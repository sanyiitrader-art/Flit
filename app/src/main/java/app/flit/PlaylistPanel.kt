package app.flit

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import kotlin.math.abs

/**
 * What the user ticked in the playlists. It stays while the app lives and is emptied when the media is
 * cleared (audio stopped, video player closed).
 */
object Picks {
    val audio = LinkedHashSet<String>()
    val video = LinkedHashSet<String>()
}

/**
 * Playlist of the video and audio players (every file of that kind on the phone). Rows: a selection box,
 * thumbnail / artwork, file name, duration. Tapping the box ticks the file; tapping anywhere else on the
 * row opens it. While something is ticked, a Play button appears in the header (between the title and the
 * X): it plays only the ticked files. [side] = a drawer from the right covering half the width (video,
 * landscape); otherwise a sheet from the bottom covering half the height. Closes with the X, a tap
 * outside, or a drag in the closing direction (right / down).
 */
class PlaylistPanel(
    c: Context,
    private val files: List<File>,
    private val current: Int,
    private val video: Boolean,
    private val side: Boolean,
    private val picks: MutableSet<String>,
    private val onPick: (Int) -> Unit,
    private val onPlay: (List<File>) -> Unit,
    private val onClosed: () -> Unit
) : FrameLayout(c) {

    private val panel = LinearLayout(c)
    private val rv = RecyclerView(c)
    private val play: TextView
    private val slop = ViewConfiguration.get(c).scaledTouchSlop
    private var closing = false
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private val px: Int by lazy { Ui.dp(56) }
    private val adapter = Ad()

    init {
        val scrim = View(c)
        scrim.setBackgroundColor(Ui.alpha(Color.BLACK, 90))
        scrim.setOnClickListener { close() }
        addView(scrim, LayoutParams(Ui.MATCH, Ui.MATCH))

        panel.orientation = LinearLayout.VERTICAL
        panel.isClickable = true
        panel.setBackgroundColor(0xF2161616.toInt())

        val head = LinearLayout(c)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(Ui.dp(16), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        head.addView(Ui.label(c, c.getString(R.string.v_playlist), 16f, Color.WHITE, true, 1), Ui.lin(0, Ui.WRAP, 1f))
        play = Ui.label(c, c.getString(R.string.v_play_selected), 14f, Ui.cOnAccent, true, 1)
        play.gravity = Gravity.CENTER
        play.setPadding(Ui.dp(18), Ui.dp(8), Ui.dp(18), Ui.dp(8))
        play.background = Ui.shape(PSlider.ACCENT, 100)
        play.setOnClickListener {
            val sel = files.filter { picks.contains(it.path) }
            if (sel.isNotEmpty()) {
                onPlay(sel)
                close()
            }
        }
        head.addView(play, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(4) })
        val x = ImageView(c)
        x.setImageDrawable(Icons.glyph(Icons.CLOSE, Color.WHITE))
        x.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
        x.contentDescription = c.getString(R.string.v_close)
        x.setOnClickListener { close() }
        head.addView(x, Ui.lin(Ui.dp(48), Ui.dp(48)))
        panel.addView(head, Ui.lin(Ui.MATCH, Ui.WRAP))

        rv.layoutManager = LinearLayoutManager(c)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, 0, 0, Ui.dp(12))
        panel.addView(rv, Ui.lin(Ui.MATCH, 0, 1f))
        addView(panel, LayoutParams(Ui.MATCH, Ui.MATCH, if (side) Gravity.END else Gravity.BOTTOM))
        updatePlay()
    }

    private fun updatePlay() {
        play.visibility = if (files.any { picks.contains(it.path) }) VISIBLE else GONE
    }

    /** Adds the panel on top of [parent] and slides it in. */
    fun open(parent: ViewGroup) {
        parent.addView(this, LayoutParams(Ui.MATCH, Ui.MATCH))
        panel.visibility = INVISIBLE
        post {
            val lp = panel.layoutParams as LayoutParams
            if (side) lp.width = width / 2 else lp.height = height / 2
            panel.layoutParams = lp
            panel.post {
                if (side) panel.translationX = panel.width.toFloat() else panel.translationY = panel.height.toFloat()
                panel.visibility = VISIBLE
                panel.animate().translationX(0f).translationY(0f).setDuration(180L).start()
                rv.scrollToPosition((current - 1).coerceAtLeast(0))
            }
        }
    }

    fun close() {
        if (closing) return
        closing = true
        val a = panel.animate().setDuration(160L)
        if (side) a.translationX(panel.width.toFloat()) else a.translationY(panel.height.toFloat())
        a.withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onClosed()
        }.start()
    }

    // ---- Drag to close ----------------------------------------------------------------------

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX
                val dy = e.y - downY
                val ok = if (side) dx > slop && dx > abs(dy) * 1.2f
                else dy > slop && dy > abs(dx) * 1.2f && !rv.canScrollVertically(-1)
                if (ok && !closing) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = vt ?: VelocityTracker.obtain().also { vt = it }
        t.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (side) panel.translationX = (e.x - downX).coerceAtLeast(0f)
                else panel.translationY = (e.y - downY).coerceAtLeast(0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    t.computeCurrentVelocity(1000)
                    val moved = if (side) panel.translationX else panel.translationY
                    val size = (if (side) panel.width else panel.height).toFloat()
                    val v = if (side) t.xVelocity else t.yVelocity
                    if (moved > size / 3f || v > 1200f) {
                        close()
                    } else {
                        panel.animate().translationX(0f).translationY(0f).setDuration(150L).start()
                    }
                }
                dragging = false
                t.recycle()
                vt = null
            }
        }
        return true
    }

    // ---- Rows -------------------------------------------------------------------------------

    private inner class Row(c: Context) : LinearLayout(c) {
        val box = SelBox(c)
        val hit = FrameLayout(c)
        val thumb = ImageView(c)
        val name = Ui.label(c, "", 14f, Color.WHITE, false, 2)
        val dur = Ui.label(c, "", 12f, Ui.alpha(Color.WHITE, 160), false, 1)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP)
            setPadding(Ui.dp(4), Ui.dp(2), Ui.dp(12), Ui.dp(2))
            hit.addView(box, FrameLayout.LayoutParams(Ui.dp(44), Ui.dp(56), Gravity.CENTER))
            addView(hit, Ui.lin(Ui.dp(44), Ui.dp(56)))
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.background = Ui.shape(0xFF2A2A2A.toInt(), 8)
            thumb.clipToOutline = true
            addView(thumb, Ui.lin(Ui.dp(56), Ui.dp(56)).also { it.leftMargin = Ui.dp(4) })
            val col = LinearLayout(c)
            col.orientation = VERTICAL
            col.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP))
            col.addView(dur, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(2) })
            addView(col, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(12) })
        }
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = files.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val r = Row(context)
            val h = VH(r)
            r.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    onPick(p)
                    close()
                }
            }
            r.hit.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) {
                    val path = files[p].path
                    if (!picks.add(path)) picks.remove(path)
                    r.box.checked = picks.contains(path)
                    updatePlay()
                }
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = h.v as Row
            val f = files[position]
            r.box.checked = picks.contains(f.path)
            r.name.text = f.name
            r.name.setTextColor(if (position == current) PSlider.ACCENT else Color.WHITE)
            r.dur.text = ""
            r.dur.tag = f.path
            Thumbs.duration(f.path) { d ->
                if (r.dur.tag == f.path && d > 0L) r.dur.text = clockText(d)
            }
            val ph = Icons.glyph(if (video) Icons.FILM else Icons.NOTE, Ui.alpha(Color.WHITE, 150))
            if (video) Thumbs.load(r.thumb, "v:${f.path}:$px", ph, 16) { Thumbs.video(f.path, null, px) }
            else Thumbs.load(r.thumb, "a:${f.path}:$px", ph, 16) { Thumbs.audio(f.path, null, px) }
        }
    }
}
