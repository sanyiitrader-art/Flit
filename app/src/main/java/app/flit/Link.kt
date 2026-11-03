package app.flit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.p2p.WifiP2pInfo
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One remote device with a live control connection. The control connection carries session
 * messages; any number of data connections (see [obtainData], [dial], [attach]) carry the bytes.
 */
class Peer(val control: Conn, val host: InetAddress, val weAreClient: Boolean, h: Hello) {

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

    @Volatile
    private var onClosed: (() -> Unit)? = null

    fun send(type: Int, w: Wr? = null): Boolean {
        return try {
            control.send(type, w)
            true
        } catch (e: IOException) {
            close()
            false
        }
    }

    /**
     * Starts the control reader. PING/PONG and AVATAR are handled here; every other frame goes to
     * [onFrame] (on the reader thread). [onClosed] runs once when the connection ends.
     */
    fun startReader(onFrame: (Frame) -> Unit, onClosed: () -> Unit) {
        this.onClosed = onClosed
        Thread({
            try {
                while (alive) {
                    val f = control.read()
                    when (f.type) {
                        Proto.PING -> send(Proto.PONG)
                        Proto.PONG -> {}
                        Proto.AVATAR -> gotAvatar(f)
                        else -> onFrame(f)
                    }
                }
            } catch (e: IOException) {
            } catch (e: RuntimeException) {
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
        alive = false
        control.close()
        for (s in streams) s.close()
        streams.clear()
        for (s in dataQueue) s.close()
        dataQueue.clear()
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
 * networks). Every connection starts with a HELLO; data connections name their peer by device id.
 *
 * The engines register [onSender] (a peer that wants to send to us arrived: Receiver engine) and
 * [onReceiver] (a peer that can receive from us arrived: Send engine).
 */
object Link {

    const val PORT = 47821
    private const val CONTROL_TIMEOUT = 15_000

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
    private var server: ServerSocket? = null
    private var bound: InetAddress? = null
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
        if (info.isGroupOwner) {
            startServer(info.groupOwnerAddress)
            return
        }
        val host = info.groupOwnerAddress ?: return
        if (use.get() <= 0) return
        if (peers.values.any { it.alive && it.host == host }) return
        if (!connecting.compareAndSet(false, true)) return
        Thread({
            try {
                connect(host)
            } finally {
                connecting.set(false)
            }
        }, "flit-dial").start()
    }

    fun onGroupLost() {
        for (p in peers.values) p.close()
        stopServer()
    }

    // ---- Server (Group Owner) ---------------------------------------------------------------

    @Synchronized
    fun startServer(addr: InetAddress?): Boolean {
        val cur = server
        if (cur != null && !cur.isClosed && (addr == null || addr == bound)) return true
        stopServer()
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
            true
        } catch (e: IOException) {
            false
        }
    }

    @Synchronized
    fun stopServer() {
        try {
            server?.close()
        } catch (e: IOException) {
        }
        server = null
        bound = null
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                val sock = s.accept()
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
            if (h.kind == Proto.KIND_DATA) {
                val p = peers[h.id]
                if (p == null || !p.alive) c.close() else p.attach(c)
                return
            }
            val p = Peer(c, sock.inetAddress, false, h)
            register(p) // before the reply, so the peer's data connections can find it
            c.send(Proto.HELLO, Proto.hello(myRole(), Proto.KIND_CONTROL))
            sock.soTimeout = CONTROL_TIMEOUT
            sendAvatar(p)
            dispatch(p)
        } catch (e: IOException) {
            try {
                sock.close()
            } catch (x: Exception) {
            }
        } catch (e: RuntimeException) {
            try {
                sock.close()
            } catch (x: Exception) {
            }
        }
    }

    // ---- Client ------------------------------------------------------------------------------

    fun socketTo(host: InetAddress): Socket {
        val s = Socket()
        try {
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
        var tries = 0
        while (tries < 15 && use.get() > 0) {
            try {
                val c = Conn(socketTo(host), 5000)
                c.send(Proto.HELLO, Proto.hello(myRole(), Proto.KIND_CONTROL))
                val h = Proto.parseHello(c.read())
                if (h.kind != Proto.KIND_CONTROL) {
                    c.close()
                    return
                }
                c.sock.soTimeout = CONTROL_TIMEOUT
                val p = Peer(c, host, true, h)
                register(p)
                sendAvatar(p)
                dispatch(p)
                return
            } catch (e: IOException) {
                tries++ // the Group Owner's server may not be up yet
                SystemClock.sleep(700)
            } catch (e: RuntimeException) {
                return
            }
        }
    }

    // ---- Peers -------------------------------------------------------------------------------

    private fun register(p: Peer) {
        peers.put(p.id, p)?.close()
        ensureBeat()
    }

    fun forget(p: Peer) {
        peers.remove(p.id, p)
    }

    private fun dispatch(p: Peer) {
        val h = if (p.role == Proto.ROLE_SENDER) onSender else onReceiver
        if (h == null) p.close() else h(p)
    }

    fun closeAll() {
        for (p in peers.values) p.close()
        stopServer()
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