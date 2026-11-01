package app.flit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** One MediaStore entry (image, video or audio). [kind] is Types.IMAGE, Types.VIDEO or Types.AUDIO. */
class Media(
    val id: Long,
    val kind: Int,
    val path: String,
    val name: String,
    val size: Long,
    val duration: Long,
    val uri: Uri
) {
    val key: String = Sel.fileKey(path)
    fun item(): Item = Item(key, name, size, path, kind)
}

/** An installed application. [splits] is non-empty for split-APK apps. */
class AppEntry(
    val pkg: String,
    val label: String,
    val system: Boolean,
    val path: String,
    val splits: List<String>,
    val size: Long
) {
    private fun fileName(): String = label.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { pkg } + ".apk"
    fun item(): Item = Item(Sel.appKey(pkg), fileName(), size, path, Types.APK, fileName(), splits)
}

/** An APK file found in accessible storage. */
class ApkFile(val path: String, val name: String, val size: Long) {
    fun item(): Item = Item(Sel.fileKey(path), name, size, path, Types.APK)
}

/** One row of the Internal storage browser. */
class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val restricted: Boolean)

/** Storage statistics, MediaStore and PackageManager queries, directory listing, opening files. */
object Files {

    private const val APK_MIME = "application/vnd.android.package-archive"

    val root: File
        get() = Environment.getExternalStorageDirectory()

    /** (used, total) bytes of the internal storage. */
    fun storage(): Pair<Long, Long> {
        return try {
            val s = StatFs(root.path)
            val total = s.blockCountLong * s.blockSizeLong
            val free = s.availableBlocksLong * s.blockSizeLong
            Pair((total - free).coerceAtLeast(0L), total)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    // ---- MediaStore ----------------------------------------------------------------

    /** Newest first. Needs storage access. Call on a background thread. */
    @Suppress("DEPRECATION")
    fun queryMedia(kind: Int): List<Media> {
        val cr = FlitApp.app.contentResolver
        val base: Uri
        var sel = MediaStore.MediaColumns.SIZE + ">0"
        when (kind) {
            Types.IMAGE -> base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            Types.VIDEO -> base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else -> {
                base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                sel += " AND ${MediaStore.Audio.Media.IS_RINGTONE}=0" +
                    " AND ${MediaStore.Audio.Media.IS_NOTIFICATION}=0" +
                    " AND ${MediaStore.Audio.Media.IS_ALARM}=0"
            }
        }
        val hasDur = kind != Types.IMAGE
        val proj = if (hasDur) arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, "duration"
        ) else arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE
        )
        val out = ArrayList<Media>()
        cr.query(base, proj, sel, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                if (path.contains("/Android/data/")) continue
                val name = c.getString(2) ?: File(path).name
                val id = c.getLong(0)
                val dur = if (hasDur) c.getLong(4) else 0L
                out.add(Media(id, kind, path, name, c.getLong(3), dur, android.content.ContentUris.withAppendedId(base, id)))
            }
        }
        return out
    }

    // ---- Apps ------------------------------------------------------------------------

    /** All installed apps, sorted by label. System apps are limited to launchable ones. */
    fun queryApps(): List<AppEntry> {
        val pm = FlitApp.app.packageManager
        val list = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList<ApplicationInfo>()
        }
        val out = ArrayList<AppEntry>(list.size)
        for (ai in list) {
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (sys && pm.getLaunchIntentForPackage(ai.packageName) == null) continue
            val src = ai.sourceDir ?: continue
            val splits = ai.splitSourceDirs?.toList() ?: emptyList()
            var size = File(src).length()
            for (s in splits) size += File(s).length()
            val label = try {
                ai.loadLabel(pm).toString()
            } catch (e: Exception) {
                ai.packageName
            }
            out.add(AppEntry(ai.packageName, label, sys, src, splits, size))
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return out
    }

    /** APK files in accessible storage: MediaStore first, plus Download/ and Download/Flit/. */
    @Suppress("DEPRECATION")
    fun queryApks(): List<ApkFile> {
        val seen = HashSet<String>()
        val out = ArrayList<ApkFile>()
        try {
            FlitApp.app.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                arrayOf(MediaStore.Files.FileColumns.DATA, MediaStore.Files.FileColumns.SIZE),
                "${MediaStore.Files.FileColumns.MIME_TYPE}=? OR ${MediaStore.Files.FileColumns.DATA} LIKE ?",
                arrayOf(APK_MIME, "%.apk"),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    if (path.contains("/Android/data/") || path.contains("/.partial/")) continue
                    if (!path.endsWith(".apk", true)) continue
                    val f = File(path)
                    if (f.isFile && f.length() > 0 && seen.add(path)) out.add(ApkFile(path, f.name, f.length()))
                }
            }
        } catch (e: Exception) {
        }
        for (dir in listOf(File(root, "Download"), File(root, "Download/Flit"))) {
            val kids = dir.listFiles() ?: continue
            for (f in kids) {
                if (f.isFile && f.name.endsWith(".apk", true) && f.length() > 0 && seen.add(f.path)) {
                    out.add(ApkFile(f.path, f.name, f.length()))
                }
            }
        }
        out.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        return out
    }

    /** (package name, version code) of an APK file, or null if Android cannot parse it. */
    fun apkMeta(path: String): Pair<String, Long>? {
        return try {
            val pi = FlitApp.app.packageManager.getPackageArchiveInfo(path, 0) ?: return null
            Pair(pi.packageName, pi.longVersionCode)
        } catch (e: Exception) {
            null
        }
    }

    /** Installed version code of [pkg], or -1 if it is not installed. */
    fun installedVersion(pkg: String): Long {
        return try {
            FlitApp.app.packageManager.getPackageInfo(pkg, 0).longVersionCode
        } catch (e: PackageManager.NameNotFoundException) {
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    // ---- Directory browsing ----------------------------------------------------------

    /** Android 11+ blocks Android/data and Android/obb even with all-files access. */
    fun isRestricted(f: File): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val p = f.path
        val r = root.path
        return p == "$r/Android/data" || p == "$r/Android/obb"
    }

    /** Folders first, then files, each sorted by name. Null if the folder cannot be read. */
    fun listDir(dir: File): List<Entry>? {
        val arr = dir.listFiles() ?: return null
        val out = ArrayList<Entry>(arr.size)
        for (f in arr) {
            val n = f.name
            if (n.startsWith(".")) continue
            val d = f.isDirectory
            out.add(Entry(n, f.path, d, if (d) 0L else f.length(), d && isRestricted(f)))
        }
        out.sortWith(compareBy<Entry>({ !it.dir }, { it.name.lowercase() }))
        return out
    }

    /**
     * Every accessible file below [dir] as selectable items. The relative path keeps the folder
     * name, so a folder send recreates the structure on the Receiver. Call on a background thread.
     */
    fun expandFolder(dir: File, limit: Int = 200_000): List<Item> {
        val out = ArrayList<Item>()
        val base = dir.parentFile?.path ?: dir.path
        val stack = ArrayList<File>()
        stack.add(dir)
        while (stack.isNotEmpty() && out.size < limit) {
            val d = stack.removeAt(stack.size - 1)
            if (isRestricted(d)) continue
            val kids = d.listFiles() ?: continue
            for (f in kids) {
                if (f.isDirectory) {
                    if (f.name == ".partial") continue
                    if (!java.nio.file.Files.isSymbolicLink(f.toPath())) stack.add(f)
                } else {
                    val rel = f.path.removePrefix(base).trimStart('/')
                    out.add(Item(Sel.fileKey(f.path), f.name, f.length(), f.path, Types.typeOf(f.name), rel))
                }
            }
        }
        return out
    }

    // ---- Opening ---------------------------------------------------------------------

    /** Opens a file with another app (Open, Play) or the package installer (APK). */
    fun open(c: Context, path: String) {
        try {
            val f = File(path)
            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, Types.mime(f.name))
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        } catch (e: Exception) {
            Ui.toast(c, R.string.err_generic)
        }
    }
}