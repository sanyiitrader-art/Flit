package app.flit

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.RandomAccessFile

/**
 * The one place that decides how a file is opened.
 *
 * Built in (only formats Android itself decodes, so no extra codec libraries):
 *  - images: whatever Android's ImageDecoder reads (JPEG, PNG, WebP, GIF, BMP, HEIF; AVIF on
 *    Android 12+) plus SVG; the image screen hands over to "Open with" if decoding fails
 *  - video: MP4, MOV, 3GP, WebM, MKV, MPEG-TS, MPEG-PS; audio: MP3, M4A, AAC/ADTS, WAV, FLAC,
 *    Ogg/Opus, AMR (the player hands over to "Open with" if the phone cannot decode the codec)
 *  - PDF (Android's PdfRenderer), TXT, Markdown, HTML, and ZIP / 7z / RAR extraction
 *
 * Everything else, and anything a built-in viewer cannot handle, goes to Android's own
 * "Open with" chooser. Opening never silently does nothing: a missing file, a missing app or any
 * other failure always shows a short message.
 */
object Open {

    const val K_OTHER = 0
    const val K_IMAGE = 1
    const val K_VIDEO = 2
    const val K_AUDIO = 3
    const val K_PDF = 4
    const val K_TEXT = 5
    const val K_MD = 6
    const val K_HTML = 7
    const val K_ARCHIVE = 8
    const val K_APK = 9

    /** Text and Markdown larger than this are not loaded into memory; "Open with" is used instead. */
    private const val MAX_TEXT = 4L shl 20

    private val IMAGE = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "svg")
    private val VIDEO = setOf("mp4", "m4v", "mov", "3gp", "3gpp", "3g2", "webm", "mkv", "mpg", "mpeg", "mpe", "m2ts", "mts")
    private val AUDIO = setOf("mp3", "m4a", "aac", "adts", "wav", "flac", "ogg", "oga", "opus", "amr", "awb", "mka")
    private val TEXT = setOf("txt", "log")
    private val MD = setOf("md", "markdown")
    private val HTML = setOf("html", "htm")
    private val ARCHIVE = setOf("zip", "rar", "7z")

    /** What kind of file this is, by extension (".ts" is video only when it really is a transport stream). */
    fun kind(path: String): Int {
        val e = Types.ext(File(path).name)
        return when {
            e in IMAGE -> K_IMAGE
            e in VIDEO -> K_VIDEO
            e in AUDIO -> K_AUDIO
            e == "pdf" -> K_PDF
            e in TEXT -> K_TEXT
            e in MD -> K_MD
            e in HTML -> K_HTML
            e in ARCHIVE -> K_ARCHIVE
            e == "apk" -> K_APK
            e == "ts" -> if (isTransportStream(path)) K_VIDEO else K_OTHER
            else -> K_OTHER
        }
    }

    /** MPEG-TS packets are 188 bytes and each starts with 0x47; ".ts" is also TypeScript. */
    private fun isTransportStream(path: String): Boolean {
        return try {
            RandomAccessFile(path, "r").use { f ->
                if (f.length() < 189L) return false
                f.seek(0)
                val a = f.read()
                f.seek(188)
                val b = f.read()
                a == 0x47 && b == 0x47
            }
        } catch (e: Exception) {
            false
        }
    }

    /** True for kinds that the built-in player list (playlist) can contain. */
    fun playable(kind: Int): Boolean = kind == K_VIDEO || kind == K_AUDIO

    /**
     * Files of the same kind in the same folder, sorted by name: the playlist of the video and
     * audio players. Always contains [path] itself.
     */
    fun siblings(path: String): List<File> {
        val f = File(path)
        val k = kind(path)
        val list = f.parentFile?.listFiles()?.filter { it.isFile && !it.name.startsWith(".") && kind(it.path) == k }
        val out = (list ?: emptyList()).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return if (out.any { it.path == f.path }) out else listOf(f) + out
    }

    /** Opens [path] in the built-in viewer for its type, or in Android's chooser. */
    fun file(c: Context, path: String) {
        val f = File(path)
        if (!f.isFile) {
            Ui.toast(c, R.string.v_open_missing)
            return
        }
        val a = c.findActivity() as? MainActivity
        if (a == null || a.isFinishing || a.isDestroyed) {
            external(c, path)
            return
        }
        when (kind(path)) {
            K_IMAGE -> a.push(ImageScreen(a, path))
            K_VIDEO -> a.push(VideoScreen(a, path))
            K_AUDIO -> a.push(AudioScreen(a, path))
            K_PDF -> a.push(PdfScreen(a, path))
            K_TEXT -> if (f.length() <= MAX_TEXT) a.push(TextScreen(a, path)) else external(c, path)
            K_MD -> if (f.length() <= MAX_TEXT) a.push(HtmlScreen(a, path, true)) else external(c, path)
            K_HTML -> a.push(HtmlScreen(a, path))
            K_ARCHIVE -> Extractor.ask(a, path)
            K_APK -> installApk(a, path)
            else -> external(c, path)
        }
    }

    /**
     * A built-in screen could not handle the file (unsupported codec, password-protected PDF, ...):
     * close it and offer the file to other apps instead.
     */
    fun fallback(a: MainActivity, screen: Screen, path: String) {
        FlitApp.main.post {
            if (a.isFinishing || a.isDestroyed) return@post
            if (a.top() === screen) a.pop()
            external(a, path)
        }
    }

    /**
     * Android's native "Open with" chooser. Shows a friendly message when no app can open the
     * file, the file is gone, or anything else goes wrong.
     */
    fun external(c: Context, path: String) {
        val f = File(path)
        if (!f.isFile) {
            Ui.toast(c, R.string.v_open_missing)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
            val view = Intent(Intent.ACTION_VIEW)
            view.setDataAndType(uri, Types.mime(f.name))
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = ClipData.newRawUri("", uri)
            val apps = c.packageManager.queryIntentActivities(view, 0)
                .filter { it.activityInfo.packageName != c.packageName }
            if (apps.isEmpty()) {
                Ui.toast(c, R.string.v_open_no_app)
                return
            }
            val chooser = Intent.createChooser(view, c.getString(R.string.v_open_with))
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (c !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Ui.toast(c, R.string.v_open_no_app)
        } catch (e: Exception) {
            Ui.toast(c, R.string.v_open_failed)
        }
    }
}
