package app.flit

import android.webkit.MimeTypeMap
import java.util.Locale

/** File-type classification, MIME lookup and size/duration formatting. */
object Types {

    const val IMAGE = 0
    const val VIDEO = 1
    const val AUDIO = 2
    const val PDF = 3
    const val DOC = 4
    const val XLS = 5
    const val PPT = 6
    const val APK = 7
    const val ARCHIVE = 8
    const val TEXT = 9
    const val CODE = 10
    const val DATA = 11
    const val OTHER = 12

    private val map = HashMap<String, Int>(128)

    private fun reg(type: Int, vararg exts: String) {
        for (e in exts) map[e] = type
    }

    init {
        reg(IMAGE, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
        reg(VIDEO, "mp4", "mkv", "webm", "3gp", "avi", "mov", "m4v", "flv", "wmv")
        reg(AUDIO, "mp3", "m4a", "aac", "wav", "flac", "ogg", "opus", "amr", "wma")
        reg(PDF, "pdf")
        reg(DOC, "doc", "docx", "odt", "rtf")
        reg(XLS, "xls", "xlsx", "ods")
        reg(PPT, "ppt", "pptx", "odp")
        reg(APK, "apk")
        reg(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
        reg(TEXT, "txt", "md", "log")
        reg(CODE, "py", "js", "html", "htm", "css", "kt", "java", "c", "cpp", "h", "sh", "ts")
        reg(DATA, "json", "xml", "yaml", "yml", "csv")
    }

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun typeOf(name: String): Int = map[ext(name)] ?: OTHER

    /** Short label drawn inside the document icon (empty for unknown types). */
    fun badge(type: Int, ext: String): String = when (type) {
        PDF -> "PDF"
        DOC -> "DOC"
        XLS -> "XLS"
        PPT -> "PPT"
        APK -> "APK"
        ARCHIVE, TEXT, CODE, DATA -> ext.uppercase(Locale.ROOT).take(4)
        else -> ""
    }

    fun mime(name: String): String {
        val e = ext(name)
        return when (e) {
            "apk" -> "application/vnd.android.package-archive"
            "md" -> "text/markdown"
            "py", "kt", "java", "c", "cpp", "h", "sh", "ts", "log", "yaml", "yml" -> "text/plain"
            "7z" -> "application/x-7z-compressed"
            "rar" -> "application/vnd.rar"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(e) ?: "*/*"
        }
    }

    // ---- Formatting (decimal units, matching Android's own storage screen) ----

    private val UNITS = arrayOf("B", "KB", "MB", "GB", "TB")

    private fun unitIndex(bytes: Long): Int {
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1000.0 && u < UNITS.size - 1) {
            v /= 1000.0
            u++
        }
        return u
    }

    /** Number only, expressed in the given unit index (0 = B ... 3 = GB). */
    private fun sizeIn(bytes: Long, unit: Int): String {
        var v = bytes.toDouble()
        for (i in 0 until unit) v /= 1000.0
        val rounded = Math.round(v * 10.0) / 10.0
        val whole = unit == 0 || v >= 100.0 || rounded == Math.floor(rounded)
        return if (whole) String.format(Locale.getDefault(), "%.0f", v)
        else String.format(Locale.getDefault(), "%.1f", v)
    }

    /** "850 MB", "38.5 GB", "12 KB". */
    fun size(bytes: Long): String {
        val b = if (bytes < 0) 0L else bytes
        val u = unitIndex(b)
        return sizeIn(b, u) + " " + UNITS[u]
    }

    /** Storage indicator parts: ("38.5", "128 GB"), both in the unit of the total. */
    fun storagePair(used: Long, total: Long): Pair<String, String> {
        val u = unitIndex(total)
        return Pair(sizeIn(used, u), sizeIn(total, u) + " " + UNITS[u])
    }

    /** "3:07" or "1:02:45". */
    fun duration(ms: Long): String {
        val s = if (ms < 0) 0L else ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }
}