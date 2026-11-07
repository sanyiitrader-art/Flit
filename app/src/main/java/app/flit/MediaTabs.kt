package app.flit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Finds the hosting Activity of a view context. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The only ViewHolder class used by Flit's lists. */
class VH(val v: View) : RecyclerView.ViewHolder(v)

/** Centered message with an optional button (empty states, missing access). */
class Notice(c: Context) : LinearLayout(c) {

    private val text = Ui.label(c, "", 14.5f, Ui.cText2)
    private val button = Pill(c, "", true)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        setPadding(Ui.dp(32), Ui.dp(16), Ui.dp(32), Ui.dp(16))
        text.gravity = Gravity.CENTER
        addView(text, Ui.lin(Ui.WRAP, Ui.WRAP))
        addView(button, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(16) })
        visibility = GONE
    }

    fun set(message: String, buttonLabel: String? = null, onClick: (() -> Unit)? = null) {
        text.text = message
        if (buttonLabel != null && onClick != null) {
            button.text = buttonLabel
            button.setOnClickListener { onClick() }
            button.visibility = VISIBLE
        } else {
            button.visibility = GONE
        }
        visibility = VISIBLE
    }

    fun hide() {
        visibility = GONE
    }
}

/**
 * Card row shared by Videos, Audios and History: thumbnail on the left (optional duration chip at
 * its bottom-right), title and detail text, then an optional checkbox, badge and action button.
 * Tapping the thumbnail is consumed unless the owner sets a listener on [thumbBox].
 */
class RowView(c: Context, phKind: Int) : LinearLayout(c) {

    val thumbBox = FrameLayout(c)
    val thumb = ImageView(c)
    val chip = TextView(c)
    val col = LinearLayout(c)
    val title = Ui.label(c, "", 14f, Ui.cText, false, 2)
    val sub = Ui.label(c, "", 12f, Ui.cText2, false, 1)
    val badge = Ui.label(c, "", 11f, Ui.cText2, true, 1)
    val check = CheckView(c)
    val action = Pill(c, "", true)
    var ph: Drawable = Icons.glyph(phKind, Ui.cText2)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).also {
            it.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4))
        }
        background = Ui.card(14)
        setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(12), Ui.dp(10))

        thumbBox.background = Ui.shape(Ui.cSurface2, 10)
        thumbBox.clipToOutline = true
        thumbBox.isClickable = true
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumbBox.addView(thumb, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        chip.textSize = 10f
        chip.setTextColor(Color.WHITE)
        chip.includeFontPadding = false
        chip.background = Ui.shape(Ui.alpha(Color.BLACK, 150), 6)
        chip.setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(4), Ui.dp(2))
        chip.visibility = GONE
        thumbBox.addView(
            chip,
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.END or Gravity.BOTTOM).also {
                it.setMargins(0, 0, Ui.dp(3), Ui.dp(3))
            }
        )
        addView(thumbBox, Ui.lin(Ui.dp(64), Ui.dp(64)))

        col.orientation = VERTICAL
        col.addView(title, Ui.lin(Ui.MATCH, Ui.WRAP))
        col.addView(sub, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(4) })
        addView(col, Ui.lin(0, Ui.WRAP, 1f).also { it.setMargins(Ui.dp(12), 0, Ui.dp(8), 0) })

        addView(badge, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(2) })
        addView(check, Ui.lin(Ui.dp(24), Ui.dp(24)))
        action.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8))
        action.textSize = 12f
        addView(action, Ui.lin(Ui.WRAP, Ui.WRAP))
        reset()
    }

    /** Hides every optional part. Does not touch the card's own click listener. */
    fun reset() {
        chip.visibility = GONE
        badge.visibility = GONE
        check.visibility = GONE
        action.visibility = GONE
        action.setOnClickListener(null)
        thumbBox.alpha = 1f
        col.alpha = 1f
        badge.tag = null
        sub.tag = null
        chip.tag = null
    }
}

/** Square image cell with the selection checkbox at the top-right. Tapping the image opens it. */
class ImageCell(c: Context) : FrameLayout(c) {

    val img = ImageView(c)
    val check = CheckView(c, true)
    val hit = FrameLayout(c)
    val ph: Drawable = Icons.glyph(Icons.IMAGE, Ui.cText2)

    init {
        layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).also { it.setMargins(1, 1, 1, 1) }
        img.background = Ui.shape(Ui.cSurface2, 0)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        addView(img, LayoutParams(Ui.MATCH, Ui.MATCH))
        hit.addView(check, LayoutParams(Ui.dp(24), Ui.dp(24), Gravity.CENTER))
        addView(hit, LayoutParams(Ui.dp(44), Ui.dp(44), Gravity.TOP or Gravity.END))
    }

    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, w)
    }
}

/**
 * Images (3-column grid), Videos and Audios (card lists) share this view. Media is read from
 * MediaStore with ONE query that streams its rows: the first 150 appear almost immediately and the
 * rest are appended in chunks.
 *
 * Live updates: when MediaStore reports a change for this kind and the tab is on screen, it reloads
 * by itself. A reload of an already-shown list is collected off-screen and swapped in at the end, so
 * the list never flickers or jumps. A change that happens while the tab is hidden is detected the
 * next time it is shown (and by a 5-minute safety net). Needs all-files access; without it a notice
 * with a grant button is shown instead.
 */
class MediaTab(c: Context, private val kind: Int) : FrameLayout(c), Sel.Listener, Live {

    private val rv = RecyclerView(c)
    private val notice = Notice(c)
    private val adapter = Ad()
    private var data: MutableList<Media> = ArrayList()
    private var staging: MutableList<Media>? = null
    private var gen = 0
    private var loaded = false
    private var loading = false
    private var firstPending = false
    private var loadedAt = 0L
    private var loadedVer = -1
    private var pendingReveal: String? = null
    private val px: Int by lazy { Ui.dp(if (kind == Types.IMAGE) 120 else 96) }

    init {
        rv.layoutManager = if (kind == Types.IMAGE) GridLayoutManager(c, 3) else LinearLayoutManager(c)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(if (kind == Types.IMAGE) Ui.dp(1) else 0, Ui.dp(4), if (kind == Types.IMAGE) Ui.dp(1) else 0, Ui.dp(96))
        addView(rv, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(notice, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
    }

    /** Called by the Sender screen whenever this tab becomes visible (or access may have changed). */
    fun onShow() {
        if (!Perms.hasStorage()) {
            gen++
            loaded = false
            loading = false
            loadedVer = -1
            staging = null
            if (data.isNotEmpty()) {
                data = ArrayList()
                adapter.notifyDataSetChanged()
            }
            notice.set(
                context.getString(R.string.perm_files_needed),
                context.getString(R.string.btn_grant)
            ) { context.findActivity()?.let { Perms.requestStorage(it) } }
            return
        }
        Files.watch()
        if (loading) return
        val now = System.currentTimeMillis()
        val changed = Files.version(kind) != loadedVer
        if (!loaded || changed || now - loadedAt > 300_000L) load() else updateNotice()
    }

    /** Share With: scrolls to [path] as soon as it is in the list (the list may still be loading). */
    fun reveal(path: String) {
        pendingReveal = path
        tryReveal()
    }

    private fun tryReveal() {
        val p = pendingReveal ?: return
        val i = data.indexOfFirst { it.path == p }
        if (i < 0) return
        pendingReveal = null
        rv.post {
            val lm = rv.layoutManager as? LinearLayoutManager
            if (lm != null) lm.scrollToPositionWithOffset(i, Ui.dp(24)) else rv.scrollToPosition(i)
        }
    }

    /** Debounced change from MediaStore. Reloads only if this tab is on screen. */
    override fun onLive(kind: Int) {
        if (kind == this.kind && isShown && !loading && Perms.hasStorage()) load()
    }

    private fun load() {
        val g = ++gen
        val ver = Files.version(kind)
        loading = true
        val silent = loaded && data.isNotEmpty()
        firstPending = !silent
        staging = if (silent) ArrayList() else null
        if (!silent) notice.hide()
        Files.pool.execute {
            try {
                Files.streamMedia(kind, { g != gen }) { chunk ->
                    FlitApp.main.post { if (g == gen) append(chunk) }
                }
            } catch (e: Exception) {
            }
            FlitApp.main.post {
                if (g == gen) {
                    val st = staging
                    if (st != null) {
                        staging = null
                        data = st
                        adapter.notifyDataSetChanged()
                    } else if (firstPending) { // the query returned nothing: replace any older rows
                        data = ArrayList()
                        adapter.notifyDataSetChanged()
                    }
                    firstPending = false
                    loading = false
                    loaded = true
                    loadedAt = System.currentTimeMillis()
                    loadedVer = ver
                    updateNotice()
                    tryReveal()
                    pendingReveal = null // not in the library (yet): do not jump on later reloads
                    // Something changed while we were loading: go again.
                    if (isShown && Files.version(kind) != ver) load()
                }
            }
        }
    }

    /** Main thread. A silent reload collects off-screen; otherwise the first chunk replaces the list. */
    private fun append(chunk: List<Media>) {
        val st = staging
        if (st != null) {
            st.addAll(chunk)
            return
        }
        if (firstPending) {
            firstPending = false
            data = ArrayList(chunk)
            adapter.notifyDataSetChanged()
            notice.hide()
        } else {
            val start = data.size
            data.addAll(chunk)
            adapter.notifyItemRangeInserted(start, chunk.size)
        }
        tryReveal()
    }

    private fun updateNotice() {
        if (loaded && data.isEmpty()) notice.set(context.getString(R.string.media_empty)) else notice.hide()
    }

    override fun onChanged() {
        if (data.isNotEmpty()) adapter.notifyItemRangeChanged(0, data.size, PAY)
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

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = data.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v: View = if (kind == Types.IMAGE) ImageCell(context)
            else RowView(context, if (kind == Types.VIDEO) Icons.FILM else Icons.NOTE)
            val h = VH(v)
            if (v is ImageCell) {
                v.hit.setOnClickListener { toggle(h) } // checkbox corner selects
                v.img.setOnClickListener { open(h) } // the picture opens the viewer
            } else {
                v.setOnClickListener { toggle(h) } // rest of the card selects
                (v as RowView).thumbBox.setOnClickListener { open(h) } // thumbnail opens the player
            }
            return h
        }

        private fun open(h: VH) {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < data.size) Open.file(context, data[p].path)
        }

        private fun toggle(h: VH) {
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION && p < data.size) Sel.toggle(data[p].item())
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val m = data[position]
            val v = h.v
            if (v is ImageCell) {
                Thumbs.load(v.img, "i:${m.path}:$px", v.ph, 36) { Thumbs.image(m.path, m.uri, px) }
                v.check.checked = Sel.has(m.key)
                return
            }
            val r = v as RowView
            r.reset()
            r.title.text = m.name
            r.check.visibility = View.VISIBLE
            r.check.checked = Sel.has(m.key)
            if (kind == Types.VIDEO) {
                r.sub.text = Types.size(m.size)
                if (m.duration > 0) {
                    r.chip.text = Types.duration(m.duration)
                    r.chip.visibility = View.VISIBLE
                }
                Thumbs.load(r.thumb, "v:${m.path}:$px", r.ph, 18) { Thumbs.video(m.path, m.uri, px) }
            } else {
                r.sub.text = Types.size(m.size) + " · " + Types.duration(m.duration)
                Thumbs.load(r.thumb, "a:${m.path}:$px", r.ph, 18) { Thumbs.audio(m.path, m.uri, px) }
            }
        }

        override fun onBindViewHolder(h: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isEmpty()) {
                super.onBindViewHolder(h, position, payloads)
                return
            }
            val sel = Sel.has(data[position].key)
            val v = h.v
            if (v is ImageCell) v.check.checked = sel else (v as RowView).check.checked = sel
        }
    }

    private companion object {
        val PAY = Any()
    }
}