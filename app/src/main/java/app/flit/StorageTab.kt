package app.flit

import android.content.Context
import android.os.FileObserver
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * Internal storage browser. Folders first, then files. Tapping a folder opens it; tapping a file's
 * icon opens the file, tapping the rest of its card or its checkbox selects it; its checkbox
 * selects the folder and every accessible file below it (expanded on a background thread).
 * Android/data and Android/obb are shown as restricted on Android 11+, never faked.
 *
 * Live updates: a FileObserver watches ONLY the folder that is open (and stops when the tab is
 * detached), and MediaStore changes act as a backup signal. Either one marks the listing dirty and
 * reloads it (debounced) while the tab is on screen. If the open folder is deleted, the browser
 * moves up to the nearest folder that still exists.
 */
class StorageTab(c: Context) : LinearLayout(c), Sel.Listener, Live {

    private var cur: File = Files.root
    private var entries: List<Entry> = emptyList()
    private var loaded = false
    private var loadedAt = 0L
    private var gen = 0
    private val busy = HashSet<String>()

    @Volatile
    private var dirty = false
    private var obs: FileObserver? = null
    private val refresh = Runnable { if (dirty && isShown && Perms.hasStorage()) load(false) }

    private val up: ImageView
    private val path = Ui.label(c, "", 14f, Ui.cText, true, 1)
    private val rv = RecyclerView(c)
    private val notice = Notice(c)
    private val adapter = Ad()
    private val px: Int by lazy { Ui.dp(44) }

    init {
        orientation = VERTICAL

        val head = LinearLayout(c)
        head.orientation = HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
        up = iconBtn(c, Icons.BACK, c.getString(R.string.cd_folder_up)) { up() }
        path.ellipsize = TextUtils.TruncateAt.START
        head.addView(up, Ui.lin(Ui.dp(44), Ui.dp(44)))
        head.addView(path, Ui.lin(0, Ui.WRAP, 1f))
        addView(head, Ui.lin(Ui.MATCH, Ui.dp(44)))

        val body = FrameLayout(c)
        rv.layoutManager = LinearLayoutManager(c)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, Ui.dp(4), 0, Ui.dp(96))
        body.addView(rv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        body.addView(notice, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        addView(body, Ui.lin(Ui.MATCH, 0, 1f))

        updateHeader()
    }

    /** Goes one folder up. Returns false when already at the storage root. */
    fun up(): Boolean {
        if (cur.path == Files.root.path) return false
        val p = cur.parentFile ?: return false
        cur = p
        load(true)
        return true
    }

    /** Called by the Sender screen whenever this tab becomes visible. */
    fun onShow() {
        if (!Perms.hasStorage()) {
            gen++
            loaded = false
            entries = emptyList()
            adapter.notifyDataSetChanged()
            notice.set(
                context.getString(R.string.perm_files_needed),
                context.getString(R.string.btn_grant)
            ) { context.findActivity()?.let { Perms.requestStorage(it) } }
            return
        }
        Files.watch()
        if (!loaded || dirty || System.currentTimeMillis() - loadedAt > 10_000L) load(false)
    }

    // ---- Live updates ------------------------------------------------------------------

    /** MediaStore backup signal: files changed somewhere. Folder-level events normally arrive first. */
    override fun onLive(kind: Int) {
        if (kind == Files.KIND_APPS) return
        dirty = true
        refresh.run()
    }

    @Suppress("DEPRECATION")
    private fun watchDir() {
        obs?.stopWatching()
        obs = null
        if (!Perms.hasStorage()) return
        val o = object : FileObserver(cur.path, MASK) {
            override fun onEvent(event: Int, name: String?) {
                dirty = true
                FlitApp.main.removeCallbacks(refresh)
                FlitApp.main.postDelayed(refresh, 600L)
            }
        }
        o.startWatching()
        obs = o
    }

    private fun stopWatching() {
        obs?.stopWatching()
        obs = null
        FlitApp.main.removeCallbacks(refresh)
    }

    private fun nearest(f: File): File {
        var x = f
        while (!x.exists() && x.path != Files.root.path) x = x.parentFile ?: break
        return x
    }

    // ---- Loading -----------------------------------------------------------------------

    private fun updateHeader() {
        val atRoot = cur.path == Files.root.path
        up.visibility = if (atRoot) View.GONE else View.VISIBLE
        path.text = if (atRoot) context.getString(R.string.cd_storage_tab)
        else cur.path.removePrefix(Files.root.path)
    }

    private fun load(scrollTop: Boolean) {
        val d = cur
        val g = ++gen
        dirty = false
        updateHeader()
        if (isAttachedToWindow) watchDir()
        FlitApp.io.execute {
            val list = try {
                Files.listDir(d)
            } catch (e: Exception) {
                null
            }
            val gone = list == null && !d.exists()
            FlitApp.main.post {
                if (g == gen) {
                    if (gone) {
                        // The open folder was deleted or moved: continue from the nearest one that exists.
                        cur = nearest(d)
                        load(true)
                    } else {
                        loaded = true
                        loadedAt = System.currentTimeMillis()
                        entries = list ?: emptyList()
                        adapter.notifyDataSetChanged()
                        if (scrollTop) rv.scrollToPosition(0)
                        when {
                            list == null -> notice.set(context.getString(R.string.folder_restricted))
                            list.isEmpty() -> notice.set(context.getString(R.string.folder_empty))
                            else -> notice.hide()
                        }
                    }
                }
            }
        }
    }

    override fun onChanged() {
        if (entries.isNotEmpty()) adapter.notifyItemRangeChanged(0, entries.size, PAY)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Sel.addListener(this)
        Files.addLive(this)
        if (loaded) watchDir()
        onChanged()
    }

    override fun onDetachedFromWindow() {
        Sel.removeListener(this)
        Files.removeLive(this)
        stopWatching()
        super.onDetachedFromWindow()
    }

    private fun fileItem(e: Entry): Item =
        Item(Sel.fileKey(e.path), e.name, e.size, e.path, Types.typeOf(e.name))

    private fun rowTap(e: Entry) {
        if (e.dir) {
            if (e.restricted) {
                Ui.toast(context, R.string.folder_restricted)
            } else {
                cur = File(e.path)
                load(true)
            }
        } else {
            Sel.toggle(fileItem(e))
        }
    }

    private fun checkTap(e: Entry) {
        if (!e.dir) {
            Sel.toggle(fileItem(e))
            return
        }
        if (Sel.isFolderSelected(e.path)) {
            Sel.removeFolder(e.path)
            return
        }
        if (!busy.add(e.path)) return
        onChanged()
        FlitApp.io.execute {
            val items = try {
                Files.expandFolder(File(e.path))
            } catch (ex: Exception) {
                emptyList()
            }
            FlitApp.main.post {
                busy.remove(e.path)
                Sel.addFolder(e.path, items)
            }
        }
    }

    private class FRow(c: Context) : LinearLayout(c) {
        val icon = ImageView(c)
        val name = Ui.label(c, "", 14f, Ui.cText, false, 2)
        val sub = Ui.label(c, "", 12f, Ui.cText2, false, 1)
        val hit = FrameLayout(c)
        val check = CheckView(c)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).also {
                it.setMargins(Ui.dp(12), Ui.dp(3), Ui.dp(12), Ui.dp(3))
            }
            background = Ui.card(14)
            setPadding(Ui.dp(10), Ui.dp(8), Ui.dp(2), Ui.dp(8))

            icon.background = Ui.shape(Ui.cSurface2, 10)
            icon.clipToOutline = true
            addView(icon, Ui.lin(Ui.dp(44), Ui.dp(44)))

            val col = LinearLayout(c)
            col.orientation = VERTICAL
            col.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP))
            col.addView(sub, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(2) })
            addView(col, Ui.lin(0, Ui.WRAP, 1f).also { it.setMargins(Ui.dp(12), 0, Ui.dp(4), 0) })

            hit.addView(check, FrameLayout.LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER))
            addView(hit, Ui.lin(Ui.dp(48), Ui.dp(44)))
        }
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = FRow(context)
            val h = VH(row)
            row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && p < entries.size) rowTap(entries[p])
            }
            row.hit.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && p < entries.size) checkTap(entries[p])
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val e = entries[position]
            val r = h.v as FRow
            r.name.text = e.name
            if (e.dir) {
                Thumbs.icon(r.icon, Icons.glyph(Icons.FOLDER, Ui.cText2), 10)
                r.sub.text = context.getString(R.string.folder_restricted)
                r.sub.visibility = if (e.restricted) View.VISIBLE else View.GONE
                r.alpha = if (e.restricted) 0.5f else 1f
                r.hit.visibility = if (e.restricted) View.INVISIBLE else View.VISIBLE
                // A folder's icon is not a button: tapping it behaves like tapping the row.
                r.icon.setOnClickListener(null)
                r.icon.isClickable = false
            } else {
                // A file's icon / thumbnail opens it (built-in viewer or Android's "Open with").
                r.icon.setOnClickListener { Open.file(context, e.path) }
                r.alpha = 1f
                r.sub.text = Types.size(e.size)
                r.sub.visibility = View.VISIBLE
                r.hit.visibility = View.VISIBLE
                val type = Types.typeOf(e.name)
                val ph = Icons.forFile(type, Types.ext(e.name))
                when (type) {
                    Types.IMAGE -> Thumbs.load(r.icon, "i:${e.path}:$px", ph, 10) { Thumbs.image(e.path, null, px) }
                    Types.VIDEO -> Thumbs.load(r.icon, "v:${e.path}:$px", ph, 10) { Thumbs.video(e.path, null, px) }
                    Types.APK -> Thumbs.load(r.icon, "k:${e.path}:${e.size}:s", ph, 10) { Thumbs.apkIcon(e.path, px) }
                    else -> Thumbs.icon(r.icon, ph, 10)
                }
            }
            bindCheck(r, e)
        }

        override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(h, position, payloads)
                return
            }
            bindCheck(h.v as FRow, entries[position])
        }

        private fun bindCheck(r: FRow, e: Entry) {
            r.check.checked = if (e.dir) Sel.isFolderSelected(e.path) else Sel.has(Sel.fileKey(e.path))
            r.hit.alpha = if (busy.contains(e.path)) 0.4f else 1f
        }
    }

    private companion object {
        val PAY = Any()

        // Structural changes and finished writes only (no per-write MODIFY events).
        const val MASK = FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or FileObserver.DELETE_SELF or
            FileObserver.MOVE_SELF
    }
}