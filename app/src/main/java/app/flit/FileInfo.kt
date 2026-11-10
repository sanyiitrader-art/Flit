package app.flit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile

/** Avatars of the devices Flit exchanged files with, kept so the history can show who it was. */
object PeerAvatars {

    private fun file(name: String): File {
        val h = MessageDigest.getInstance("SHA-1").digest(name.toByteArray())
        val hex = h.joinToString("") { "%02x".format(it) }
        return File(File(FlitApp.app.filesDir, "peers"), "$hex.jpg")
    }

    fun save(name: String, bmp: Bitmap) {
        try {
            val f = file(name)
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (e: Exception) {
        }
    }

    fun load(name: String): Bitmap? = try {
        BitmapFactory.decodeFile(file(name).path)
    } catch (e: Exception) {
        null
    }
}

/**
 * The details dialog shown by a long press on a file card: name, location, type, size and dates for
 * everything, then what is specific to the kind of file (picture data and camera details, video and
 * audio streams and tags, pages, text statistics, archive contents, app information, folder totals).
 * For a History entry it also tells who the file was exchanged with, when and how much was transferred.
 * The basic rows appear at once; the deeper ones are read in the background and added as they are ready.
 */
object FileInfo {

    private class Rows(c: Context) : LinearLayout(c) {

        init {
            orientation = VERTICAL
            setPadding(0, Ui.dp(2), 0, Ui.dp(6))
        }

        fun section(t: String) {
            val v = Ui.label(context, t.uppercase(), 11f, Ui.cAccent, true)
            v.letterSpacing = 0.08f
            addView(v, Ui.lin(Ui.MATCH, Ui.WRAP).also {
                it.topMargin = Ui.dp(14)
                it.bottomMargin = Ui.dp(2)
            })
        }

        fun row(label: String, value: String?) {
            if (value.isNullOrBlank()) return
            addView(Ui.label(context, label, 12f, Ui.cText2, false), Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(8) })
            val v = TextView(context)
            v.text = value
            v.setTextColor(Ui.cText)
            v.textSize = 14f
            v.setTextIsSelectable(true)
            addView(v, Ui.lin(Ui.MATCH, Ui.WRAP))
        }

        fun caption(label: String) {
            addView(Ui.label(context, label, 12f, Ui.cText2, false), Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(8) })
        }

        fun person(name: String, bmp: Bitmap?) {
            val r = LinearLayout(context)
            r.orientation = HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            val av = AvatarView(context)
            av.bitmap = bmp
            av.initial = name
            r.addView(av, Ui.lin(Ui.dp(36), Ui.dp(36)))
            r.addView(Ui.label(context, name, 15f, Ui.cText, true, 1), Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(12) })
            addView(r, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(8) })
        }
    }

    private class Op(val label: String, val value: String?)

    private fun sec(c: Context, id: Int) = Op("§" + c.getString(id), null)

    /** [h] is the History entry when the dialog comes from the History tab. */
    fun show(c: Context, path: String, h: Hist? = null) {
        val a = c.findActivity() ?: return
        val f = File(path)
        val body = Rows(a)
        val rows = body

        if (h != null) {
            rows.section(a.getString(R.string.fi_s_transfer))
            rows.row(a.getString(R.string.fi_direction), a.getString(if (h.dir == Db.RECEIVED) R.string.hist_received else R.string.hist_sent))
            rows.row(a.getString(R.string.fi_when), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(Date(h.time)))
            rows.row(a.getString(R.string.fi_data), bytes(h.size))
            if (h.peer.isNotBlank()) {
                rows.caption(a.getString(if (h.dir == Db.RECEIVED) R.string.fi_from else R.string.fi_to))
                for (n in h.peer.split(", ").map { it.trim() }.filter { it.isNotEmpty() }) rows.person(n, PeerAvatars.load(n))
            }
            rows.row(a.getString(R.string.fi_checksum), h.root.ifBlank { null })
        }

        rows.section(a.getString(R.string.fi_s_file))
        rows.row(a.getString(R.string.fi_name), h?.name ?: f.name)
        rows.row(a.getString(R.string.fi_folder), f.parent ?: "")
        if (!f.exists()) {
            rows.row("", a.getString(R.string.fi_missing))
        } else {
            val ext = Types.ext(f.name)
            rows.row(a.getString(R.string.fi_type), (if (f.isDirectory) a.getString(R.string.fi_s_folder) else ext.uppercase().ifEmpty { "—" } + " · " + Types.mime(f.name)))
            if (f.isFile) rows.row(a.getString(R.string.fi_size), bytes(f.length()))
            rows.row(a.getString(R.string.fi_modified), DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(f.lastModified())))
            rows.row(a.getString(R.string.fi_access), a.getString(if (f.canWrite()) R.string.fi_rw else R.string.fi_ro))
        }

        Dialogs.details(a, h?.name ?: f.name, body)
        if (!f.exists()) return

        Files.pool.execute {
            val ops = ArrayList<Op>()
            try {
                if (f.isDirectory) folder(a, f, ops) else when (Open.kind(path)) {
                    Open.K_IMAGE -> image(a, f, ops)
                    Open.K_VIDEO -> video(a, f, ops)
                    Open.K_AUDIO -> audio(a, f, ops)
                    Open.K_PDF -> pdf(a, f, ops)
                    Open.K_TEXT, Open.K_MD, Open.K_HTML -> text(a, f, ops)
                    Open.K_ARCHIVE -> archive(a, f, ops)
                    Open.K_APK -> apk(a, f, ops)
                }
            } catch (e: Throwable) {
                ops.add(Op(a.getString(R.string.fi_unreadable), null))
            }
            FlitApp.main.post {
                for (o in ops) {
                    if (o.label.startsWith("§")) rows.section(o.label.substring(1))
                    else if (o.value == null) rows.row("", o.label)
                    else rows.row(o.label, o.value)
                }
            }
        }
    }

    // ---- Formatting helpers ----------------------------------------------------------------------

    private fun bytes(n: Long): String = Types.size(n) + " (" + NumberFormat.getIntegerInstance().format(n) + " B)"

    private fun dur(ms: Long): String = clockText(ms)

    private fun rate(bps: Long): String =
        if (bps >= 1_000_000L) String.format(Locale.US, "%.2f Mbps", bps / 1_000_000.0) else String.format(Locale.US, "%d kbps", bps / 1000L)

    private fun add(ops: ArrayList<Op>, c: Context, id: Int, v: String?) {
        if (!v.isNullOrBlank()) ops.add(Op(c.getString(id), v))
    }

    // ---- Image ----------------------------------------------------------------------------------------

    private fun image(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_image))
        if (Types.ext(f.name) == "svg") {
            ops.add(Op(c.getString(R.string.fi_format), c.getString(R.string.fi_vector)))
            return
        }
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth > 0) {
            add(ops, c, R.string.fi_dimensions, "${o.outWidth} × ${o.outHeight}")
            add(ops, c, R.string.fi_megapixels, String.format(Locale.US, "%.1f MP", o.outWidth.toLong() * o.outHeight / 1_000_000.0))
            add(ops, c, R.string.fi_format, o.outMimeType)
        }
        try {
            val e = ExifInterface(f.path)
            val make = e.getAttribute(ExifInterface.TAG_MAKE)?.trim()
            val model = e.getAttribute(ExifInterface.TAG_MODEL)?.trim()
            add(ops, c, R.string.fi_camera, listOfNotNull(make, model).joinToString(" ").ifBlank { null })
            add(ops, c, R.string.fi_taken, e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))
            val ex = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            if (ex > 0.0) add(ops, c, R.string.fi_exposure, if (ex < 1.0) "1/${Math.round(1.0 / ex)} s" else String.format(Locale.US, "%.1f s", ex))
            val fn = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (fn > 0.0) add(ops, c, R.string.fi_aperture, String.format(Locale.US, "f/%.1f", fn))
            val iso = e.getAttributeInt("ISOSpeedRatings", 0)
            if (iso > 0) add(ops, c, R.string.fi_iso, iso.toString())
            val fl = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            if (fl > 0.0) add(ops, c, R.string.fi_focal, String.format(Locale.US, "%.1f mm", fl))
            add(ops, c, R.string.fi_software, e.getAttribute(ExifInterface.TAG_SOFTWARE))
            val ll = FloatArray(2)
            if (e.getLatLong(ll)) add(ops, c, R.string.fi_gps, String.format(Locale.US, "%.5f, %.5f", ll[0], ll[1]))
        } catch (e: Exception) {
        }
    }

    // ---- Video and audio ---------------------------------------------------------------------------

    private fun num(fm: MediaFormat, key: String): Long? {
        if (!fm.containsKey(key)) return null
        return try {
            fm.getInteger(key).toLong()
        } catch (e: Exception) {
            try {
                Math.round(fm.getFloat(key).toDouble())
            } catch (e2: Exception) {
                null
            }
        }
    }

    private fun streams(c: Context, f: File, ops: ArrayList<Op>) {
        val x = MediaExtractor()
        try {
            x.setDataSource(f.path)
            for (i in 0 until x.trackCount) {
                val fm = x.getTrackFormat(i)
                val mime = fm.getString(MediaFormat.KEY_MIME) ?: continue
                val parts = ArrayList<String>()
                parts.add(mime)
                if (mime.startsWith("video/")) {
                    val w = num(fm, MediaFormat.KEY_WIDTH)
                    val h = num(fm, MediaFormat.KEY_HEIGHT)
                    if (w != null && h != null) parts.add("$w×$h")
                    num(fm, MediaFormat.KEY_FRAME_RATE)?.let { parts.add("$it fps") }
                    num(fm, MediaFormat.KEY_BIT_RATE)?.let { parts.add(rate(it)) }
                    ops.add(Op(c.getString(R.string.fi_video_track), parts.joinToString(" · ")))
                } else if (mime.startsWith("audio/")) {
                    num(fm, MediaFormat.KEY_SAMPLE_RATE)?.let { parts.add("$it Hz") }
                    num(fm, MediaFormat.KEY_CHANNEL_COUNT)?.let { parts.add("$it ch") }
                    num(fm, MediaFormat.KEY_BIT_RATE)?.let { parts.add(rate(it)) }
                    ops.add(Op(c.getString(R.string.fi_audio_track), parts.joinToString(" · ")))
                }
            }
        } catch (e: Exception) {
        } finally {
            x.release()
        }
    }

    private fun video(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_video))
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(f.path)
            fun k(key: Int) = m.extractMetadata(key)
            k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { add(ops, c, R.string.fi_duration, dur(it)) }
            val w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            if (w != null && h != null) add(ops, c, R.string.fi_dimensions, "$w × $h")
            k(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()?.let {
                add(ops, c, R.string.fi_frame_rate, String.format(Locale.US, "%.2f fps", it))
            }
            k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { add(ops, c, R.string.fi_bitrate, rate(it)) }
            add(ops, c, R.string.fi_container, k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            add(ops, c, R.string.fi_created, k(MediaMetadataRetriever.METADATA_KEY_DATE))
            add(ops, c, R.string.fi_gps, k(MediaMetadataRetriever.METADATA_KEY_LOCATION))
        } catch (e: Exception) {
        } finally {
            try {
                m.release()
            } catch (e: Exception) {
            }
        }
        streams(c, f, ops)
    }

    private fun audio(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_audio))
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(f.path)
            fun k(key: Int) = m.extractMetadata(key)
            add(ops, c, R.string.fi_title, k(MediaMetadataRetriever.METADATA_KEY_TITLE))
            add(ops, c, R.string.fi_artist, k(MediaMetadataRetriever.METADATA_KEY_ARTIST))
            add(ops, c, R.string.fi_album, k(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            add(ops, c, R.string.fi_album_artist, k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
            add(ops, c, R.string.fi_genre, k(MediaMetadataRetriever.METADATA_KEY_GENRE))
            add(ops, c, R.string.fi_year, k(MediaMetadataRetriever.METADATA_KEY_YEAR))
            add(ops, c, R.string.fi_track, k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            add(ops, c, R.string.fi_disc, k(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            add(ops, c, R.string.fi_composer, k(MediaMetadataRetriever.METADATA_KEY_COMPOSER))
            k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { add(ops, c, R.string.fi_duration, dur(it)) }
            k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let { add(ops, c, R.string.fi_bitrate, rate(it)) }
            add(ops, c, R.string.fi_container, k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
            add(ops, c, R.string.fi_artwork, c.getString(if (m.embeddedPicture != null) R.string.fi_yes else R.string.fi_no))
        } catch (e: Exception) {
        } finally {
            try {
                m.release()
            } catch (e: Exception) {
            }
        }
        streams(c, f, ops)
    }

    // ---- Documents ------------------------------------------------------------------------------------

    private fun pdf(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_doc))
        try {
            val d = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            d.use {
                val r = PdfRenderer(it)
                add(ops, c, R.string.fi_pages, r.pageCount.toString())
                if (r.pageCount > 0) {
                    val p = r.openPage(0)
                    add(ops, c, R.string.fi_page_size, "${p.width} × ${p.height} pt")
                    p.close()
                }
                r.close()
            }
        } catch (e: SecurityException) {
            add(ops, c, R.string.fi_protected, c.getString(R.string.fi_yes))
        }
    }

    private fun text(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_doc))
        if (f.length() > 4L shl 20) return
        val b = f.readBytes()
        var lines = if (b.isEmpty()) 0 else 1
        for (x in b) if (x == '\n'.code.toByte()) lines++
        val utf8 = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: Exception) {
            null
        }
        val s = utf8 ?: String(b, Charsets.ISO_8859_1)
        add(ops, c, R.string.fi_encoding, if (utf8 != null) "UTF-8" else "ISO-8859-1")
        add(ops, c, R.string.fi_lines, NumberFormat.getIntegerInstance().format(lines))
        add(ops, c, R.string.fi_words, NumberFormat.getIntegerInstance().format(s.split(Regex("\\s+")).count { it.isNotEmpty() }))
        add(ops, c, R.string.fi_chars, NumberFormat.getIntegerInstance().format(s.length))
        if (Open.kind(f.path) == Open.K_HTML) {
            Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(s)
                ?.groupValues?.get(1)?.trim()?.let { add(ops, c, R.string.fi_title, it) }
        }
    }

    private fun archive(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_archive))
        var files = 0
        var dirs = 0
        var unpacked = 0L
        var packed = -1L
        when (Types.ext(f.name)) {
            "zip" -> ZipFile(f).use { z ->
                packed = 0L
                for (e in java.util.Collections.list(z.entries())) {
                    if (e.isDirectory) dirs++ else {
                        files++
                        if (e.size > 0) unpacked += e.size
                        if (e.compressedSize > 0) packed += e.compressedSize
                    }
                }
            }
            "7z" -> @Suppress("DEPRECATION") SevenZFile(f).use { z ->
                for (e in z.entries) if (e.isDirectory) dirs++ else {
                    files++
                    if (e.size > 0) unpacked += e.size
                }
            }
            "rar" -> Archive(f).use { a ->
                for (h in a.fileHeaders) if (h.isDirectory) dirs++ else {
                    files++
                    unpacked += h.fullUnpackSize
                }
            }
        }
        add(ops, c, R.string.fi_entries, c.getString(R.string.fi_files_n, files, dirs))
        if (unpacked > 0L) add(ops, c, R.string.fi_unpacked, bytes(unpacked))
        if (packed > 0L) add(ops, c, R.string.fi_packed, bytes(packed))
    }

    private fun apk(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_app))
        val pm = c.packageManager
        val pi = pm.getPackageArchiveInfo(f.path, android.content.pm.PackageManager.GET_PERMISSIONS) ?: return
        val ai = pi.applicationInfo
        if (ai != null) {
            ai.sourceDir = f.path
            ai.publicSourceDir = f.path
            add(ops, c, R.string.fi_app, ai.loadLabel(pm).toString())
        }
        add(ops, c, R.string.fi_package, pi.packageName)
        add(ops, c, R.string.fi_version, "${pi.versionName ?: "?"} (${pi.longVersionCode})")
        if (ai != null) {
            add(ops, c, R.string.fi_min_sdk, "API ${ai.minSdkVersion}")
            add(ops, c, R.string.fi_target_sdk, "API ${ai.targetSdkVersion}")
        }
        add(ops, c, R.string.fi_perms, (pi.requestedPermissions?.size ?: 0).toString())
        val inst = Files.installedVersion(pi.packageName)
        add(ops, c, R.string.fi_installed, if (inst >= 0L) c.getString(R.string.fi_yes) + " ($inst)" else c.getString(R.string.fi_not_installed))
        val parts = Files.splitsOf(f.path)
        if (parts.isNotEmpty()) add(ops, c, R.string.fi_parts, "${parts.size} · " + Types.size(parts.sumOf { it.length() }))
    }

    private fun folder(c: Context, f: File, ops: ArrayList<Op>) {
        ops.add(sec(c, R.string.fi_s_folder))
        if (Files.isRestricted(f)) return
        var files = 0
        var dirs = 0
        var size = 0L
        val stack = ArrayList<File>()
        stack.add(f)
        while (stack.isNotEmpty() && files + dirs < 200_000) {
            val d = stack.removeAt(stack.size - 1)
            val kids = d.listFiles() ?: continue
            for (k in kids) {
                if (k.isDirectory) {
                    dirs++
                    if (!java.nio.file.Files.isSymbolicLink(k.toPath())) stack.add(k)
                } else {
                    files++
                    size += k.length()
                }
            }
        }
        add(ops, c, R.string.fi_entries, c.getString(R.string.fi_files_n, files, dirs))
        add(ops, c, R.string.fi_size, bytes(size))
    }
}
