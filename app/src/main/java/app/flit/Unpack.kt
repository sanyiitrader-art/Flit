package app.flit

import android.os.StatFs
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** One archive extraction: progress is written by the worker thread and read by the UI (all volatile). */
class ExtractJob(val src: File, val dest: File) {

    @Volatile
    var file = ""

    @Volatile
    var done = 0L

    @Volatile
    var total = 0L

    @Volatile
    var entries = 0

    @Volatile
    var entriesDone = 0

    @Volatile
    var result = R_RUNNING

    val cancel = AtomicBoolean(false)

    /** Paths of extracted files (capped), handed to the media scanner afterwards. */
    val written = ArrayList<String>()

    /** Real progress: bytes written of the bytes to write, or entries when sizes are unknown. */
    fun fraction(): Float {
        val t = total
        if (t > 0L) return (done.toDouble() / t).toFloat().coerceIn(0f, 1f)
        val n = entries
        return if (n > 0) (entriesDone.toFloat() / n).coerceIn(0f, 1f) else 0f
    }

    companion object {
        const val R_RUNNING = 0
        const val R_OK = 1
        const val R_CANCELLED = 2
        const val R_NOSPACE = 3

        /** Not something Flit can extract (RAR5, encrypted, unknown method): offer other apps. */
        const val R_UNSUPPORTED = 4
        const val R_FAILED = 5
    }
}

private class Cancelled : RuntimeException()
private class NoSpace : IOException()
private class Unsupported : RuntimeException()

/**
 * Archive extraction engines: ZIP through java.util.zip, 7z through Apache commons-compress and
 * RAR (RAR4; RAR5 is not supported by the library) through junrar. Extraction only.
 *
 * Safety: every entry path is checked against the destination folder (no "..", no absolute paths,
 * canonical-path check), so a hostile archive cannot write outside it (Zip Slip). The destination is a
 * new folder named after the archive, so nothing existing is overwritten; a repeated name inside the
 * archive gets "(1)". Free space is checked first, and a cancelled or failed extraction removes what
 * it had written.
 */
object Unpack {

    private const val BUF = 128 * 1024
    private const val KEEP = 1000

    /** Runs the extraction on the calling thread and stores the outcome in [job]. */
    fun run(job: ExtractJob) {
        var r: Int
        try {
            if (!job.dest.mkdirs() && !job.dest.isDirectory) throw IOException("cannot create folder")
            when (Types.ext(job.src.name)) {
                "zip" -> zip(job)
                "7z" -> sevenZ(job)
                "rar" -> rar(job)
                else -> throw Unsupported()
            }
            r = ExtractJob.R_OK
        } catch (e: Cancelled) {
            r = ExtractJob.R_CANCELLED
        } catch (e: NoSpace) {
            r = ExtractJob.R_NOSPACE
        } catch (e: Unsupported) {
            r = ExtractJob.R_UNSUPPORTED
        } catch (e: OutOfMemoryError) {
            r = ExtractJob.R_FAILED
        } catch (e: Throwable) {
            r = when {
                job.cancel.get() -> ExtractJob.R_CANCELLED
                noSpace(e) -> ExtractJob.R_NOSPACE
                else -> ExtractJob.R_FAILED
            }
        }
        if (r != ExtractJob.R_OK) deleteTree(job.dest)
        job.result = r
    }

    // ---- Engines -------------------------------------------------------------------------------

    private fun zip(job: ExtractJob) {
        try {
            ZipFile(job.src).use { zf ->
                val list = java.util.Collections.list(zf.entries())
                var sum = 0L
                for (e in list) if (!e.isDirectory) sum += if (e.size >= 0L) e.size else (e.compressedSize.coerceAtLeast(0L) * 2L)
                begin(job, sum, list.size)
                for (e in list) {
                    stopIfCancelled(job)
                    val out = target(job, e.name)
                    if (out == null) {
                        job.entriesDone++
                        continue
                    }
                    if (e.isDirectory) {
                        out.mkdirs()
                    } else {
                        job.file = File(e.name.replace('\\', '/')).name
                        zf.getInputStream(e).use { copy(job, it, out) }
                    }
                    job.entriesDone++
                }
            }
        } catch (e: ZipException) {
            val m = (e.message ?: "").lowercase()
            if (m.contains("method") || m.contains("encrypt") || m.contains("password")) throw Unsupported()
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun sevenZ(job: ExtractJob) {
        try {
            SevenZFile(job.src).use { z ->
                var sum = 0L
                var n = 0
                for (en in z.entries) {
                    n++
                    if (!en.isDirectory && en.size > 0L) sum += en.size
                }
                begin(job, sum, n)
                val buf = ByteArray(BUF)
                var e = z.nextEntry
                while (e != null) {
                    val ent = e
                    stopIfCancelled(job)
                    val out = if (ent.isAntiItem) null else target(job, ent.name)
                    if (out != null) {
                        if (ent.isDirectory) {
                            out.mkdirs()
                        } else {
                            job.file = File(ent.name.replace('\\', '/')).name
                            out.parentFile?.mkdirs()
                            val dst = unique(out)
                            FileOutputStream(dst).use { os ->
                                if (ent.hasStream()) {
                                    while (true) {
                                        stopIfCancelled(job)
                                        val r = z.read(buf)
                                        if (r < 0) break
                                        os.write(buf, 0, r)
                                        job.done += r
                                    }
                                }
                            }
                            remember(job, dst)
                        }
                    }
                    job.entriesDone++
                    e = z.nextEntry
                }
            }
        } catch (e: IOException) {
            val n = e.javaClass.simpleName
            if (n.contains("Password") || n.contains("Unsupported")) throw Unsupported()
            throw e
        }
    }

    private fun rar(job: ExtractJob) {
        try {
            Archive(job.src).use { a ->
                var sum = 0L
                var n = 0
                for (fh in a.fileHeaders) {
                    n++
                    if (!fh.isDirectory) sum += fh.fullUnpackSize
                }
                begin(job, sum, n)
                var h = a.nextFileHeader()
                while (h != null) {
                    val hd = h
                    stopIfCancelled(job)
                    val name = hd.fileName
                    val out = target(job, name)
                    if (out != null) {
                        if (hd.isDirectory) {
                            out.mkdirs()
                        } else {
                            job.file = File(name.replace('\\', '/')).name
                            out.parentFile?.mkdirs()
                            val dst = unique(out)
                            FileOutputStream(dst).use { os -> a.extractFile(hd, Counting(os, job)) }
                            remember(job, dst)
                        }
                    }
                    job.entriesDone++
                    h = a.nextFileHeader()
                }
            }
        } catch (e: Cancelled) {
            throw e
        } catch (e: NoSpace) {
            throw e
        } catch (e: IOException) {
            if (job.cancel.get()) throw Cancelled()
            throw e
        } catch (e: Exception) {
            // The RAR library reports RAR5, encrypted and unsupported archives with its own exceptions.
            if (job.cancel.get()) throw Cancelled()
            throw Unsupported()
        }
    }

    // ---- Helpers ---------------------------------------------------------------------------------

    private fun begin(job: ExtractJob, total: Long, entries: Int) {
        job.total = total
        job.entries = entries
        val free = try {
            StatFs((job.dest.parentFile ?: job.dest).path).availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE
        }
        if (total > 0L && free < total + (4L shl 20)) throw NoSpace()
    }

    private fun stopIfCancelled(job: ExtractJob) {
        if (job.cancel.get()) throw Cancelled()
    }

    /**
     * Where entry [name] goes, or null to skip it. Rejects ".." parts and anything that would land
     * outside the destination folder (Zip Slip).
     */
    private fun target(job: ExtractJob, name: String): File? {
        val n = name.replace('\\', '/').trimStart('/')
        if (n.isEmpty()) return null
        if (n.split('/').any { it == ".." }) return null
        val f = File(job.dest, n)
        val base = job.dest.canonicalPath
        val c = f.canonicalPath
        if (c != base && !c.startsWith(base + File.separator)) return null
        return f
    }

    /** "name.ext" that already exists (the same name twice in one archive) becomes "name (1).ext". */
    private fun unique(f: File): File {
        if (!f.exists()) return f
        val dot = f.name.lastIndexOf('.')
        val base = if (dot > 0) f.name.substring(0, dot) else f.name
        val ext = if (dot > 0) f.name.substring(dot) else ""
        var i = 1
        var g = File(f.parentFile, "$base ($i)$ext")
        while (g.exists()) {
            i++
            g = File(f.parentFile, "$base ($i)$ext")
        }
        return g
    }

    private fun remember(job: ExtractJob, f: File) {
        if (job.written.size < KEEP) job.written.add(f.path)
    }

    private fun copy(job: ExtractJob, ins: InputStream, out: File) {
        out.parentFile?.mkdirs()
        val dst = unique(out)
        val buf = ByteArray(BUF)
        FileOutputStream(dst).use { os ->
            while (true) {
                stopIfCancelled(job)
                val r = ins.read(buf)
                if (r < 0) break
                os.write(buf, 0, r)
                job.done += r
            }
        }
        remember(job, dst)
    }

    /** OutputStream that counts bytes for the progress and stops when the user cancels. */
    private class Counting(private val out: OutputStream, private val job: ExtractJob) : OutputStream() {
        override fun write(b: Int) {
            if (job.cancel.get()) throw IOException("cancelled")
            out.write(b)
            job.done += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (job.cancel.get()) throw IOException("cancelled")
            out.write(b, off, len)
            job.done += len
        }
    }

    private fun noSpace(e: Throwable): Boolean {
        var t: Throwable? = e
        var i = 0
        while (t != null && i < 6) {
            val m = t.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", true)) return true
            t = t.cause
            i++
        }
        return false
    }

    fun deleteTree(f: File) {
        try {
            if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        } catch (e: Exception) {
        }
    }
}
