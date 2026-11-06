package app.flit

import android.media.MediaScannerConnection
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** An incoming request (kept for the queue API; requests are now started right away). */
class Pending(val peer: Peer, val req: Req)

/** One file of a receiving session. [rf] exists while the file is (or was) being written. */
class RxFile(val m: Man, val dest: File, val tmp: File, val key: String) {

    @Volatile
    var state = RecvFile.WAITING

    @Volatile
    var err = RecvFile.ERR_NONE

    @Volatile
    var rf: RecvFile? = null

    /** Part of a split-APK group (main APK or one of its parts). */
    @Volatile
    var grouped = false

    /** A split part (not the main APK). */
    @Volatile
    var part = false

    val finishing = AtomicBoolean(false)
    val name: String = dest.name
    val size: Long
        get() = m.size

    fun bytes(): Long {
        if (state == RecvFile.DONE) return m.size
        val r = rf ?: return 0L
        return r.received.get().coerceIn(0L, m.size)
    }
}

/**
 * One displayed entry of a receiving session: a normal file, or a split-APK app (its main APK plus
 * the parts, shown as ONE entry with combined size, progress and status).
 */
class RxRow(val head: RxFile, val parts: List<RxFile>, val dir: File?) {

    val members: List<RxFile> = listOf(head) + parts

    fun size(): Long {
        var s = 0L
        for (m in members) s += m.size
        return s
    }

    fun bytes(): Long {
        var s = 0L
        for (m in members) s += m.bytes()
        return s
    }

    /** Combined state, in terms of RecvFile.* constants. */
    fun state(): Int {
        if (members.any { it.state == RecvFile.FAILED }) return RecvFile.FAILED
        if (members.all { it.state == RecvFile.DONE }) return RecvFile.DONE
        if (members.any { it.state == RecvFile.ACTIVE }) return RecvFile.ACTIVE
        if (members.any { it.state == RecvFile.STOPPED }) return RecvFile.STOPPED
        if (members.any { it.state == RecvFile.DONE }) return RecvFile.ACTIVE
        return RecvFile.WAITING
    }
}

/**
 * One accepted transfer from one Sender. Logic in short:
 *  - Files are activated one at a time, or (Simultaneous transfers on) up to Tuning.MAX_FILES at
 *    once. With the toggle on, all those files are opened up front and the data streams take
 *    chunks from them in turn, so several files really progress together.
 *  - A pool of 1..4 data streams pulls chunks; each stream keeps several GET requests in flight.
 *    A monitor thread adds or removes streams from measured throughput.
 *  - Every chunk is hashed while it is written; a file is renamed into place only when all chunks
 *    are verified. A failed file never stops the others.
 *  - A split-APK app arrives as several files into one folder but is shown and recorded as ONE entry.
 *  - Watchdogs: no manifest within 30 s, or no byte of progress for 45 s, ends the session as
 *    interrupted (instead of sitting at 0% forever).
 */
class RxSession(val peer: Peer, val id: String, val count: Int, val total: Long, val chunk: Int) {

    companion object {
        const val WAIT = 0
        const val RUN = 1
        const val DONE = 2
        const val STOPPED = 3
        const val LOST = 4

        private const val MANIFEST_WAIT_MS = 30_000L
        private const val STALL_MS = 45_000L
    }

    private class Job(val f: RxFile, val rf: RecvFile, val idx: Int)

    private class Worker {
        @Volatile
        var retire = false

        @Volatile
        var conn: Conn? = null
    }

    @Volatile
    var state = WAIT

    @Volatile
    var files: List<RxFile> = emptyList()

    /** Displayed entries (see [RxRow]). */
    @Volatile
    var rows: List<RxRow> = emptyList()

    @Volatile
    var paused = false

    @Volatile
    var space = false

    @Volatile
    var bytesTotal = 0L

    val name: String
        get() = peer.name

    val isEnded: Boolean
        get() = ended.get()

    private val term = AtomicLong(0L)
    private val termCount = AtomicInteger(0)
    private val net = AtomicLong(0L)
    private val active = CopyOnWriteArrayList<RxFile>()
    private val workers = CopyOnWriteArrayList<Worker>()
    private val actLock = Any()
    private var next = 0
    private val manifest = ArrayList<Man>()
    private val ended = AtomicBoolean(false)
    private val wake = Semaphore(0)
    private val ctl = StreamController(Tuning.maxStreams(false))

    @Volatile
    private var trouble = false

    @Volatile
    private var connFails = 0
    private var lastTick = 0L
    private var lastCk = 0L

    /** Round-robin position for taking chunks from the active files. */
    private val rr = AtomicInteger(0)

    private var lastProc = -1L
    private var stallSince = 0L

    // ---- Progress ---------------------------------------------------------------------------

    fun processed(): Long {
        var s = term.get()
        for (f in active) {
            val rf = f.rf
            if (rf != null) s += rf.received.get()
        }
        return s
    }

    fun fraction(): Float {
        val t = bytesTotal
        if (t <= 0L) return if (state == DONE) 1f else 0f
        return (processed().toDouble() / t).toFloat().coerceIn(0f, 1f)
    }

    // ---- Start and control frames -----------------------------------------------------------

    fun start() {
        Db.post {
            Db.upsertSession(
                Sess(id, Db.ROLE_RECEIVER, peer.name, Db.SESS_ACTIVE, total, 0L, count, System.currentTimeMillis())
            )
        }
        if (!peer.send(Proto.ACCEPT, Proto.sid(id))) {
            end(LOST)
            return
        }
        // The Sender must answer with the manifest soon; otherwise do not wait at 0% forever.
        FlitApp.main.postDelayed({
            if (state == WAIT && !ended.get()) end(LOST)
        }, MANIFEST_WAIT_MS)
    }

    fun onFrame(f: Frame) {
        try {
            when (f.type) {
                Proto.MANIFEST -> {
                    val p = Proto.parseManifest(f)
                    if (p.first == id && state == WAIT) manifest.addAll(p.second)
                }
                Proto.MANIFEST_END -> onManifestEnd()
                Proto.PAUSE -> paused = true
                Proto.RESUME -> if (!space) paused = false
                Proto.STOP -> end(STOPPED)
            }
        } catch (e: IOException) {
            end(LOST)
        }
    }

    private fun fixed(m0: Man): Man = if (m0.size < 0L) Man(m0.fid, m0.rel, 0L, m0.mtime, m0.type) else m0

    private fun onManifestEnd() {
        if (state != WAIT) return
        val pd = Chunks.partialDir()
        val n = manifest.size
        val list = ArrayList<RxFile>(n)
        val rl = ArrayList<RxRow>()
        var sum = 0L
        var i = 0
        while (i < n) {
            val m = fixed(manifest[i])
            var j = i + 1
            while (j < n && manifest[j].type == TYPE_PART) j++
            val safe = Chunks.safeRel(m.rel)
            if (j > i + 1 && m.type != TYPE_PART && safe.contains('/')) {
                // A split-APK app: all its files go into one unique folder and are shown as one entry.
                val dir = Chunks.reserve(safe.substringBefore('/'))
                val members = ArrayList<RxFile>(j - i)
                for (k in i until j) {
                    val mk = fixed(manifest[k])
                    val nm = Chunks.safeRel(mk.rel).substringAfterLast('/')
                    val key = Chunks.key(peer.id, mk.rel, mk.size, mk.mtime)
                    val rf = RxFile(mk, File(dir, nm), File(pd, "$key.part"), key)
                    rf.grouped = true
                    rf.part = k > i
                    members.add(rf)
                    sum += mk.size
                }
                list.addAll(members)
                rl.add(RxRow(members[0], members.subList(1, members.size).toList(), dir))
                i = j
            } else {
                val dest = Chunks.reserve(m.rel)
                val key = Chunks.key(peer.id, m.rel, m.size, m.mtime)
                val rf = RxFile(m, dest, File(pd, "$key.part"), key)
                list.add(rf)
                rl.add(RxRow(rf, emptyList(), null))
                sum += m.size
                i++
            }
        }
        manifest.clear()
        if (list.isEmpty()) {
            end(DONE)
            return
        }
        bytesTotal = sum
        files = list
        rows = rl
        state = RUN
        Thread({ monitor() }, "flit-rx-mon").start()
        spawn()
    }

    // ---- User actions ---------------------------------------------------------------------------

    fun pause() {
        if (state != RUN || paused) return
        paused = true
        peer.send(Proto.PAUSE, Proto.sid(id))
    }

    fun resume() {
        if (state != RUN || !paused) return
        paused = false
        space = false
        peer.send(Proto.RESUME, Proto.sid(id))
        wake.release()
    }

    fun stop() {
        if (ended.get()) return
        peer.send(Proto.STOP, Proto.sid(id))
        end(STOPPED)
    }

    /** Retries a failed file from its verified chunks (the connection must still be up). */
    fun retry(f: RxFile) {
        if (ended.get() || f.state != RecvFile.FAILED) return
        Files.pool.execute {
            synchronized(actLock) {
                if (ended.get() || f.state != RecvFile.FAILED) return@execute
                val resume = f.rf?.snapshot(id, f.key)
                val nrf = RecvFile(f.m.fid, f.m.rel, f.m.size, f.m.mtime, f.m.type, chunk, f.tmp, f.dest)
                try {
                    nrf.open(resume)
                } catch (e: NoSpaceException) {
                    noSpace()
                    return@execute
                } catch (e: IOException) {
                    return@execute
                }
                f.rf = nrf
                f.err = RecvFile.ERR_NONE
                f.finishing.set(false)
                term.addAndGet(-f.size)
                termCount.decrementAndGet()
                f.state = RecvFile.ACTIVE
                active.add(f)
                if (nrf.complete()) completeFile(f)
            }
            wake.release()
        }
    }

    // ---- Workers ------------------------------------------------------------------------------

    private fun liveWorkers(): Int = workers.count { !it.retire }

    private fun spawn() {
        val w = Worker()
        workers.add(w)
        Thread({ runWorker(w) }, "flit-rx").start()
    }

    private fun nextJob(): Job? {
        if (paused || ended.get()) return null
        // Simultaneous mode: open every allowed file up front so they all get chunks in turn.
        if (Prefs.simultaneous) while (activateNext()) { /* fill up to the limit */ }
        var j = claim()
        while (j == null && activateNext()) j = claim()
        return j
    }

    /**
     * Takes the next unclaimed chunk. With Simultaneous transfers on, the search starts at a
     * rotating file so all active files advance together; otherwise the first active file is
     * finished first.
     */
    private fun claim(): Job? {
        val snap = active.toList()
        val n = snap.size
        if (n == 0) return null
        val start = if (Prefs.simultaneous && n > 1) Math.floorMod(rr.getAndIncrement(), n) else 0
        for (k in 0 until n) {
            val f = snap[(start + k) % n]
            if (f.state != RecvFile.ACTIVE) continue
            val rf = f.rf ?: continue
            val i = rf.next()
            if (i >= 0) return Job(f, rf, i)
        }
        return null
    }

    private fun activateNext(): Boolean {
        synchronized(actLock) {
            val max = if (Prefs.simultaneous) Tuning.MAX_FILES else 1
            if (active.size >= max || paused || ended.get()) return false
            val list = files
            while (next < list.size) {
                val f = list[next]
                if (f.state != RecvFile.WAITING) {
                    next++
                    continue
                }
                val rf = RecvFile(f.m.fid, f.m.rel, f.m.size, f.m.mtime, f.m.type, chunk, f.tmp, f.dest)
                try {
                    rf.open(null)
                } catch (e: NoSpaceException) {
                    noSpace()
                    return false
                } catch (e: IOException) {
                    f.rf = rf
                    next++
                    fail(f, RecvFile.ERR_IO)
                    continue
                }
                f.rf = rf
                f.state = RecvFile.ACTIVE
                active.add(f)
                next++
                if (rf.n == 0) completeFile(f)
                return true
            }
            return false
        }
    }

    private fun runWorker(w: Worker) {
        val inflight = ArrayDeque<Job>()
        var c: Conn? = null
        try {
            c = peer.obtainData(Peer.DATA_TIMEOUT)
            if (c == null) {
                connFails++
                trouble = true
                return
            }
            connFails = 0
            w.conn = c
            val buf = ByteArray(Tuning.bufSize)
            val md = Chunks.sha()
            var bytes = 0L
            while (!ended.get() && !c.closed) {
                if (!paused && !w.retire && inflight.size < 32 &&
                    (inflight.size < Tuning.outstanding || bytes < Tuning.BATCH_BYTES)
                ) {
                    val j = nextJob()
                    if (j != null) {
                        c.send(Proto.GET, Proto.get(j.f.m.fid, j.idx))
                        inflight.addLast(j)
                        bytes += Chunks.chunkLen(j.f.m.size, chunk, j.idx)
                        continue
                    }
                }
                if (inflight.isEmpty()) {
                    if (w.retire || termCount.get() >= files.size) break
                    SystemClock.sleep(120)
                    continue
                }
                val j = inflight.removeFirst()
                bytes -= Chunks.chunkLen(j.f.m.size, chunk, j.idx)
                readChunk(c, j, buf, md)
            }
        } catch (e: Exception) {
            trouble = true
        } finally {
            for (j in inflight) j.rf.release(j.idx, 0L, false)
            c?.close()
            workers.remove(w)
            wake.release()
        }
    }

    private fun readChunk(c: Conn, j: Job, buf: ByteArray, md: MessageDigest) {
        val din = c.din
        var resolved = false
        var written = 0L
        try {
            val type = din.readUnsignedByte()
            val len = din.readInt()
            if (type == Proto.ERR) {
                if (len < 12 || len > 64) throw IOException("bad ERR")
                val body = ByteArray(len)
                din.readFully(body)
                val r = Rd(body)
                r.i32()
                r.i32()
                val code = r.i32()
                j.rf.release(j.idx, 0L, true)
                resolved = true
                if (code == Proto.ERR_CHANGED || code == Proto.ERR_READ) fail(j.f, RecvFile.ERR_SOURCE)
                else if (j.rf.exhausted()) fail(j.f, RecvFile.ERR_HASH)
                return
            }
            if (type != Proto.CHUNK) throw IOException("unexpected frame")
            val fid = din.readInt()
            val idx = din.readInt()
            val expect = Chunks.chunkLen(j.f.m.size, chunk, j.idx)
            if (fid != j.f.m.fid || idx != j.idx || len != 8 + expect + Proto.HASH) throw IOException("chunk mismatch")

            if (j.f.state != RecvFile.ACTIVE || j.f.rf !== j.rf) {
                var left = expect + Proto.HASH
                while (left > 0) {
                    val n = minOf(buf.size, left)
                    din.readFully(buf, 0, n)
                    left -= n
                }
                resolved = true
                return
            }

            md.reset()
            var off = 0
            try {
                while (off < expect) {
                    val n = minOf(buf.size, expect - off)
                    din.readFully(buf, 0, n)
                    md.update(buf, 0, n)
                    j.rf.write(j.idx, off, buf, n)
                    written += n
                    net.addAndGet(n.toLong())
                    off += n
                }
            } catch (e: NoSpaceException) {
                noSpace()
                throw e
            }
            val h = ByteArray(Proto.HASH)
            din.readFully(h)
            val calc = md.digest()
            if (!MessageDigest.isEqual(h, calc)) {
                j.rf.release(j.idx, written, true)
                resolved = true
                if (j.rf.exhausted()) fail(j.f, RecvFile.ERR_HASH)
                return
            }
            j.rf.commit(j.idx, calc)
            resolved = true
            if (j.rf.complete()) completeFile(j.f)
        } finally {
            if (!resolved) j.rf.release(j.idx, written, false)
        }
    }

    // ---- File results ------------------------------------------------------------------------

    private fun completeFile(f: RxFile) {
        val rf = f.rf ?: return
        synchronized(f) {
            if (f.state != RecvFile.ACTIVE || !f.finishing.compareAndSet(false, true)) return
        }
        try {
            if (!rf.finish()) {
                f.finishing.set(false)
                return
            }
        } catch (e: IOException) {
            f.finishing.set(false)
            fail(f, RecvFile.ERR_IO)
            return
        }
        f.state = RecvFile.DONE
        active.remove(f)
        term.addAndGet(f.size)
        termCount.incrementAndGet()
        peer.send(Proto.FILE_DONE, Proto.fileDone(f.m.fid, Proto.FILE_OK, rf.root))
        Chunks.unreserve(f.dest)

        val dest = f.dest
        val size = f.size
        val root = rf.root
        val now = System.currentTimeMillis()
        val from = peer.name
        val grouped = f.grouped
        Db.post {
            Db.deletePart(f.key)
            // Split-APK groups are recorded once, as one entry, when the session ends.
            if (!grouped) {
                val type = Types.typeOf(dest.name)
                var pkg = ""
                var vc = 0L
                if (type == Types.APK) {
                    val m = Files.apkMeta(dest.path)
                    if (m != null) {
                        pkg = m.first
                        vc = m.second
                    }
                }
                Db.addHistory(Hist(0, Db.RECEIVED, dest.name, dest.path, size, type, Chunks.hex(root), now, pkg, vc, from))
            }
        }
        try {
            MediaScannerConnection.scanFile(FlitApp.app, arrayOf(dest.path), null, null)
        } catch (e: Exception) {
        }
        wake.release()
    }

    private fun fail(f: RxFile, err: Int) {
        synchronized(f) {
            if (f.finishing.get()) return
            if (f.state == RecvFile.DONE || f.state == RecvFile.FAILED || f.state == RecvFile.STOPPED ||
                f.state == RecvFile.EXISTS
            ) return
            f.err = err
            f.state = RecvFile.FAILED
        }
        active.remove(f)
        val rf = f.rf
        if (rf != null) {
            val p = rf.snapshot(id, f.key)
            Db.post { Db.upsertPart(p) }
            rf.close()
        }
        term.addAndGet(f.size)
        termCount.incrementAndGet()
        peer.send(Proto.FILE_DONE, Proto.fileDone(f.m.fid, Proto.FILE_FAILED, ByteArray(0)))
        wake.release()
    }

    private fun noSpace() {
        if (space) return
        space = true
        paused = true
        peer.send(Proto.PAUSE, Proto.sid(id))
        FlitApp.main.post {
            Notifs.storage()
            Xfer.onSpace?.invoke(this)
        }
    }

    // ---- Monitor ------------------------------------------------------------------------------

    private fun monitor() {
        while (!ended.get()) {
            try {
                wake.tryAcquire(1, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
            }
            wake.drainPermits()
            if (ended.get()) break
            val now = SystemClock.elapsedRealtime()
            if (now - lastTick >= 900L) {
                lastTick = now
                tick()
                if (ended.get()) break
            }
            if (termCount.get() >= files.size) {
                end(DONE)
                break
            }
            if (now - lastCk >= Tuning.CHECKPOINT_MS) {
                lastCk = now
                for (f in active) {
                    val rf = f.rf ?: continue
                    val p = rf.snapshot(id, f.key)
                    Db.post { Db.upsertPart(p) }
                }
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val proc = processed()
        if (proc != lastProc || paused || termCount.get() >= files.size) {
            lastProc = proc
            stallSince = now
        } else if (now - stallSince > STALL_MS) {
            end(LOST) // nothing moved for a long time: report it instead of waiting forever
            return
        }
        val remaining = (bytesTotal - proc).coerceAtLeast(0L)
        val d = ctl.tick(net.get(), remaining, paused)
        if (trouble) {
            trouble = false
            ctl.trouble(maxOf(1, liveWorkers()))
        } else if (d == -1) {
            workers.lastOrNull { !it.retire }?.retire = true
        }
        if (!paused && termCount.get() < files.size) {
            while (liveWorkers() < ctl.n) spawn()
        }
        ctl.sync(maxOf(1, liveWorkers()))
        if (connFails >= 5 && workers.isEmpty()) end(LOST)
        Xfer.tickPost()
    }

    // ---- Ending ---------------------------------------------------------------------------------

    fun end(st: Int) {
        if (!ended.compareAndSet(false, true)) return
        state = st
        for (w in workers) w.conn?.close()
        for (f in files) {
            when (f.state) {
                RecvFile.ACTIVE -> {
                    val rf = f.rf
                    if (rf != null) {
                        val p = rf.snapshot(id, f.key)
                        Db.post { Db.upsertPart(p) }
                        rf.close()
                    }
                    f.state = RecvFile.STOPPED
                }
                RecvFile.WAITING -> f.state = RecvFile.STOPPED
            }
            if (f.state != RecvFile.DONE) Chunks.unreserve(f.dest)
        }
        active.clear()

        // Count and record by displayed entry (a split-APK app is one entry, complete only if all
        // its files arrived).
        var done = 0
        var failed = 0
        val now = System.currentTimeMillis()
        val from = peer.name
        for (row in rows) {
            val s = row.state()
            if (s == RecvFile.DONE) done++ else if (s == RecvFile.FAILED) failed++
            row.dir?.let { Chunks.unreserve(it) }
            if (row.parts.isNotEmpty() && s == RecvFile.DONE) {
                val head = row.head
                val dest = head.dest
                val size = row.size()
                val root = head.rf?.root ?: ByteArray(0)
                Db.post {
                    val type = Types.typeOf(dest.name)
                    var pkg = ""
                    var vc = 0L
                    if (type == Types.APK) {
                        val m = Files.apkMeta(dest.path)
                        if (m != null) {
                            pkg = m.first
                            vc = m.second
                        }
                    }
                    Db.addHistory(Hist(0, Db.RECEIVED, dest.name, dest.path, size, type, Chunks.hex(root), now, pkg, vc, from))
                }
            }
        }

        if (st == DONE) {
            peer.send(Proto.FINISHED, Proto.sid(id))
            FlitApp.main.postDelayed({ peer.close() }, 1500L) // give the Sender time to read FINISHED
        } else {
            peer.close()
        }
        Db.post { Db.endSession(id) }
        P2p.release()
        wake.release()
        ReceiveEngine.sessionEnded(this, st, done, failed)
    }
}

/**
 * Receive side. The Android Wi-Fi Direct invitation already asked the user, so a request that
 * arrives while Receiver mode is on starts at once (no second Flit dialog). Running sessions: at
 * most one per Sender.
 */
object ReceiveEngine {

    private val sessions = ConcurrentHashMap<String, RxSession>()

    /** Main thread only. Kept for the queue API; requests are no longer parked here. */
    val pending = ArrayList<Pending>()

    private val h = FlitApp.main

    fun sessionList(): List<RxSession> = sessions.values.toList()

    fun busy(): Boolean = sessions.isNotEmpty()

    fun head(): Pending? = pending.firstOrNull()

    /** A Sender's control connection arrived (any thread). */
    fun onPeer(peer: Peer) {
        peer.startReader({ f -> onFrame(peer, f) }, { onClosed(peer) })
    }

    private fun onFrame(peer: Peer, f: Frame) {
        val s = sessions[peer.id]
        if (s != null) {
            s.onFrame(f)
            return
        }
        when (f.type) {
            Proto.REQUEST -> {
                val rq = try {
                    Proto.parseRequest(f)
                } catch (e: IOException) {
                    return
                }
                h.post { enqueue(peer, rq) }
            }
            Proto.CANCEL -> h.post { dropPeer(peer) }
        }
    }

    private fun onClosed(peer: Peer) {
        sessions[peer.id]?.end(RxSession.LOST)
        h.post { dropPeer(peer) }
    }

    private fun enqueue(peer: Peer, rq: Req) {
        if (!peer.alive) return
        val ok = Rx.active && rq.count > 0 && rq.chunk in 65536..(16 shl 20) &&
            !sessions.containsKey(peer.id)
        if (!ok) {
            peer.send(Proto.DECLINE, Proto.sid(rq.session))
            h.postDelayed({ peer.close() }, 1500L)
            return
        }
        // The system invitation already asked the user: start right away and show the transfer.
        val s = accept(Pending(peer, rq)) ?: return
        if (!Xfer.foreground) Notifs.incoming(peer.name)
        Xfer.hook?.incoming()
        if (s.isEnded) Notifs.clearIncoming()
    }

    private fun remove(p: Pending, gone: Boolean) {
        val head = pending.firstOrNull() === p
        if (!pending.remove(p)) return
        if (head && gone) Xfer.hook?.incomingGone()
        if (pending.isEmpty()) Notifs.clearIncoming()
    }

    private fun dropPeer(peer: Peer) {
        for (p in pending.filter { it.peer === peer }) remove(p, true)
    }

    /** Starts the session for [p]. */
    fun accept(p: Pending): RxSession? {
        remove(p, false)
        if (!p.peer.alive || sessions.containsKey(p.peer.id)) return null
        val s = RxSession(p.peer, p.req.session, p.req.count, p.req.total, p.req.chunk)
        sessions[p.peer.id] = s
        P2p.acquire()
        Xfer.focusRx = s
        s.start()
        Xfer.sync()
        return s
    }

    fun decline(p: Pending, expired: Boolean = false) {
        remove(p, expired)
        p.peer.send(Proto.DECLINE, Proto.sid(p.req.session))
        h.postDelayed({ p.peer.close() }, 1500L)
    }

    fun declineAll() {
        for (p in pending.toList()) decline(p, true)
    }

    fun sessionEnded(s: RxSession, st: Int, done: Int, failed: Int) {
        h.post {
            sessions.remove(s.peer.id, s)
            if (sessions.isEmpty()) Bubbles.transfer(false)
            if (Xfer.focusRx === s) Xfer.focusRx = null
            Notifs.clearIncoming()
            if (st == RxSession.LOST) Notifs.interrupted()
            if (failed > 0) Notifs.failed(failed)
            if (done > 0 && !Xfer.foreground) Notifs.done(done)
            h.postDelayed({
                if (Rx.active && sessions.isEmpty() && pending.isEmpty() && !Link.hasPeers()) {
                    P2p.leave()
                    P2p.refreshAdvertising()
                }
            }, 3000L)
            Xfer.sync()
            Xfer.hook?.receiveFinished(s)
        }
    }
}
