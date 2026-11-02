package app.flit

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.stream.Collectors

/** One MediaStore entry (image, video or audio). [kind] is Types.IMAGE, Types.VIDEO or Types.AUDIO. */
class Media(
    val id: Long,
    val kind: Int,
    val path: String,
    val name: String,
    val size: Long,
    val duration: Long,
    private val base: Uri
) {
    /** Built on demand: only visible rows ever need it. */
    val uri: Uri get() = ContentUris.withAppendedId(base, id)
    val key: String get() = Sel.fileKey(path)
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

/** Result of the fast app pass: labelled user apps, plus system apps that are not labelled yet. */
class AppsFast(val users: List<AppEntry>, val systems: List<ApplicationInfo>)

/** An APK file found in accessible storage. */
class ApkFile(val path: String, val name: String, val size: Long) {
    fun item(): Item = Item(Sel.fileKey(path), name, size, path, Types.APK)
}

/** One row of the Internal storage browser. */
class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val restricted: Boolean)

/** Storage statistics, MediaStore and PackageManager queries, directory listing, opening files. */
object Files {

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val TAG = "FlitPerf"
    private const val FIRST_CHUNK = 150
    private const val NEXT_CHUNK = 800

    val root: File
        get() = Environment.getExternalStorageDirectory()

    /**
     * Dedicated pool for MediaStore / PackageManager loads, so slow loads never queue behind
     * (or in front of) the directory listing and avatar decoding on [FlitApp.io].
     * Threads exit after 10 s idle.
     */
    val pool: ExecutorService by lazy {
        val p = ThreadPoolExecutor(
            3, 3, 10L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
            ThreadFactory { r -> Thread(r, "flit-load").apply { priority = Thread.NORM_PRIORITY - 1 } }
        )
        p.allowCoreThreadTimeOut(true)
        p
    }

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

    private val mediaVer = IntArray(3) // indexed by Types.IMAGE / VIDEO / AUDIO (0, 1, 2)
    private val observers = ArrayList<ContentObserver>()
    private var watching = false

    /** Increments whenever MediaStore reports a change for this kind. Main thread. */
    fun mediaVersion(kind: Int): Int = mediaVer[kind.coerceIn(0, 2)]

    /** Starts passive change tracking (no polling, no work until MediaStore notifies). */
    fun watchMedia() {
        if (watching) return
        watching = true
        val cr = FlitApp.app.contentResolver
        val uris = arrayOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        )
        for (k in 0..2) {
            val obs = object : ContentObserver(FlitApp.main) {
                override fun onChange(selfChange: Boolean) {
                    mediaVer[k]++
                }
            }
            observers.add(obs)
            try {
                cr.registerContentObserver(uris[k], true, obs)
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Newest first. Runs ONE query and streams the rows in chunks (150, then 800 at a time) so the
     * first screenful can be shown before the rest has been read. [emit] is called on the calling
     * (background) thread; the lists it receives are never reused. Needs storage access.
     */
    @Suppress("DEPRECATION")
    fun streamMedia(kind: Int, cancelled: () -> Boolean, emit: (List<Media>) -> Unit) {
        val t0 = SystemClock.elapsedRealtime()
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

        val cursor = cr.query(base, proj, sel, null, MediaStore.MediaColumns.DATE_ADDED + " DESC") ?: return
        var chunk = ArrayList<Media>(FIRST_CHUNK)
        var limit = FIRST_CHUNK
        var total = 0
        cursor.use { c ->
            Log.d(TAG, "media kind=$kind query ${SystemClock.elapsedRealtime() - t0} ms")
            while (c.moveToNext()) {
                val path = c.getString(1) ?: continue
                if (path.contains("/Android/data/")) continue
                val name = c.getString(2) ?: File(path).name
                val dur = if (hasDur) c.getLong(4) else 0L
                chunk.add(Media(c.getLong(0), kind, path, name, c.getLong(3), dur, base))
                if (chunk.size >= limit) {
                    total += chunk.size
                    emit(chunk)
                    if (total == FIRST_CHUNK) {
                        Log.d(TAG, "media kind=$kind first chunk ${SystemClock.elapsedRealtime() - t0} ms")
                    }
                    chunk = ArrayList(NEXT_CHUNK)
                    limit = NEXT_CHUNK
                    if (cancelled()) return
                }
            }
        }
        if (chunk.isNotEmpty()) emit(chunk)
        Log.d(TAG, "media kind=$kind all ${total + chunk.size} rows ${SystemClock.elapsedRealtime() - t0} ms")
    }

    // ---- Apps ------------------------------------------------------------------------

    private class Meta(val label: String, val size: Long)

    /** Label and size cache. The key changes when an app is updated (new install path) or the language changes. */
    private val metaCache = ConcurrentHashMap<String, Meta>()

    private fun metaOf(pm: PackageManager, ai: ApplicationInfo): Meta {
        val key = ai.packageName + "|" + ai.sourceDir + "|" + Prefs.lang
        val hit = metaCache[key]
        if (hit != null) return hit
        val label = try {
            ai.loadLabel(pm).toString()
        } catch (e: Exception) {
            ai.packageName
        }
        var size = File(ai.sourceDir).length()
        val splits = ai.splitSourceDirs
        if (splits != null) for (s in splits) size += File(s).length()
        val m = Meta(label, size)
        metaCache[key] = m
        return m
    }

    private fun entryOf(pm: PackageManager, ai: ApplicationInfo, system: Boolean): AppEntry {
        val m = metaOf(pm, ai)
        return AppEntry(ai.packageName, m.label, system, ai.sourceDir, ai.splitSourceDirs?.toList() ?: emptyList(), m.size)
    }

    private fun entriesOf(pm: PackageManager, list: List<ApplicationInfo>, system: Boolean): List<AppEntry> {
        val out: List<AppEntry> = if (list.size > 24) {
            list.parallelStream().map { entryOf(pm, it, system) }.collect(Collectors.toList())
        } else {
            list.map { entryOf(pm, it, system) }
        }
        return out.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** Packages that have a launcher (or info) activity: ONE query instead of one per app. */
    private fun launchables(pm: PackageManager): Set<String> {
        val set = HashSet<String>()
        for (cat in arrayOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_INFO)) {
            try {
                val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
                for (r in pm.queryIntentActivities(i, 0)) set.add(r.activityInfo.packageName)
            } catch (e: Exception) {
            }
        }
        return set
    }

    /**
     * Fast pass for the Apps tab: the full list of user apps with labels (cached, parallel), and the
     * launchable system apps WITHOUT labels. System labels are only needed when that collapsed
     * section is opened: see [systemEntries].
     */
    fun queryUserApps(): AppsFast {
        val t0 = SystemClock.elapsedRealtime()
        val pm = FlitApp.app.packageManager
        val list = try {
            pm.getInstalledApplications(0)
        } catch (e: Exception) {
            emptyList<ApplicationInfo>()
        }
        val users = ArrayList<ApplicationInfo>(list.size)
        val sysRaw = ArrayList<ApplicationInfo>()
        for (ai in list) {
            if (ai.sourceDir == null) continue
            val sys = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (sys) sysRaw.add(ai) else users.add(ai)
        }
        val t1 = SystemClock.elapsedRealtime()
        val launch: Set<String> = if (sysRaw.isEmpty()) emptySet() else launchables(pm)
        val systems = sysRaw.filter { launch.contains(it.packageName) }
        val entries = entriesOf(pm, users, false)
        Log.d(
            TAG,
            "apps list ${t1 - t0} ms, launchables+labels ${SystemClock.elapsedRealtime() - t1} ms, " +
                "users=${entries.size} systems=${systems.size}"
        )
        return AppsFast(entries, systems)
    }

    /** Labels and sorts system apps (called when the System apps section is first opened). */
    fun systemEntries(raw: List<ApplicationInfo>): List<AppEntry> =
        entriesOf(FlitApp.app.packageManager, raw, true)

    /** Warms the label cache while the user is still on the Home screen. */
    fun prefetchApps() {
        pool.execute {
            try {
                queryUserApps()
            } catch (e: Exception) {
            }
        }
    }

    /** APK files in accessible storage: MediaStore first, plus Download/ and Download/Flit/. */
    @Suppress("DEPRECATION")
    fun queryApks(): List<ApkFile> {
        val t0 = SystemClock.elapsedRealtime()
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
        Log.d(TAG, "apk scan ${SystemClock.elapsedRealtime() - t0} ms, ${out.size} files")
        return out
    }

    /** (package name, version code) of an APK file, or null if Android cannot parse it (e.g. a lone split APK). */
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

    // ---- Opening and installing ------------------------------------------------------

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

    /** True when Android lets Flit hand APKs to the package installer ("Install unknown apps"). */
    fun canInstall(c: Context): Boolean = c.packageManager.canRequestPackageInstalls()

    /** Starts Android's own Package Installer for [path]. Returns false if it could not be started. */
    fun install(c: Context, path: String): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(c, c.packageName + ".files", File(path))
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, APK_MIME)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}