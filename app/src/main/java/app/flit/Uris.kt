package app.flit

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream

/**
 * Turns the Uris that other apps hand to Flit ("Open with" and "Share") into files.
 *
 * Normally the Uri points at a real file on the phone (file://, a MediaStore item, or a document
 * in Downloads / internal storage / an SD card), and its path is used directly: nothing is copied.
 * Only when the other app hands over a private content Uri that has no path (for example a file from
 * another app's own storage) is the content copied once into Flit's cache, because the viewers and
 * the transfer engine work with files.
 */
object Uris {

    /** The files shared with ACTION_SEND / ACTION_SEND_MULTIPLE. */
    @Suppress("DEPRECATION")
    fun streams(i: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        if (i.action == Intent.ACTION_SEND_MULTIPLE) {
            val l: List<Uri>? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                i.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (l != null) out.addAll(l)
        } else {
            val u: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                i.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (u != null) out.add(u)
        }
        if (out.isEmpty()) {
            val cd = i.clipData
            if (cd != null) for (k in 0 until cd.itemCount) cd.getItemAt(k).uri?.let { out.add(it) }
        }
        return out
    }

    /** The file behind [uri]: its real path when it has one, otherwise a copy in the cache. Background thread. */
    fun resolve(c: Context, uri: Uri, mime: String?): File? {
        path(c, uri)?.let { return File(it) }
        return copy(c, uri, mime)
    }

    /** Removes cached copies older than an hour. */
    fun purge(c: Context) {
        val dir = File(c.cacheDir, "shared")
        val old = System.currentTimeMillis() - 3_600_000L
        dir.listFiles()?.forEach { if (it.lastModified() < old) Unpack.deleteTree(it) }
    }

    // ---- Real path -------------------------------------------------------------------------------

    private fun path(c: Context, uri: Uri): String? {
        try {
            if (uri.scheme == "file") return uri.path?.takeIf { File(it).isFile }
            if (uri.scheme != "content") return null
            val auth = uri.authority ?: return null
            if (DocumentsContract.isDocumentUri(c, uri)) {
                val id = DocumentsContract.getDocumentId(uri)
                val parts = id.split(":", limit = 2)
                when (auth) {
                    "com.android.externalstorage.documents" -> {
                        val base = if (parts[0].equals("primary", true)) Files.root.path else "/storage/${parts[0]}"
                        return "$base/${parts.getOrElse(1) { "" }}".takeIf { File(it).isFile }
                    }
                    "com.android.providers.downloads.documents" -> {
                        if (id.startsWith("raw:")) return id.removePrefix("raw:").takeIf { File(it).isFile }
                        val n = id.toLongOrNull() ?: return null
                        return dataColumn(c, ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), n))
                    }
                    "com.android.providers.media.documents" -> {
                        if (parts.size < 2) return null
                        val base = when (parts[0]) {
                            "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                            "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                            "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                            else -> MediaStore.Files.getContentUri("external")
                        }
                        return dataColumn(c, ContentUris.withAppendedId(base, parts[1].toLongOrNull() ?: return null))
                    }
                }
            }
            return dataColumn(c, uri) // MediaStore items and providers that expose the path
        } catch (e: Exception) {
            return null
        }
    }

    private fun dataColumn(c: Context, uri: Uri): String? {
        return try {
            c.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.takeIf { p -> File(p).isFile } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- Copy (only when there is no path) ----------------------------------------------------------

    private fun copy(c: Context, uri: Uri, mime: String?): File? {
        val sub = File(c.cacheDir, "shared/" + System.nanoTime())
        if (!sub.mkdirs()) return null
        val out = File(sub, displayName(c, uri, mime))
        return try {
            val ins = c.contentResolver.openInputStream(uri) ?: return null
            ins.use { i -> FileOutputStream(out).use { o -> i.copyTo(o, 128 * 1024) } }
            out
        } catch (e: Exception) {
            out.delete()
            null
        }
    }

    private fun displayName(c: Context, uri: Uri, mime: String?): String {
        var name: String? = null
        try {
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (e: Exception) {
        }
        var n = (name ?: uri.lastPathSegment ?: "file").substringAfterLast('/').replace(Regex("[\\\\:*?\"<>|]"), "_").trim()
        if (n.isEmpty()) n = "file"
        if (!n.contains('.')) {
            val type = mime ?: c.contentResolver.getType(uri)
            val ext = type?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) n = "$n.$ext"
        }
        return n
    }
}
