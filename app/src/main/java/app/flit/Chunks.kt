package app.flit

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.BitSet
import java.util.concurrent.atomic.AtomicLong

/** The destination ran out of space (also raised for quota errors). */
class NoSpaceException : IOException("No space left on device")

/** Chunk arithmetic, hashing, positional I/O and the receive-folder policy. */
object Chunks {

    fun count(size: Long, chunk: Int): Int =
        if (size <= 0L) 0 else ((size + chunk - 1) / chunk).toInt()

    fun chunkLen(size: Long, chunk: Int, idx: Int): Int =
        minOf(chunk.toLong(), size - idx.toLong() * chunk).toInt()

    fun sha(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** SHA-256 over the ordered chunk hashes: the identity of the whole file. */
    fun root(hashes: ByteArray, n: Int): ByteArray {
        val md = sha()
        md.update(hashes, 0, n * Proto.HASH)
        return md.digest()
    }

    private const val HEX = "0123456789abcdef"

    fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            sb.append(HEX[(x.toInt() shr 4) and 15])
            sb.append(HEX[x.toInt() and 15])
        }
        return sb.toString()
    }

    /** Resume key of one file of one sender. Also names the temporary file. */
    fun key(peerId: String, rel: String, size: Long, mtime: Long): String =
        hex(sha().digest("$peerId|$rel|$size|$mtime".toByteArray(Charsets.UTF_8)).copyOf(12))

    // ---- Positional I/O (thread-safe: no shared file pointer) -----------------------------

    /** Reads up to [len] bytes at [pos]; returns the number read (less than [len] only at end of file). */
    fun pread(fd: FileDescriptor, buf: ByteArray, len: Int, pos: Long): Int {
        var got = 0
        try {
            while (got < len) {
                val n = Os.pread(fd, buf, got, len - got, pos + got)
                if (n <= 0) break
                got += n
            }
        } catch (e: ErrnoException) {
            throw IOException(e.message, e)
        }
        return got
    }

    fun pwrite(fd: FileDescriptor, buf: ByteArray, len: Int, pos: Long) {
        var done = 0
        try {
            while (done < len) {
                val n = Os.pwrite(fd, buf, done, len - done, pos + done)
                if (n <= 0) throw IOException("write failed")
                done += n
            }
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOSPC || e.errno == OsConstants.EDQUOT) throw NoSpaceException()
            throw IOException(e.message, e)
        }
    }

    /** Reserves [size] bytes up front where the filesystem supports it, so a full disk shows at file start. */
    fun allocate(raf: RandomAccessFile, size: Long) {
        if (size <= 0L) return
        var done = false
        if (Tuning.fallocate) {
            try {
                Os.posix_fallocate(raf.fd, 0L, size)
                done = true
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.ENOSPC || e.errno == OsConstants.EDQUOT) throw NoSpaceException()
            }
        }
        if (!done && raf.length() != size) raf.setLength(size)
    }

    /** Hashes chunks [first, first+count) of [src]. Used for resume spot-checks and duplicate detection. */
    fun hashChunks(src: Src, size: Long, chunk: Int, first: Int, count: Int, buf: ByteArray): ByteArray {
        val last = minOf(count(size, chunk), first + count)
        val out = ByteArray(maxOf(0, last - first) * Proto.HASH)
        val md = sha()
        for (i in first until last) {
            var left = chunkLen(size, chunk, i)
            var pos = i.toLong() * chunk
            md.reset()
            while (left > 0) {
                val got = src.read(buf, minOf(left, buf.size), pos)
                if (got <= 0) throw IOException("short read")
                md.update(buf, 0, got)
                pos += got
                left -= got
            }
            md.digest(out, (i - first) * Proto.HASH, Proto.HASH)
        }
        return out
    }

    // ---- Receive folder --------------------------------------------------------------------

    fun recvRoot(): File = File(Files.root, "Download/Flit")

    /** Hidden folder for unfinished files (with .nomedia so galleries ignore it). */
    fun partialDir(): File {
        val d = File(recvRoot(), ".partial")
        d.mkdirs()
        try {
            File(d, ".nomedia").createNewFile()
        } catch (e: IOException) {
        }
        return d
    }

    /** Makes a path received from a peer safe: no "..", no absolute paths, no illegal characters. */
    fun safeRel(rel: String): String {
        val parts = ArrayList<String>()
        for (raw in rel.split('/', '\\')) {
            var s = raw.trim().replace(Regex("[\\u0000-\\u001F:*?\"<>|]"), "_")
            if (s.isEmpty() || s == "." || s == "..") continue
            if (s.length > 180) s = s.take(180)
            parts.add(s)
        }
        return if (parts.isEmpty()) "file" else parts.joinToString("/")
    }

    private val reserved = HashSet<String>()

    private fun taken(f: File): Boolean = f.exists() || reserved.contains(f.path)

    /**
     * Picks a destination under Download/Flit that does not exist and is not reserved by another
     * file of the running transfers: "photo.jpg", then "photo (1).jpg", "photo (2).jpg" ...
     */
    @Synchronized
    fun reserve(rel: String): File {
        val first = File(recvRoot(), safeRel(rel))
        if (!taken(first)) {
            reserved.add(first.path)
            return first
        }
        val dir = first.parentFile ?: recvRoot()
        val name = first.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (true) {
            val c = File(dir, "$base ($i)$ext")
            if (!taken(c)) {
                reserved.add(c.path)
                return c
            }
            i++
        }
    }

    @Synchronized
    fun unreserve(f: File) {
        reserved.remove(f.path)
    }
}

/** Sender-side read handle of one source file. Shared by all data streams (positional reads). */
class Src(val path: String) {

    private var raf: RandomAccessFile? = null
    private var fd: FileDescriptor? = null

    var size = 0L
        private set
    var mtime = 0L
        private set

    @Synchronized
    @Throws(IOException::class)
    fun open() {
        if (raf != null) return
        val f = File(path)
        val r = RandomAccessFile(f, "r")
        raf = r
        fd = r.fd
        size = r.length()
        mtime = f.lastModified()
    }

    fun read(buf: ByteArray, len: Int, pos: Long): Int {
        val f = fd ?: throw IOException("closed")
        return Chunks.pread(f, buf, len, pos)
    }

    @Synchronized
    fun close() {
        try {
            raf?.close()
        } catch (e: IOException) {
        }
        raf = null
        fd = null
    }
}

/**
 * Receiver-side state of one file: per-chunk state machine, the temporary file, verified chunk
 * hashes and the journal snapshot. A chunk is owned by exactly one stream at a time
 * (PENDING -> IN_FLIGHT -> COMMITTED), so two writers can never touch the same byte range.
 * Writes are positional into a pre-sized temporary file; the file is renamed to its final name
 * only after every chunk is committed.
 */
class RecvFile(
    val fid: Int,
    val rel: String,
    val size: Long,
    val mtime: Long,
    val type: Int,
    val chunkSize: Int,
    val tmp: File,
    var dest: File
) {
    companion object {
        const val WAITING = 0
        const val ACTIVE = 1
        const val DONE = 2
        const val FAILED = 3
        const val EXISTS = 4
        const val STOPPED = 5

        const val ERR_NONE = 0
        const val ERR_SPACE = 1
        const val ERR_IO = 2
        const val ERR_HASH = 3
        const val ERR_SOURCE = 4
    }

    val n: Int = Chunks.count(size, chunkSize)
    val hashes = ByteArray(n * Proto.HASH)

    @Volatile
    var state = WAITING

    @Volatile
    var err = ERR_NONE

    /** Bytes written so far, including chunks still being verified. Drives the progress display. */
    val received = AtomicLong(0L)

    var root: ByteArray = ByteArray(0)
        private set

    private val committed = BitSet(n)
    private val inflight = BitSet(n)
    private val attempts = IntArray(n)
    private var raf: RandomAccessFile? = null
    private var fd: FileDescriptor? = null
    private var committedBytes = 0L

    /**
     * Opens (or reopens) the temporary file and reserves its space. With a matching journal entry
     * [resume] the already committed chunks are kept. Throws NoSpaceException when the disk is full.
     */
    @Synchronized
    @Throws(IOException::class)
    fun open(resume: Part?) {
        tmp.parentFile?.mkdirs()
        val f = RandomAccessFile(tmp, "rw")
        try {
            var reuse = false
            if (resume != null && resume.size == size && resume.mtime == mtime &&
                resume.chunkSize == chunkSize && resume.hashes.size == hashes.size && f.length() == size
            ) {
                val bs = BitSet.valueOf(resume.bitmap)
                committedBytes = 0L
                for (i in 0 until n) {
                    if (bs.get(i)) {
                        committed.set(i)
                        committedBytes += Chunks.chunkLen(size, chunkSize, i)
                    }
                }
                System.arraycopy(resume.hashes, 0, hashes, 0, hashes.size)
                reuse = true
            }
            if (!reuse) {
                f.setLength(0L)
                Chunks.allocate(f, size)
            }
            received.set(committedBytes)
            raf = f
            fd = f.fd
            state = ACTIVE
        } catch (e: IOException) {
            try {
                f.close()
            } catch (x: IOException) {
            }
            throw e
        }
    }

    /** Claims the next chunk that needs fetching, or -1 if none is available right now. */
    @Synchronized
    fun next(): Int {
        var i = committed.nextClearBit(0)
        while (i < n) {
            if (!inflight.get(i) && attempts[i] < Tuning.ATTEMPTS) {
                inflight.set(i)
                return i
            }
            i = committed.nextClearBit(i + 1)
        }
        return -1
    }

    /** True if some chunk has used all its attempts without being committed: the file cannot complete. */
    @Synchronized
    fun exhausted(): Boolean {
        var i = committed.nextClearBit(0)
        while (i < n) {
            if (!inflight.get(i) && attempts[i] >= Tuning.ATTEMPTS) return true
            i = committed.nextClearBit(i + 1)
        }
        return false
    }

    /** Writes one block of chunk [idx] at offset [off] inside the chunk. */
    @Throws(IOException::class)
    fun write(idx: Int, off: Int, buf: ByteArray, len: Int) {
        val f = fd ?: throw IOException("closed")
        Chunks.pwrite(f, buf, len, idx.toLong() * chunkSize + off)
        received.addAndGet(len.toLong())
    }

    /** The chunk arrived completely and its hash matched. */
    @Synchronized
    fun commit(idx: Int, hash: ByteArray) {
        System.arraycopy(hash, 0, hashes, idx * Proto.HASH, Proto.HASH)
        committed.set(idx)
        inflight.clear(idx)
        committedBytes += Chunks.chunkLen(size, chunkSize, idx)
    }

    /** The attempt for [idx] failed: give the chunk back. [written] is subtracted from the progress. */
    @Synchronized
    fun release(idx: Int, written: Long, penalize: Boolean) {
        inflight.clear(idx)
        if (penalize) attempts[idx]++
        received.addAndGet(-written)
    }

    @Synchronized
    fun complete(): Boolean = committed.cardinality() == n

    @Synchronized
    fun committedBytes(): Long = committedBytes

    /**
     * Verifies that every chunk is committed, flushes, renames the temporary file to [dest],
     * restores the sender's modification time and computes the root hash. Returns false if the
     * file is not complete.
     */
    @Throws(IOException::class)
    fun finish(): Boolean {
        synchronized(this) {
            if (committed.cardinality() != n) return false
            val f = raf ?: throw IOException("closed")
            try {
                f.fd.sync()
            } catch (e: IOException) {
            }
            f.close()
            raf = null
            fd = null
            root = Chunks.root(hashes, n)
        }
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) throw IOException("rename failed")
        dest.setLastModified(mtime)
        state = DONE
        return true
    }

    /** Journal snapshot (copied, so it can be written on the database thread). */
    @Synchronized
    fun snapshot(session: String, key: String): Part =
        Part(
            key, session, rel, tmp.path, dest.path, size, mtime, chunkSize,
            committed.toByteArray(), hashes.copyOf(), System.currentTimeMillis()
        )

    /** Closes the file handle but keeps the temporary file (for a later resume). */
    @Synchronized
    fun close() {
        try {
            raf?.close()
        } catch (e: IOException) {
        }
        raf = null
        fd = null
    }

    /** Closes and deletes the temporary file. */
    fun discard() {
        close()
        tmp.delete()
    }
}