package app.flit

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Section header. Collapsible headers show a chevron that turns down when expanded. */
class HeaderCell(c: Context) : LinearLayout(c) {

    val title = Ui.label(c, "", 13f, Ui.cText2, true)
    val chev = ImageView(c)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
        setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(12), Ui.dp(8))
        chev.setImageDrawable(Icons.glyph(Icons.CHEVRON, Ui.cText2))
        addView(title, Ui.lin(0, Ui.WRAP, 1f))
        addView(chev, Ui.lin(Ui.dp(20), Ui.dp(20)))
    }
}

/** App or APK cell: icon, name (2 lines), size line under the name, selection check at the top-right. */
class AppCell(c: Context) : FrameLayout(c) {

    val icon = ImageView(c)
    val name = Ui.label(c, "", 11f, Ui.cText, false, 2)
    val note = Ui.label(c, "", 9.5f, Ui.cText2, false, 1)
    val check = CheckView(c)
    val ph: Drawable = DocGlyph("APK", Ui.cText2)

    private val bgOn = Ui.ripple(Ui.shape(Ui.alpha(Ui.cAccent, 30), 12, Ui.cAccent, 1), 12)
    private val bgOff = Ui.ripple(null, 12)

    init {
        layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).also {
            it.setMargins(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3))
        }
        val col = LinearLayout(c)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(Ui.dp(4), Ui.dp(10), Ui.dp(4), Ui.dp(8))
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        col.addView(icon, Ui.lin(Ui.dp(52), Ui.dp(52)))
        name.gravity = Gravity.CENTER
        col.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(6) })
        note.gravity = Gravity.CENTER
        col.addView(note, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(1) })
        addView(col, LayoutParams(Ui.MATCH, Ui.WRAP))
        addView(
            check,
            LayoutParams(Ui.dp(22), Ui.dp(22), Gravity.TOP or Gravity.END).also {
                it.setMargins(0, Ui.dp(4), Ui.dp(4), 0)
            }
        )
        mark(false)
    }

    fun mark(on: Boolean) {
        check.checked = on
        check.visibility = if (on) View.VISIBLE else View.INVISIBLE
        background = if (on) bgOn else bgOff
    }
}

/**
 * Apps tab: four-column grid. Order: installed user apps, APK files found in storage,
 * then a collapsible (collapsed by default) System apps section. Every cell shows its size under
 * its name (for split-APK apps: the total of all parts).
 *
 * Loading is progressive: user apps and the APK scan run in parallel on a dedicated pool and each
 * appears as soon as it is ready. System apps are only labelled when their section is opened.
 *
 * Live updates: installing, updating or removing an app (package broadcasts) refreshes the grid, and
 * APK files appearing or disappearing refresh the APK section, whenever this tab is on screen.
 * Changes that happen while it is hidden are detected the next time it is shown.
 *
 * Tap selects / deselects. Long-press never selects:
 *  - installed app            -> "Already installed" dialog
 *  - APK file, same or newer version installed (package identity, not filename) -> same dialog
 *  - APK file, not installed (or older) -> Android's own Package Installer
 */
class AppsTab(c: Context) : FrameLayout(c), Sel.Listener, Live {

    private class Row(
        val type: Int,
        val app: AppEntry? = null,
        val apk: ApkFile? = null,
        val title: String = "",
        val expandable: Boolean = false
    )

    private val rv = RecyclerView(c)
    private val adapter = Ad()
    private var users: List<AppEntry> = emptyList()
    private var systemRaw: List<ApplicationInfo> = emptyList()
    private var systems: List<AppEntry>? = null
    private var apks: List<ApkFile> = emptyList()
    private var rows: List<Row> = emptyList()
    private var showSystem = false
    private var loadedAt = 0L
    private var apksAt = 0L
    private var apksHadAccess = false
    private var appsVer = -1
    private var filesVer = -1
    private var gen = 0
    private var pendingInstall: String? = null
    private val px: Int by lazy { Ui.dp(56) }

    init {
        val lm = GridLayoutManager(c, 4)
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                if (position < rows.size && rows[position].type == HEADER) 4 else 1
        }
        rv.layoutManager = lm
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(Ui.dp(8), Ui.dp(4), Ui.dp(8), Ui.dp(96))
        addView(rv, LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    /** Called by the Sender screen whenever this tab becomes visible. */
    fun onShow() {
        resumeInstall()
        Files.watch()
        val now = System.currentTimeMillis()
        val access = Perms.hasStorage()
        val stale = now - loadedAt > 30_000L
        val appsChanged = Files.version(Files.KIND_APPS) != appsVer
        val apksStale = access &&
            (!apksHadAccess || now - apksAt > 30_000L || Files.version(Files.KIND_FILES) != filesVer)
        if (loadedAt == 0L || stale || appsChanged) load(access) else if (apksStale) loadApks(gen)
    }

    /** Debounced change: a package changed, or files (APKs) changed. Refreshes only while on screen. */
    override fun onLive(kind: Int) {
        if (!isShown) return
        val access = Perms.hasStorage()
        if (kind == Files.KIND_APPS) load(access)
        else if (access && (kind == Files.KIND_FILES || kind <= Types.AUDIO)) loadApks(gen)
    }

    private fun load(access: Boolean) {
        val g = ++gen
        appsVer = Files.version(Files.KIND_APPS)
        Files.pool.execute {
            val fast = try {
                Files.queryUserApps()
            } catch (e: Exception) {
                AppsFast(emptyList(), emptyList())
            }
            FlitApp.main.post {
                if (g == gen) {
                    users = fast.users
                    systemRaw = fast.systems
                    loadedAt = System.currentTimeMillis()
                    if (showSystem) loadSystems() else systems = null
                    rebuild()
                }
            }
        }
        if (access) loadApks(g) // runs alongside the app query, not after it
    }

    private fun loadApks(g: Int) {
        val v = Files.version(Files.KIND_FILES)
        Files.pool.execute {
            val list = try {
                Files.queryApks()
            } catch (e: Exception) {
                emptyList()
            }
            FlitApp.main.post {
                if (g == gen) {
                    apks = list
                    apksAt = System.currentTimeMillis()
                    apksHadAccess = true
                    filesVer = v
                    rebuild()
                }
            }
        }
    }

    /** Labels the system apps (only when their section is opened). Old rows stay until the new ones arrive. */
    private fun loadSystems() {
        val raw = systemRaw
        Files.pool.execute {
            val l = try {
                Files.systemEntries(raw)
            } catch (e: Exception) {
                emptyList()
            }
            FlitApp.main.post {
                if (raw === systemRaw) {
                    systems = l
                    rebuild()
                }
            }
        }
    }

    private fun rebuild() {
        val sys = systems
        val l = ArrayList<Row>(users.size + apks.size + (sys?.size ?: 0) + 2)
        for (a in users) l.add(Row(APP, app = a))
        if (apks.isNotEmpty()) {
            l.add(Row(HEADER, title = context.getString(R.string.apps_apk_files)))
            for (f in apks) l.add(Row(APK, apk = f))
        }
        if (systemRaw.isNotEmpty()) {
            l.add(Row(HEADER, title = context.getString(R.string.apps_system), expandable = true))
            if (showSystem && sys != null) for (a in sys) l.add(Row(APP, app = a))
        }
        rows = l
        adapter.notifyDataSetChanged()
    }

    override fun onChanged() {
        if (rows.isNotEmpty()) adapter.notifyItemRangeChanged(0, rows.size, PAY)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Sel.addListener(this)
        Files.addLive(this)
        onChanged()
    }

    override fun onDetachedFromWindow() {
        Sel.removeListener(this)
        Files.removeLive(this)
        super.onDetachedFromWindow()
    }

    private fun item(r: Row): Item? = r.app?.item() ?: r.apk?.item()

    private fun selected(r: Row): Boolean = when {
        r.app != null -> Sel.has(Sel.appKey(r.app.pkg))
        r.apk != null -> Sel.has(Sel.fileKey(r.apk.path))
        else -> false
    }

    // ---- Long-press: already installed / install ---------------------------------------

    private fun alreadyInstalled(a: android.app.Activity) {
        Dialogs.show(a, a.getString(R.string.apk_already_installed), null, a.getString(R.string.dialog_ok), null)
    }

    private fun longPress(r: Row) {
        val a = context.findActivity() ?: return
        if (r.app != null) {
            alreadyInstalled(a)
            return
        }
        val f = r.apk ?: return
        Files.pool.execute {
            // Package identity from the APK itself, never from its filename.
            val meta = Files.apkMeta(f.path)
            val installed = if (meta != null) Files.installedVersion(meta.first) else -1L
            FlitApp.main.post {
                when {
                    a.isFinishing || a.isDestroyed -> {}
                    meta == null -> Ui.toast(a, R.string.apk_cannot_install)
                    installed >= meta.second -> alreadyInstalled(a)
                    else -> startInstall(a, f.path)
                }
            }
        }
    }

    private fun startInstall(a: android.app.Activity, path: String) {
        if (Files.canInstall(a)) {
            if (!Files.install(a, path)) Ui.toast(a, R.string.apk_install_failed)
            return
        }
        // "Install unknown apps" is off for Flit: Android's own page for it. The installer
        // starts automatically when the user comes back with the permission granted.
        pendingInstall = path
        try {
            a.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName))
            )
        } catch (e: Exception) {
            pendingInstall = null
            Ui.toast(a, R.string.apk_install_failed)
        }
    }

    private fun resumeInstall() {
        val p = pendingInstall ?: return
        pendingInstall = null
        val a = context.findActivity() ?: return
        if (Files.canInstall(a) && !Files.install(a, p)) Ui.toast(a, R.string.apk_install_failed)
    }

    // ---- Adapter ---------------------------------------------------------------------------

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = rows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            if (viewType == HEADER) {
                val hv = HeaderCell(context)
                val h = VH(hv)
                hv.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION && rows[p].expandable) {
                        showSystem = !showSystem
                        if (showSystem && systems == null) loadSystems()
                        rebuild()
                    }
                }
                return h
            }
            val cell = AppCell(context)
            val h = VH(cell)
            cell.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) item(rows[p])?.let { Sel.toggle(it) }
            }
            // Returning true consumes the press, so a long-press never also selects.
            cell.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) longPress(rows[p])
                true
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = rows[position]
            if (r.type == HEADER) {
                val hv = h.v as HeaderCell
                hv.title.text = r.title
                hv.chev.visibility = if (r.expandable) View.VISIBLE else View.GONE
                hv.chev.rotation = if (showSystem) 90f else 0f
                hv.isClickable = r.expandable
                return
            }
            val cell = h.v as AppCell
            val app = r.app
            if (app != null) {
                cell.name.text = app.label
                cell.note.text = Types.size(app.size)
                cell.note.visibility = View.VISIBLE
                Thumbs.load(cell.icon, "app:${app.pkg}:${app.path}", cell.ph, 10) { Thumbs.appIcon(app.pkg, px) }
            } else {
                val f = r.apk!!
                cell.name.text = f.name.substringBeforeLast('.')
                cell.note.text = Types.size(f.size)
                cell.note.visibility = View.VISIBLE
                Thumbs.load(cell.icon, "apk:${f.path}:${f.size}", cell.ph, 10) { Thumbs.apkIcon(f.path, px) }
            }
            cell.mark(selected(r))
        }

        override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(h, position, payloads)
                return
            }
            val v = h.v
            if (v is AppCell) v.mark(selected(rows[position]))
        }
    }

    private companion object {
        const val HEADER = 0
        const val APP = 1
        const val APK = 2
        val PAY = Any()
    }
}