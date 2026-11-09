package app.flit

import android.util.Log
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

private const val TAG = "FlitSend"

/**
 * Manifest type of a split-APK part. The parts of one app follow their main APK in the manifest;
 * both sides show the whole group as ONE entry while the files still travel separately.
 */
const val TYPE_PART = 100

/** One file of a send run. [label] is the name shown in History; [hist] is false for split-APK parts. */
class TxFile(
    val fid: Int,
    val path: String,
    val rel: String,
    val size: Long,
    val mtime: Long,
    val type: Int,
    val label: String,
    val hist: Boolean,
    val part: Boolean = false
) {
    val name: String = File(rel).name
}

/** One displayed entry: files [start, end) of the run (a normal file, or an app with its splits). */
class TxRow(val start: Int, val end: Int)

/** Chunk hashes served so far for one file (used to verify the Receiver's root hash). */
private class Tc(val n: Int) {
    val h = ByteArray(n * Proto.HASH)
    val has = BitSet(n)
}

/** One Receiver of a send run: connection state, per-file status and progress. */
class TxSession(val run: TxRun, val target: P2p.Found) {

    companion object {
        const val CONNECTING = 0
        const val REQUESTED = 1
        const val ACTIVE = 2
        const val DONE = 3
        const val DECLINED = 4
        const val STOPPED = 5
        const val FAILED = 6
        const val LOST = 7

        const val ST_WAIT = 0
        const val ST_SEND = 1
        const val ST_SENT = 2
        const val ST_FAIL = 3
        const val ST_EXISTS = 4
    }

    val id: String = UUID.randomUUID().toString().replace("-", "").take(16)

    @Volatile
    var state = CONNECTING

    /** Internal reason of a failure (diagnostics only; the user sees a generic message). */
    @Volatile
    var why = ""

    @Volatile
    var peer: Peer? = null

    @Volatile
    var paused = false

    @Volatile
    var accepted = false

    @Volatile
    var prepared = false

    @Volatile
    var status: AtomicIntegerArray = AtomicIntegerArray(0)

    @Volatile
    var sent: AtomicLongArray = AtomicLongArray(0)

    val advanced = AtomicBoolean(false)
    val sentTotal = AtomicLong(0L)

    private val srcs = ConcurrentHashMap<Int, Src>()
    private val chunks = ConcurrentHashMap<Int, Tc>()

    val name: String
        get() = peer?.name ?: target.name

    val alive: Boolean
        get() = state <= ACTIVE

    @Synchronized
    fun prepare() {
        if (prepared) return
        val n = run.files.size
        status = AtomicIntegerArray(n)
        sent = AtomicLongArray(n)
        prepared = true
    }

    fun src(fid: Int): Src = srcs.computeIfAbsent(fid) { Src(run.files[fid].path) }

    fun closeSrc(fid: Int) {
        srcs.remove(fid)?.close()
    }

    fun closeSrcs() {
        for (s in srcs.values) s.close()
        srcs.clear()
    }

    fun addSent(fid: Int, n: Int) {
        val cap = run.files[fid].size
        while (true) {
            val old = sent.get(fid)
            val nw = minOf(cap, old + n)
            if (nw == old) return
            if (sent.compareAndSet(fid, old, nw)) {
                sentTotal.addAndGet(nw - old)
                return
            }
        }
    }

    fun setSentFull(fid: Int) {
        val old = sent.getAndSet(fid, run.files[fid].size)
        sentTotal.addAndGet(run.files[fid].size - old)
    }

    fun noteHash(fid: Int, idx: Int, h: ByteArray) {
        val tc = chunks.computeIfAbsent(fid) { Tc(Chunks.count(run.files[fid].size, run.chunk)) }
        synchronized(tc) {
            System.arraycopy(h, 0, tc.h, idx * Proto.HASH, Proto.HASH)
            tc.has.set(idx)
        }
    }

    fun rootOf(fid: Int): ByteArray? {
        val tc = chunks[fid] ?: return null
        synchronized(tc) {
            if (tc.has.cardinality() != tc.n) return null
            return Chunks.root(tc.h, tc.n)
        }
    }

    fun fraction(): Float {
        val t = run.total
        return if (t > 0L) (sentTotal.get().toDouble() / t).toFloat().coerceIn(0f, 1f) else 0f
    }
}

/** One send operation: the selected items, the manifest built from them, and one session per Receiver. */
class TxRun(val items: List<Item>) {

    @Volatile
    var files: List<TxFile> = emptyList()

    /** Displayed entries: a split-APK app is ONE row covering its main APK and its parts. */
    @Volatile
    var rows: List<TxRow> = emptyList()

    @Volatile
    var mans: List<Man> = emptyList()

    @Volatile
    var total = 0L

    @Volatile
    var missing = 0

    @Volatile
    var ready = false

    @Volatile
    var finished = false

    val chunk: Int = Tuning.chunkSize
    val sessions = CopyOnWriteArrayList<TxSession>()
    val byId = ConcurrentHashMap<String, TxSession>()
    val fin = AtomicBoolean(false)
    private val latch = CountDownLatch(1)

    /** Background: stats every file and builds the manifest. Missing files are skipped and counted. */
    fun build() {
        val list = ArrayList<TxFile>(items.size)
        var miss = 0
        for (item in items) {
            if (item.splits.isEmpty()) {
                val f = File(item.path)
                if (!f.isFile) {
                    miss++
                    continue
                }
                list.add(TxFile(list.size, item.path, item.rel, f.length(), f.lastModified(), item.type, item.name, true))
            } else {
                val folder = item.rel.removeSuffix(".apk")
                val base = File(item.path)
                if (!base.isFile) {
                    miss++
                    continue
                }
                // Main APK first, then its parts (named split_*), all inside one folder.
                list.add(
                    TxFile(list.size, item.path, "$folder/$folder.apk", base.length(), base.lastModified(),
                        Types.APK, item.name, true)
                )
                for (p in item.splits) {
                    val f = File(p)
                    if (!f.isFile) {
                        miss++
                        continue
                    }
                    val nm = if (f.name.startsWith("split_")) f.name else "split_" + f.name
                    list.add(
                        TxFile(list.size, p, "$folder/$nm", f.length(), f.lastModified(), Types.APK,
                            item.name, false, true)
                    )
                }
            }
        }
        var sum = 0L
        val m = ArrayList<Man>(list.size)
        for (f in list) {
            sum += f.size
            m.add(Man(f.fid, f.rel, f.size, f.mtime, if (f.part) TYPE_PART else f.type))
        }
        val rl = ArrayList<TxRow>()
        var i = 0
        while (i < list.size) {
            var j = i + 1
            while (j < list.size && list[j].part) j++
            rl.add(TxRow(i, j))
            i = j
        }
        synchronized(this) {
            files = list
            rows = rl
            mans = m
            total = sum
            missing = miss
            for (s in sessions) s.prepare()
            ready = true
        }
        Log.d(TAG, "manifest ready: ${list.size} files in ${rl.size} entries, $sum bytes, $miss missing")
        latch.countDown()
    }

    fun awaitReady(ms: Long): Boolean = latch.await(ms, TimeUnit.MILLISECONDS)

    @Synchronized
    fun add(s: TxSession) {
        sessions.add(s)
        byId[s.target.id] = s
        if (ready) s.prepare()
    }

    fun progress(): Float {
        var sent = 0L
        var den = 0L
        for (s in sessions) {
            if (s.accepted) {
                sent += s.sentTotal.get()
                den += total
            }
        }
        return if (den > 0L) (sent.toDouble() / den).toFloat().coerceIn(0f, 1f) else 0f
    }
}

/**
 * Send side. One [TxRun] at a time. Receivers are connected one after another (a Wi-Fi Direct
 * device can only negotiate one invitation at a time): the next Receiver is invited only after the
 * previous one's control connection is up (plus a short settle time) or its attempt failed, so a
 * new invitation never disturbs one that is still being accepted. Each connected Receiver gets its
 * own [TxSession] with independent pause / stop. Data is pulled by the Receiver: this side answers
 * GET requests on any number of data connections, hashing every chunk while it streams it.
 * Diagnostics use the log tag "FlitSend"; every failure records an internal reason.
 */
object SendEngine {

    @Volatile
    var cur: TxRun? = null

    val running: Boolean
        get() {
            val r = cur
            return r != null && !r.finished
        }

    private val h = FlitApp.main
    private val queue = ArrayList<TxSession>()

    /** Pause between one Receiver's link coming up and the next invitation. */
    private const val SETTLE_MS = 1500L

    /** Longest a single Receiver may hold up the invitations behind it (user has to accept first). */
    private const val CONNECT_MAX_MS = 45_000L

    fun begin(items: List<Item>): TxRun? {
        val c = cur
        if (c != null && !c.finished) return c
        Xfer.ensure()
        if (!P2p.acquire()) {
            Log.w(TAG, "begin: Wi-Fi Direct could not be acquired")
            return null
        }
        val r = TxRun(items)
        cur = r
        queue.clear()
        Link.sending = true
        Link.use(true)
        Xfer.focusTx = r
        Log.d(TAG, "begin: ${items.size} selected item(s)")
        Files.pool.execute { r.build() }
        h.postDelayed(object : Runnable {
            override fun run() {
                if (!r.finished) {
                    Xfer.tick()
                    h.postDelayed(this, 1000L)
                }
            }
        }, 1000L)
        Xfer.sync()
        return r
    }

    fun search(l: P2p.Discovery) {
        P2p.discover(10_000L, l)
    }

    fun send(targets: List<P2p.Found>) {
        val r = cur ?: return
        Log.d(TAG, "send: ${targets.size} target(s): " + targets.joinToString { P2p.mask(it.address) })
        P2p.stopDiscovery()
        for (t in targets) {
            val s = TxSession(r, t)
            r.add(s)
            queue.add(s)
        }
        connectNext(r)
        Xfer.sync()
    }

    fun cancel() {
        val r = cur ?: return
        Log.d(TAG, "cancel: run abandoned")
        if (!r.finished) finish(r)
    }

    // ---- Connecting ------------------------------------------------------------------------

    private fun connectNext(r: TxRun) {
        if (r.finished || queue.isEmpty()) return
        val s = queue.removeAt(0)
        if (s.state != TxSession.CONNECTING) {
            connectNext(r)
            return
        }
        Log.d(TAG, "connecting to '${s.target.name}' (${P2p.mask(s.target.address)}), ${queue.size} more queued")
        P2p.connect(s.target.address) { ok ->
            if (!ok) {
                fail(s, "invitation failed: ${P2p.lastFail}")
                advance(r, s)
            } else {
                Log.d(TAG, "invitation sent to '${s.target.name}', waiting for the group and the TCP link")
            }
        }
        // The next Receiver waits for this one (control link up, failure, or this time limit).
        h.postDelayed({
            if (s.state == TxSession.CONNECTING) {
                fail(s, "no control connection within ${CONNECT_MAX_MS / 1000} s (not accepted, group not formed, or TCP dial failed)")
            }
            advance(r, s)
        }, CONNECT_MAX_MS)
    }

    private fun advance(r: TxRun, s: TxSession) {
        if (s.advanced.compareAndSet(false, true)) connectNext(r)
    }

    private fun fail(s: TxSession, why: String) {
        if (s.state == TxSession.CONNECTING || s.state == TxSession.REQUESTED) {
            s.why = why
            Log.w(TAG, "session with '${s.target.name}' failed (state=${s.state}): $why")
            s.state = TxSession.FAILED
            s.peer?.close()
            check(s.run)
        }
    }

    fun onPeer(peer: Peer) {
        val r = cur
        val s = r?.byId?.get(peer.id)
        if (r == null || r.finished || s == null || s.peer != null || s.state != TxSession.CONNECTING) {
            Log.w(
                TAG,
                "onPeer: rejecting '${peer.name}' (run=${r != null} finished=${r?.finished} " +
                    "known=${s != null} hasPeer=${s?.peer != null} state=${s?.state})"
            )
            peer.close()
            return
        }
        Log.d(TAG, "onPeer: control connection to '${peer.name}' established")
        s.peer = peer
        peer.dataHandler = { c -> serve(s, c) }
        peer.startReader({ f -> onFrame(s, f) }, { onClosed(s) })
        // Let this link settle before the next invitation is sent.
        h.postDelayed({ advance(r, s) }, SETTLE_MS)
        if (!r.awaitReady(20_000L) || r.files.isEmpty()) {
            fail(s, "manifest not ready or empty")
            peer.close()
            return
        }
        s.state = TxSession.REQUESTED
        if (!peer.send(Proto.REQUEST, Proto.request(s.id, r.files.size, r.total, r.chunk))) return
        Log.d(TAG, "REQUEST sent to '${peer.name}': ${r.files.size} files, ${r.total} bytes")
        h.postDelayed({
            if (s.state == TxSession.REQUESTED) {
                peer.send(Proto.CANCEL, Proto.sid(s.id))
                fail(s, "no answer to the request within 60 s")
                peer.close()
            }
        }, 60_000L)
    }

    // ---- Control frames --------------------------------------------------------------------

    private fun onFrame(s: TxSession, f: Frame) {
        val peer = s.peer ?: return
        try {
            when (f.type) {
                Proto.ACCEPT -> if (s.state == TxSession.REQUESTED) {
                    Log.d(TAG, "ACCEPT from '${s.name}', sending the manifest")
                    s.state = TxSession.ACTIVE
                    s.accepted = true
                    sendManifest(s, peer)
                }
                Proto.DECLINE -> end(s, TxSession.DECLINED)
                Proto.FILE_DONE -> fileDone(s, f)
                Proto.FINISHED -> end(s, TxSession.DONE)
                Proto.PAUSE -> s.paused = true
                Proto.RESUME -> s.paused = false
                Proto.STOP -> end(s, TxSession.STOPPED)
                Proto.STREAM_WANT -> Thread({
                    val c = peer.dial(0)
                    if (c != null) serve(s, c)
                }, "flit-dial2").start()
            }
        } catch (e: IOException) {
        }
    }

    private fun sendManifest(s: TxSession, peer: Peer) {
        val m = s.run.mans
        var i = 0
        while (i < m.size) {
            val j = minOf(m.size, i + 400)
            if (!peer.send(Proto.MANIFEST, Proto.manifest(s.id, m, i, j))) return
            i = j
        }
        peer.send(Proto.MANIFEST_END, Proto.sid(s.id))
    }

    private fun fileDone(s: TxSession, f: Frame) {
        val r = f.rd()
        val fid = r.i32()
        val st = r.u8()
        val root = r.bytes()
        if (!s.prepared || fid < 0 || fid >= s.status.length()) return
        when (st) {
            Proto.FILE_OK -> {
                val mine = s.rootOf(fid)
                if (mine == null || mine.contentEquals(root)) {
                    s.setSentFull(fid)
                    s.status.set(fid, TxSession.ST_SENT)
                } else {
                    s.status.set(fid, TxSession.ST_FAIL)
                }
            }
            Proto.FILE_EXISTS -> {
                s.setSentFull(fid)
                s.status.set(fid, TxSession.ST_EXISTS)
            }
            else -> s.status.set(fid, TxSession.ST_FAIL)
        }
        s.closeSrc(fid)
    }

    private fun onClosed(s: TxSession) {
        if (s.state <= TxSession.ACTIVE) {
            if (s.why.isEmpty()) s.why = "control connection closed (state=${s.state})"
            Log.w(TAG, "control connection to '${s.name}' closed while state=${s.state}")
            s.state = if (s.state == TxSession.ACTIVE) TxSession.LOST else TxSession.FAILED
        }
        s.closeSrcs()
        check(s.run)
    }

    private fun end(s: TxSession, st: Int) {
        if (s.state >= TxSession.DONE) return
        Log.d(TAG, "session with '${s.name}' ended: state=$st")
        s.state = st
        s.closeSrcs()
        val p = s.peer
        if (p != null) h.postDelayed({ p.close() }, 800L)
        check(s.run)
    }

    // ---- Per-Receiver controls -------------------------------------------------------------

    fun pause(s: TxSession) {
        if (s.state != TxSession.ACTIVE || s.paused) return
        s.paused = true
        s.peer?.send(Proto.PAUSE, Proto.sid(s.id))
    }

    fun resume(s: TxSession) {
        if (s.state != TxSession.ACTIVE || !s.paused) return
        s.paused = false
        s.peer?.send(Proto.RESUME, Proto.sid(s.id))
    }

    fun stop(s: TxSession) {
        if (!s.alive) return
        s.peer?.send(if (s.state == TxSession.REQUESTED) Proto.CANCEL else Proto.STOP, Proto.sid(s.id))
        end(s, TxSession.STOPPED)
    }

    // ---- Serving data ----------------------------------------------------------------------

    private fun serve(s: TxSession, c: Conn) {
        val buf = ByteArray(Tuning.bufSize)
        val md = Chunks.sha()
        Log.d(TAG, "data connection for '${s.name}' is being served")
        try {
            while (!c.closed && s.state <= TxSession.ACTIVE) {
                val f = c.read()
                if (f.type == Proto.GET) get(s, c, f, buf, md)
            }
        } catch (e: IOException) {
        } catch (e: RuntimeException) {
        } finally {
            c.close()
        }
    }

    private fun get(s: TxSession, c: Conn, f: Frame, buf: ByteArray, md: MessageDigest) {
        val rd = f.rd()
        val fid = rd.i32()
        val idx = rd.i32()
        val r = s.run
        val files = r.files
        if (!s.prepared || fid < 0 || fid >= files.size) {
            c.send(Proto.ERR, Proto.err(fid, idx, Proto.ERR_RANGE))
            return
        }
        val tf = files[fid]
        if (idx < 0 || idx >= Chunks.count(tf.size, r.chunk)) {
            c.send(Proto.ERR, Proto.err(fid, idx, Proto.ERR_RANGE))
            return
        }
        val src = s.src(fid)
        try {
            src.open()
        } catch (e: IOException) {
            c.send(Proto.ERR, Proto.err(fid, idx, Proto.ERR_READ))
            return
        }
        if (src.size != tf.size || src.mtime != tf.mtime) {
            c.send(Proto.ERR, Proto.err(fid, idx, Proto.ERR_CHANGED))
            return
        }
        val len = Chunks.chunkLen(tf.size, r.chunk, idx)
        s.status.compareAndSet(fid, TxSession.ST_WAIT, TxSession.ST_SEND)
        c.beginFrame(Proto.CHUNK, 8 + len + Proto.HASH)
        c.dout.writeInt(fid)
        c.dout.writeInt(idx)
        md.reset()
        var left = len
        var pos = idx.toLong() * r.chunk
        while (left > 0) {
            val n = src.read(buf, minOf(left, buf.size), pos)
            if (n <= 0) throw IOException("source read")
            md.update(buf, 0, n)
            c.dout.write(buf, 0, n)
            s.addSent(fid, n)
            pos += n
            left -= n
        }
        val hash = md.digest()
        c.dout.write(hash)
        c.dout.flush()
        s.noteHash(fid, idx, hash)
    }

    // ---- Finishing ---------------------------------------------------------------------------

    private fun check(r: TxRun) {
        h.post {
            if (!r.finished && r.sessions.isNotEmpty() && queue.isEmpty() &&
                r.sessions.all { it.state >= TxSession.DONE }
            ) {
                finish(r)
            }
        }
    }

    private fun finish(r: TxRun) {
        if (!r.fin.compareAndSet(false, true)) return
        r.finished = true
        for (s in r.sessions) {
            Log.d(TAG, "finish: '${s.name}' state=${s.state} accepted=${s.accepted} why='${s.why}'")
        }
        val files = r.files
        val rows = r.rows
        val n = files.size
        val okFid = BooleanArray(n)
        val failFid = BooleanArray(n)
        var lost = false
        var accepted = false
        var connFail = false
        for (s in r.sessions) {
            if (s.accepted) accepted = true
            if (s.accepted) s.peer?.avatar?.let { av -> val nm = s.name; Files.pool.execute { PeerAvatars.save(nm, av) } }
            if (s.state == TxSession.LOST) lost = true
            if (s.state == TxSession.FAILED && !s.accepted) connFail = true
            if (s.prepared && s.accepted) {
                for (i in 0 until n) {
                    val st = s.status.get(i)
                    if (st == TxSession.ST_SENT || st == TxSession.ST_EXISTS) okFid[i] = true
                    else if (st == TxSession.ST_FAIL) failFid[i] = true
                }
            }
            s.closeSrcs()
            s.peer?.close()
        }

        // Count and record by displayed entry: an app with splits is one entry, complete only
        // when its main APK and every part were sent.
        var ok = 0
        var failed = r.missing
        val rowOk = BooleanArray(rows.size)
        for ((k, row) in rows.withIndex()) {
            var all = true
            var anyFail = false
            for (i in row.start until row.end) {
                if (!okFid[i]) all = false
                if (failFid[i]) anyFail = true
            }
            if (all) {
                ok++
                rowOk[k] = true
            } else if (anyFail) {
                failed++
            }
        }

        Link.sending = false
        Link.use(false)
        P2p.stopDiscovery()
        P2p.leave()
        P2p.release()
        Bubbles.transfer(false)
        Xfer.focusTx = null

        if (ok > 0) {
            val names = r.sessions.filter { it.accepted }.joinToString(", ") { it.name }
            Files.pool.execute {
                val now = System.currentTimeMillis()
                for ((k, row) in rows.withIndex()) {
                    if (!rowOk[k]) continue
                    val tf = files[row.start]
                    var size = 0L
                    for (i in row.start until row.end) size += files[i].size
                    var pkg = ""
                    var vc = 0L
                    if (tf.type == Types.APK) {
                        val m = Files.apkMeta(tf.path)
                        if (m != null) {
                            pkg = m.first
                            vc = m.second
                        }
                    }
                    var root = ""
                    for (s in r.sessions) {
                        val b = if (s.accepted && s.prepared) s.rootOf(row.start) else null
                        if (b != null) {
                            root = Chunks.hex(b)
                            break
                        }
                    }
                    try {
                        Db.addHistory(Hist(0, Db.SENT, tf.label, tf.path, size, tf.type, root, now, pkg, vc, names))
                    } catch (e: Exception) {
                    }
                }
            }
        }

        if (lost) Notifs.interrupted()
        if (failed > 0) Notifs.failed(failed)
        if (ok > 0 && !Xfer.foreground) Notifs.done(ok)
        if (!accepted && connFail) Ui.toast(FlitApp.ctx(), R.string.err_connection_failed)

        Xfer.sync()
        Xfer.hook?.sendFinished(r)
    }
}
