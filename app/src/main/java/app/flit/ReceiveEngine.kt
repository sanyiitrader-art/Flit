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

/** An incoming request waiting for the user's decision. */
class Pending(val peer: Peer, val req: Req)

/** One file of a receiving session. [rf] exists while the file is (or was) being written. */
class RxFile(val m: Man, val dest: File, val tmp: File, val key: String) {

    @Volatile
    var state = RecvFile.WAITING

    @Volatile
    var err = RecvFile.ERR_NONE

    @Volatile
    var rf: RecvFile? = null

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
 * One accepted transfer from one Sender. Logic in short:
 *  - Files are activated one at a time (or up to 4 with the Receiver toggle on).
 *  - A pool of 1..4 data streams pulls chunks from any active file; each stream keeps several GET
 *    requests in flight. A monitor thread adds or removes streams from measured throughput.
 *  - Every chunk is hashed while it is written; a file is renamed into place only when all chunks
 *    are verified. A failed file never stops the others.
 */
class RxSession(val peer: Peer, val id: String, val count: Int, val total: Long, val chunk: Int) {

    companion object {
        const val WAIT = 0
        const val RUN = 1
        const val DONE = 2
        const val STOPPED = 3
        const val LOST = 4
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
        if (!peer.send(Proto.ACCEPT, Proto.sid(id))) end(LOST)
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

    private fun onManifestEnd() {
        if (state != WAIT) return
        val pd = Chunks.partialDir()
        val list = ArrayList<RxFile>(manifest.size)
        var sum = 0L
        for (m0 in manifest) {
            val m = if (m0.size < 0L) Man(m0.fid, m0.rel, 0L, m0.mtime, m0.type) else m0
            val dest = Chunks.reserve(m.rel)
            val key = Chunks.key(peer.id, m.rel, m.size, m.mtime)
            list.add(RxFile(m, dest, File(pd, "$key.part"), key))
            sum += m.size
        }
        manifest.clear()
        if (list.isEmpty()) {
            end(DONE)
            return
        }
        bytesTotal = sum
        files = list
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
        var j = claim()
        while (j == null && activateNext()) j = claim()
        return j
    }

    private fun claim(): Job? {
        for (f in active) {
            if (f.state != RecvFile.ACTIVE) continue
            val rf = f.rf ?: continue
            val i = rf.next()
            if (i >= 0) return Job(f, rf, i)
        }
        return null
    }

    /** Opens the next waiting file if fewer than the allowed number are active. */
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

    /** Reads one response (a CHUNK or an ERR). Any exception means the stream is no longer usable. */
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
                // The file failed or was restarted meanwhile: read the frame and drop it.
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
        Db.post {
            Db.deletePart(f.key)
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
        val remaining = (bytesTotal - processed()).coerceAtLeast(0L)
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
        var done = 0
        var failed = 0
        for (f in files) {
            when (f.state) {
                RecvFile.DONE -> done++
                RecvFile.FAILED -> failed++
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
 * Receive side: the queue of incoming requests (one dialog at a time, via [Xfer.hook]) and the
 * running sessions (at most one per Sender).
 */
object ReceiveEngine {

    private val sessions = ConcurrentHashMap<String, RxSession>()

    /** Main thread only. The head is the request currently shown to the user. */
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
            !sessions.containsKey(peer.id) && pending.none { it.peer === peer }
        if (!ok) {
            peer.send(Proto.DECLINE, Proto.sid(rq.session))
            h.postDelayed({ peer.close() }, 1500L)
            return
        }
        val p = Pending(peer, rq)
        pending.add(p)
        Notifs.incoming(peer.name)
        if (pending.size == 1) Xfer.hook?.incoming()
        h.postDelayed({ if (pending.contains(p)) decline(p, true) }, 90_000L)
    }

    /** [gone]: the dialog for the head request may still be on screen and must be dismissed. */
    private fun remove(p: Pending, gone: Boolean) {
        val head = pending.firstOrNull() === p
        if (!pending.remove(p)) return
        if (head && gone) Xfer.hook?.incomingGone()
        if (pending.isEmpty()) Notifs.clearIncoming() else if (head) Xfer.hook?.incoming()
    }

    private fun dropPeer(peer: Peer) {
        for (p in pending.filter { it.peer === peer }) remove(p, true)
    }

    /** The user accepted the request: starts the session. */
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

    /** [expired]: not from the dialog (timeout or Receiver mode switched off). */
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
            if (st == RxSession.LOST) Notifs.interrupted()
            if (failed > 0) Notifs.failed(failed)
            if (done > 0 && !Xfer.foreground) Notifs.done(done)
            // Back to being discoverable: leave the group once nothing else uses it.
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