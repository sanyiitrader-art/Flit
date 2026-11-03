package app.flit

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/** Message body builder (big-endian). */
class Wr(cap: Int = 64) {
    private val bos = ByteArrayOutputStream(cap)
    private val d = DataOutputStream(bos)

    fun u8(v: Int): Wr {
        d.writeByte(v)
        return this
    }

    fun i32(v: Int): Wr {
        d.writeInt(v)
        return this
    }

    fun i64(v: Long): Wr {
        d.writeLong(v)
        return this
    }

    fun str(s: String): Wr {
        var b = s.toByteArray(Charsets.UTF_8)
        if (b.size > 60000) b = b.copyOf(60000)
        d.writeShort(b.size)
        d.write(b)
        return this
    }

    fun bytes(b: ByteArray): Wr {
        d.writeInt(b.size)
        d.write(b)
        return this
    }

    fun toBytes(): ByteArray {
        d.flush()
        return bos.toByteArray()
    }
}

/** Message body reader. Throws IOException on truncated or malformed data. */
class Rd(private val b: ByteArray) {
    private var p = 0

    private fun need(n: Int) {
        if (n < 0 || p + n > b.size) throw IOException("short frame")
    }

    fun u8(): Int {
        need(1)
        return b[p++].toInt() and 0xFF
    }

    fun i32(): Int {
        need(4)
        val v = ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
            ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
        p += 4
        return v
    }

    fun i64(): Long {
        val hi = i32().toLong() and 0xFFFFFFFFL
        val lo = i32().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    fun str(): String {
        need(2)
        val n = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
        p += 2
        need(n)
        val s = String(b, p, n, Charsets.UTF_8)
        p += n
        return s
    }

    fun bytes(): ByteArray {
        val n = i32()
        need(n)
        val r = b.copyOfRange(p, p + n)
        p += n
        return r
    }
}

class Frame(val type: Int, val body: ByteArray) {
    fun rd(): Rd = Rd(body)
}

class Hello(val role: Int, val kind: Int, val id: String, val name: String, val rev: Int)

class Req(val session: String, val count: Int, val total: Long, val chunk: Int)

/** One file announced in the manifest. */
class Man(val fid: Int, val rel: String, val size: Long, val mtime: Long, val type: Int)

/**
 * A TCP connection with framed messages. Control sends are serialised by a lock. A data
 * connection has one writer thread and one reader thread, so its raw read/write access
 * ([din], [dout], [beginFrame]) needs no lock.
 */
class Conn(val sock: Socket, readTimeoutMs: Int) {

    val din = DataInputStream(BufferedInputStream(sock.getInputStream(), 64 * 1024))
    val dout = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 64 * 1024))
    private val wlock = Any()

    @Volatile
    var closed = false
        private set

    init {
        sock.tcpNoDelay = true
        sock.keepAlive = true
        sock.soTimeout = readTimeoutMs
    }

    fun send(type: Int, w: Wr? = null) {
        val body = w?.toBytes()
        synchronized(wlock) {
            dout.writeByte(type)
            dout.writeInt(body?.size ?: 0)
            if (body != null) dout.write(body)
            dout.flush()
        }
    }
    /** Writes an already-built frame (used by the per-peer writer thread). */
    fun sendRaw(type: Int, body: ByteArray?) {
        synchronized(wlock) {
            dout.writeByte(type)
            dout.writeInt(body?.size ?: 0)
            if (body != null) dout.write(body)
            dout.flush()
        }
    }

    fun read(): Frame {
        val t = din.readUnsignedByte()
        val n = din.readInt()
        if (n < 0 || n > Proto.MAX_CONTROL) throw IOException("bad frame length")
        val b = ByteArray(n)
        din.readFully(b)
        return Frame(t, b)
    }

    /** Raw frame header for the data path (CHUNK). The caller writes the body and flushes. */
    fun beginFrame(type: Int, len: Int) {
        dout.writeByte(type)
        dout.writeInt(len)
    }

    fun close() {
        closed = true
        try {
            sock.close()
        } catch (e: Exception) {
        }
    }
}

/** Wire format constants and message builders / parsers. */
object Proto {

    const val VERSION = 1
    const val MAX_CONTROL = 4 shl 20
    const val HASH = 32

    // Control connection
    const val HELLO = 1
    const val REQUEST = 2
    const val ACCEPT = 3
    const val DECLINE = 4
    const val CANCEL = 5
    const val MANIFEST = 6
    const val MANIFEST_END = 7
    const val PAUSE = 8
    const val RESUME = 9
    const val STOP = 10
    const val STREAM_WANT = 11
    const val PING = 12
    const val PONG = 13
    const val FILE_DONE = 15
    const val AVATAR = 16
    const val FINISHED = 17

    // Data connection
    const val GET = 20
    const val CHUNK = 21
    const val ERR = 22
    const val HASHES = 23
    const val HASHES_R = 24

    const val ROLE_RECEIVER = 0
    const val ROLE_SENDER = 1
    const val KIND_CONTROL = 0
    const val KIND_DATA = 1

    const val FILE_OK = 0
    const val FILE_FAILED = 1
    const val FILE_EXISTS = 2

    const val ERR_READ = 1
    const val ERR_CHANGED = 2
    const val ERR_RANGE = 3

    // ---- HELLO -----------------------------------------------------------------------

    fun hello(role: Int, kind: Int): Wr =
        Wr().u8(VERSION).u8(role).u8(kind).str(Prefs.deviceId).str(Prefs.name).i32(Prefs.avatarRev)

    fun parseHello(f: Frame): Hello {
        if (f.type != HELLO) throw IOException("expected HELLO")
        val r = f.rd()
        if (r.u8() != VERSION) throw IOException("protocol version")
        val role = r.u8()
        val kind = r.u8()
        val id = r.str()
        val name = r.str()
        val rev = r.i32()
        if (id.isEmpty()) throw IOException("empty id")
        return Hello(role, kind, id, name, rev)
    }

    // ---- Session messages ---------------------------------------------------------------

    fun request(session: String, count: Int, total: Long, chunk: Int): Wr =
        Wr().str(session).i32(count).i64(total).i32(chunk)

    fun parseRequest(f: Frame): Req {
        val r = f.rd()
        return Req(r.str(), r.i32(), r.i64(), r.i32())
    }

    /** ACCEPT / DECLINE / CANCEL / STOP / PAUSE / RESUME / FINISHED all carry just the session id. */
    fun sid(session: String): Wr = Wr().str(session)

    fun parseSid(f: Frame): String = f.rd().str()

    /** Entries [from, to) of [list]. */
    fun manifest(session: String, list: List<Man>, from: Int, to: Int): Wr {
        val w = Wr(64 + (to - from) * 96)
        w.str(session).i32(to - from)
        for (i in from until to) {
            val m = list[i]
            w.i32(m.fid).str(m.rel).i64(m.size).i64(m.mtime).u8(m.type)
        }
        return w
    }

    /** Returns the session id and the entries of one MANIFEST frame. */
    fun parseManifest(f: Frame): Pair<String, List<Man>> {
        val r = f.rd()
        val session = r.str()
        val n = r.i32()
        if (n < 0 || n > 100_000) throw IOException("bad manifest")
        val out = ArrayList<Man>(n)
        for (i in 0 until n) out.add(Man(r.i32(), r.str(), r.i64(), r.i64(), r.u8()))
        return Pair(session, out)
    }

    fun fileDone(fid: Int, status: Int, root: ByteArray): Wr = Wr().i32(fid).u8(status).bytes(root)

    // ---- Data messages ------------------------------------------------------------------

    fun get(fid: Int, idx: Int): Wr = Wr(8).i32(fid).i32(idx)

    fun hashesReq(fid: Int, first: Int, count: Int): Wr = Wr(12).i32(fid).i32(first).i32(count)

    fun err(fid: Int, idx: Int, code: Int): Wr = Wr(12).i32(fid).i32(idx).i32(code)
}