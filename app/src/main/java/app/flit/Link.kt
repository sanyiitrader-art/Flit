package app.flit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.p2p.WifiP2pInfo
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "FlitLink"

private fun frameName(t: Int): String = when (t) {
    Proto.HELLO -> "HELLO"
    Proto.REQUEST -> "REQUEST"
    Proto.ACCEPT -> "ACCEPT"
    Proto.DECLINE -> "DECLINE"
    Proto.CANCEL -> "CANCEL"
    Proto.MANIFEST -> "MANIFEST"
    Proto.MANIFEST_END -> "MANIFEST_END"
    Proto.PAUSE -> "PAUSE"
    Proto.RESUME -> "RESUME"
    Proto.STOP -> "STOP"
    Proto.STREAM_WANT -> "STREAM_WANT"
    Proto.FILE_DONE -> "FILE_DONE"
    Proto.AVATAR -> "AVATAR"
    Proto.FINISHED -> "FINISHED"
    else -> "type$t"
}

/**
 * One remote device with a live control connection. The control connection carries session
 * messages; any number of data connections (see [obtainData], [dial], [attach]) carry the bytes.
 *
 * Control messages are written by ONE dedicated thread per peer (in order). [send] only queues the
 * message when called on the main thread, because Android forbids network writes there; from a
 * background thread it writes directly (after anything already queued).
 */
class Peer(val control: Conn, val host: InetAddress, val weAreClient: Boolean, h: Hello) {

    private class Out(val type: Int, val body: ByteArray?)

    val id: String = h.id
    val name: String = h.name
    val role: Int = h.role
    val rev: Int = h.rev

    @Volatile
    var avatar: Bitmap? = null

    /** Called (main thread) when the peer's avatar arrived. */
    @Volatile
    var onAvatar: (() -> Unit)? = null

    /** Serving side: called on its own thread for every data connection that arrives. */
    @Volatile
    var dataHandler: ((Conn) -> Unit)? = null

    @Volatile
    var alive = true
        private set

    private val closed = AtomicBoolean(false)
    private val streams = java.util.concurrent.CopyOnWriteArrayList<Conn>()
    private val dataQueue = LinkedBlockingQueue<Conn>()
    private val outbox = LinkedBlockingQueue<Out>()
    private val writerStarted = AtomicBoolean(false)

    @Volatile
    private var onClosed: (() -> Unit)? = null

    private fun startWriter() {
        if (!writerStarted.compareAndSet(false, true)) return
        Thread({
            try {
                while (alive || outbox.isNotEmpty()) {
                    val m = outbox.poll(500, TimeUnit.MILLISECONDS)
                    if (m == null) {
                        if (!alive) break
                        continue
                    }
                    if (!write(m)) break
                }
            } catch (e: InterruptedException) {
            }
        }, "flit-ctl-out").also {
            it.isDaemon = true
            it.start()
        }
    }

    /** Writes one message to the socket. Returns false (and closes the peer) on failure. */
    private fun write(m: Out): Boolean {
        return try {
            control.sendRaw(m.type, m.body)
            if (m.type != Proto.PING && m.type != Proto.PONG) Log.d(TAG, "ctl >> ${frameName(m.type)} to $name")
            true
        } catch (e: IOException) {
            Log.w(TAG, "ctl >> ${frameName(m.type)} to $name failed: ${e.javaClass.simpleName}: ${e.message}")
            close()
            false
        }
    }

    /**
     * Queues a control message. Never touches the network on the calling thread, so it is safe to
     * call from the UI. Returns false only if the peer is already closed.
     */
    fun send(type: Int, w: Wr? = null): Boolean {
        if (!alive) return false
        startWriter()
        outbox.offer(Out(type, w?.toBytes()))
        return true
    }

    /**
     * Starts the control reader. PING/PONG and AVATAR are handled here; every other frame goes to
     * [onFrame] (on the reader thread). [onClosed] runs once when the connection ends.
     */
    fun startReader(onFrame: (Frame) -> Unit, onClosed: () -> Unit) {
        this.onClosed = onClosed
        startWriter()
        Thread({
            try {
                while (alive) {
                    val f = control.read()
                    if (f.type != Proto.PING && f.type != Proto.PONG) {
                        Log.d(TAG, "ctl << ${frameName(f.type)} from $name")
                    }
                    when (f.type) {
                        Proto.PING -> send(Proto.PONG)
                        Proto.PONG -> {}
                        Proto.AVATAR -> gotAvatar(f)
                        else -> onFrame(f)
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "ctl reader of $name ended: ${e.javaClass.simpleName}: ${e.message}")
            } catch (e: RuntimeException) {
                Log.w(TAG, "ctl reader of $name crashed: ${e.javaClass.simpleName}: ${e.message}")
            }
            close()
        }, "flit-ctl").start()
    }

    private fun gotAvatar(f: Frame) {
        try {
            val b = f.rd().bytes()
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size) ?: return
            avatar = bmp
            val cb = onAvatar
            if (cb != null) FlitApp.main.post { cb() }
        } catch (e: IOException) {
        }
    }

    /** Requesting side (the Receiver): returns a data connection, or null on failure/timeout. */
    fun obtainData(timeoutMs: Int): Conn? {
        if (!alive) return null
        if (weAreClient) return dial(DATA_TIMEOUT)
        send(Proto.STREAM_WANT, Wr().i32(1)) // we are the Group Owner: ask the peer to dial us
        val c = try {
            dataQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null
        }
        return c
    }

    /** Client side: opens a data connection to the Group Owner. [readTimeout] 0 = block (serving side). */
    fun dial(readTimeout: Int): Conn? {
        if (!alive) return null
        return try {
            val c = Conn(Link.socketTo(host), readTimeout)
            c.send(Proto.HELLO, Proto.hello(Link.myRole(), Proto.KIND_DATA))
            streams.add(c)
            if (!alive) {
                c.close()
                null
            } else {
                c
            }
        } catch (e: IOException) {
            Log.w(TAG, "data dial to $name failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Group Owner side: a data connection of this peer arrived. */
    fun attach(c: Conn) {
        if (!alive) {
            c.close()
            return
        }
        streams.add(c)
        val hd = dataHandler
        if (hd != null) {
            c.sock.soTimeout = 0
            Thread({
                try {
                    hd(c)
                } catch (e: Exception) {
                    c.close()
                }
            }, "flit-serve").start()
        } else {
            c.sock.soTimeout = DATA_TIMEOUT
            dataQueue.offer(c)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.d(TAG, "peer $name closed")
        alive = false
        // Give already queued messages (for example DECLINE, FINISHED) a moment to be written.
        val q = outbox.isNotEmpty()
        val shut = Runnable {
            control.close()
            for (s in streams) s.close()
            streams.clear()
            for (s in dataQueue) s.close()
            dataQueue.clear()
        }
        if (q && Looper.myLooper() == Looper.getMainLooper()) {
            Thread({
                SystemClock.sleep(400)
                shut.run()
            }, "flit-close").start()
        } else {
            shut.run()
        }
        Link.forget(this)
        onClosed?.invoke()
    }

    companion object {
        const val DATA_TIMEOUT = 20_000
    }
}

/**
 * TCP layer on top of the Wi-Fi Direct group. The P2P client always dials the Group Owner; the
 * Group Owner runs a server bound to its own group address (so it is unreachable from other
 * networks; if that bind keeps failing it falls back to all interfaces). Every connection starts
 * with a HELLO; data connections name their peer by device id. Diagnostics use the tag "FlitLink".
 *
 * The engines register [onSender] (a peer that wants to send to us arrived: Receiver engine) and
 * [onReceiver] (a peer that can receive from us arrived: Send engine).
 */
object Link {

    const val PORT = 47821
    private const val CONTROL_TIMEOUT = 15_000
    private const val DIAL_DEADLINE_MS = 25_000L

    @Volatile
    var onSender: ((Peer) -> Unit)? = null

    @Volatile
    var onReceiver: ((Peer) -> Unit)? = null

    /** True while a Send session is running: decides the role announced in our HELLO. */
    @Volatile
    var sending = false

    private val peers = ConcurrentHashMap<String, Peer>()
    private val use = AtomicInteger(0)
    private val connecting = AtomicBoolean(false)
    private val retrying = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private var bound: InetAddress? = null
    private var gen = 0
    private var beat: Thread? = null

    fun myRole(): Int = if (sending) Proto.ROLE_SENDER else Proto.ROLE_RECEIVER

    /** Engines call this while they need a link (Receiver mode on, Send session running). */
    fun use(on: Boolean) {
        if (on) {
            use.incrementAndGet()
        } else if (use.decrementAndGet() < 0) {
            use.set(0)
        }
    }

    fun hasPeers(): Boolean = peers.isNotEmpty()

    fun peer(id: String): Peer? = peers[id]

    // ---- Group events (from P2p, main thread) -----------------------------------------------

    fun onGroup(info: WifiP2pInfo) {
        Log.d(
            TAG,
            "onGroup: owner=${info.isGroupOwner} go=${info.groupOwnerAddress?.hostAddress} " +
                "needLink=${use.get() > 0} role=${if (sending) "sender" else "receiver"}"
        )
        logIfaces()
        if (info.isGroupOwner) {
            startServer(info.groupOwnerAddress)
            return
        }
        val host = info.groupOwnerAddress
        if (host == null) {
            Log.w(TAG, "onGroup: client without a Group Owner address yet, waiting for the next event")
            return
        }
        if (use.get() <= 0) {
            Log.d(TAG, "onGroup: ignored, nothing needs the link")
            return
        }
        if (peers.values.any { it.alive && it.host == host }) {
            Log.d(TAG, "onGroup: already connected to the Group Owner")
            return
        }
        if (!connecting.compareAndSet(false, true)) {
            Log.d(TAG, "onGroup: a dial is already running")
            return
        }
        Thread({
            try {
                connect(host)
            } finally {
                connecting.set(false)
            }
        }, "flit-dial").start()
    }

    fun onGroupLost() {
        Log.d(TAG, "onGroupLost: closing ${peers.size} peer(s) and the server")
        for (p in peers.values) p.close()
        stopServer()
    }

    // ---- Server (Group Owner) ---------------------------------------------------------------

    @Synchronized
    fun startServer(addr: InetAddress?): Boolean {
        val cur = server
        if (cur != null && !cur.isClosed && (addr == null || addr == bound)) return true
        stopServer()
        if (bindOnce(addr)) return true
        val g = gen
        if (retrying.compareAndSet(false, true)) {
            Thread({ retryBind(addr, g) }, "flit-bind").start()
        }
        return false
    }

    /** Must be called with the Link lock held. */
    private fun bindOnce(addr: InetAddress?): Boolean {
        return try {
            val s = ServerSocket()
            s.reuseAddress = true
            if (Tuning.sockBuf > 0) s.receiveBufferSize = Tuning.sockBuf
            s.bind(InetSocketAddress(addr, PORT), 16)
            server = s
            bound = addr
            Thread({ acceptLoop(s) }, "flit-accept").also {
                it.isDaemon = true
                it.start()
            }
            Log.d(TAG, "server listening on ${addr?.hostAddress ?: "all interfaces"}:$PORT")
            true
        } catch (e: IOException) {
            Log.w(TAG, "server bind failed on ${addr?.hostAddress}: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** The group interface may not carry its address yet: retry for ~6 s, then listen on all interfaces. */
    private fun retryBind(addr: InetAddress?, g: Int) {
        try {
            var i = 0
            while (i < 12) {
                SystemClock.sleep(500)
                synchronized(this) {
                    if (gen != g) return
                    if (server?.isClosed == false) return
                    if (bindOnce(addr)) return
                }
                i++
            }
            synchronized(this) {
                if (gen != g || server?.isClosed == false) return
                if (bindOnce(null)) Log.w(TAG, "server bound to all interfaces as a fallback")
            }
        } finally {
            retrying.set(false)
        }
    }

    @Synchronized
    fun stopServer() {
        gen++
        try {
            server?.close()
        } catch (e: IOException) {
        }
        if (server != null) Log.d(TAG, "server stopped")
        server = null
        bound = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val sock = s.accept()
                Log.d(TAG, "accepted TCP from ${sock.inetAddress?.hostAddress}")
                Thread({ handshake(sock) }, "flit-hs").start()
            } catch (e: IOException) {
                break
            }
        }
    }

    private fun handshake(sock: Socket) {
        try {
            val c = Conn(sock, 5000)
            val h = Proto.parseHello(c.read())
            Log.d(TAG, "handshake: HELLO from '${h.name}' kind=${if (h.kind == Proto.KIND_DATA) "data" else "control"} role=${h.role}")
            if (h.kind == Proto.KIND_DATA) {
                val p = peers[h.id]
                if (p == null || !p.alive) {
                    Log.w(TAG, "handshake: data connection from an unknown peer, closing")
                    c.close()
                } else {
                    p.attach(c)
                }
                return
            }
            val p = Peer(c, sock.inetAddress, false, h)
            register(p) // before the reply, so the peer's data connections can find it
            c.send(Proto.HELLO, Proto.hello(myRole(), Proto.KIND_CONTROL))
            sock.soTimeout = CONTROL_TIMEOUT
            sendAvatar(p)
            dispatch(p)
        } catch (e: IOException) {
            Log.w(TAG, "handshake failed: ${e.javaClass.simpleName}: ${e.message}")
            try {
                sock.close()
            } catch (x: Exception) {
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "handshake crashed: ${e.javaClass.simpleName}: ${e.message}")
            try {
                sock.close()
            } catch (x: Exception) {
            }
        }
    }

    // ---- Client ------------------------------------------------------------------------------

    /** The Wi-Fi Direct network, if Android exposes it (lets us pin sockets to it). */
    @Suppress("DEPRECATION")
    private fun p2pNetwork(): Network? {
        return try {
            val cm = FlitApp.app.getSystemService(ConnectivityManager::class.java) ?: return null
            for (n in cm.allNetworks) {
                val name = cm.getLinkProperties(n)?.interfaceName ?: continue
                if (name.contains("p2p")) return n
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun socketTo(host: InetAddress): Socket {
        val s = Socket()
        try {
            val net = p2pNetwork()
            if (net != null) {
                try {
                    net.bindSocket(s)
                } catch (e: IOException) {
                    Log.w(TAG, "could not pin the socket to the Wi-Fi Direct network: ${e.message}")
                }
            }
            val sb = Tuning.sockBuf
            if (sb > 0) {
                s.receiveBufferSize = sb
                s.sendBufferSize = sb
            }
            s.connect(InetSocketAddress(host, PORT), 4000)
        } catch (e: IOException) {
            try {
                s.close()
            } catch (x: Exception) {
            }
            throw e
        }
        return s
    }

    /** Blocking (call off the main thread): opens the control connection to the Group Owner. */
    private fun connect(host: InetAddress) {
        Log.d(TAG, "dial: ${host.hostAddress}:$PORT")
        val t0 = SystemClock.elapsedRealtime()
        var tries = 0
        while (SystemClock.elapsedRealtime() - t0 < DIAL_DEADLINE_MS && use.get() > 0) {
            try {
                val c = Conn(socketTo(host), 5000)
                c.send(Proto.HELLO, Proto.hello(myRole(), Proto.KIND_CONTROL))
                val h = Proto.parseHello(c.read())
                if (h.kind != Proto.KIND_CONTROL) {
                    Log.w(TAG, "dial: unexpected HELLO kind from the Group Owner")
                    c.close()
                    return
                }
                c.sock.soTimeout = CONTROL_TIMEOUT
                val p = Peer(c, host, true, h)
                Log.d(TAG, "dial: connected to '${h.name}' after ${tries + 1} attempt(s)")
                register(p)
                sendAvatar(p)
                dispatch(p)
                return
            } catch (e: IOException) {
                tries++ // the Group Owner's server may not be up yet
                Log.w(TAG, "dial attempt $tries failed: ${e.javaClass.simpleName}: ${e.message}")
                SystemClock.sleep(700)
            } catch (e: RuntimeException) {
                Log.w(TAG, "dial crashed: ${e.javaClass.simpleName}: ${e.message}")
                return
            }
        }
        Log.w(TAG, "dial: gave up after $tries attempt(s) (needLink=${use.get() > 0})")
    }

    // ---- Peers -------------------------------------------------------------------------------

    private fun register(p: Peer) {
        val old = peers.put(p.id, p)
        if (old != null) {
            Log.w(TAG, "register: replacing an existing control connection of '${p.name}'")
            old.close()
        }
        ensureBeat()
    }

    fun forget(p: Peer) {
        peers.remove(p.id, p)
    }

    private fun dispatch(p: Peer) {
        val h = if (p.role == Proto.ROLE_SENDER) onSender else onReceiver
        Log.d(TAG, "dispatch: '${p.name}' is a ${if (p.role == Proto.ROLE_SENDER) "sender" else "receiver"}, handler=${h != null}")
        if (h == null) p.close() else h(p)
    }

    fun closeAll() {
        for (p in peers.values) p.close()
        stopServer()
    }

    private fun logIfaces() {
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return
            for (ni in Collections.list(all)) {
                if (!ni.isUp) continue
                val n = ni.name
                if (n.contains("p2p") || n.startsWith("wlan")) {
                    val ips = Collections.list(ni.inetAddresses).joinToString { it.hostAddress ?: "?" }
                    Log.d(TAG, "iface $n: $ips")
                }
            }
        } catch (e: Exception) {
        }
    }

    /** One daemon thread pings every peer every 5 s while any control connection exists. */
    @Synchronized
    private fun ensureBeat() {
        if (beat?.isAlive == true) return
        beat = Thread({
            while (peers.isNotEmpty()) {
                SystemClock.sleep(5000)
                for (p in peers.values) p.send(Proto.PING)
            }
        }, "flit-beat").also {
            it.isDaemon = true
            it.start()
        }
    }

    // ---- Avatar ------------------------------------------------------------------------------

    private var avBytes: ByteArray? = null
    private var avRev = -1

    @Synchronized
    private fun avatarBytes(): ByteArray? {
        if (!Prefs.hasAvatar()) return null
        if (avBytes != null && avRev == Prefs.avatarRev) return avBytes
        return try {
            val src = BitmapFactory.decodeFile(Prefs.avatarFile().path) ?: return null
            val small = Bitmap.createScaledBitmap(src, 96, 96, true)
            val bos = ByteArrayOutputStream(8 * 1024)
            small.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            avBytes = bos.toByteArray()
            avRev = Prefs.avatarRev
            avBytes
        } catch (e: Exception) {
            null
        }
    }

    private fun sendAvatar(p: Peer) {
        val b = avatarBytes() ?: return
        p.send(Proto.AVATAR, Wr(b.size + 8).bytes(b))
    }
}