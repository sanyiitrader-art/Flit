package app.flit

import android.content.Context
import android.graphics.drawable.Drawable
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

/** App or APK cell: icon, name (2 lines), optional "Split APK" note, selection check at the top-right. */
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
 * then a collapsible (collapsed by default) System apps section.
 * Apps load on first show; APK files are scanned lazily afterwards and only with storage access.
 * Split-APK apps are marked, since their files are not independently installable.
 */
class AppsTab(c: Context) : FrameLayout(c), Sel.Listener {

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
    private var systems: List<AppEntry> = emptyList()
    private var apks: List<ApkFile> = emptyList()
    private var rows: List<Row> = emptyList()
    private var showSystem = false
    private var loadedAt = 0L
    private var apksAt = 0L
    private var apksHadAccess = false
    private var gen = 0
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
        val now = System.currentTimeMillis()
        val access = Perms.hasStorage()
        val stale = now - loadedAt > 30_000L
        val apksStale = access && (!apksHadAccess || now - apksAt > 30_000L)
        if (loadedAt == 0L || stale) load(access) else if (apksStale) loadApks()
    }

    private fun load(access: Boolean) {
        val g = ++gen
        FlitApp.io.execute {
            val all = try {
                Files.queryApps()
            } catch (e: Exception) {
                emptyList()
            }
            FlitApp.main.post {
                if (g == gen) {
                    users = all.filter { !it.system }
                    systems = all.filter { it.system }
                    loadedAt = System.currentTimeMillis()
                    rebuild()
                    if (access) loadApks()
                }
            }
        }
    }

    private fun loadApks() {
        val g = gen
        FlitApp.io.execute {
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
                    rebuild()
                }
            }
        }
    }

    private fun rebuild() {
        val l = ArrayList<Row>(users.size + apks.size + systems.size + 2)
        for (a in users) l.add(Row(APP, app = a))
        if (apks.isNotEmpty()) {
            l.add(Row(HEADER, title = context.getString(R.string.apps_apk_files)))
            for (f in apks) l.add(Row(APK, apk = f))
        }
        if (systems.isNotEmpty()) {
            l.add(Row(HEADER, title = context.getString(R.string.apps_system), expandable = true))
            if (showSystem) for (a in systems) l.add(Row(APP, app = a))
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
        onChanged()
    }

    override fun onDetachedFromWindow() {
        Sel.removeListener(this)
        super.onDetachedFromWindow()
    }

    private fun item(r: Row): Item? = r.app?.item() ?: r.apk?.item()

    private fun selected(r: Row): Boolean = when {
        r.app != null -> Sel.has(Sel.appKey(r.app.pkg))
        r.apk != null -> Sel.has(Sel.fileKey(r.apk.path))
        else -> false
    }

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
                cell.note.text = context.getString(R.string.apk_split)
                cell.note.visibility = if (app.splits.isNotEmpty()) View.VISIBLE else View.GONE
                Thumbs.load(cell.icon, "app:${app.pkg}", cell.ph, 10) { Thumbs.appIcon(app.pkg, px) }
            } else {
                val f = r.apk!!
                cell.name.text = f.name.substringBeforeLast('.')
                cell.note.visibility = View.GONE
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