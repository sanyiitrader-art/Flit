package app.flit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Minimal PDF viewer on Android's own PdfRenderer (no library).
 *
 *  - Opens at once: only the page count and the first page's size are read up front; every other
 *    page is sized like the first until it is rendered (a different real size corrects itself).
 *  - Pages are rendered 1.5 times wider than the screen, so zooming up to 1.5x never needs a new
 *    render. Zooming further renders once, after you let go. Pages that scrolled off screen before
 *    their turn are skipped.
 *  - Pinch zoom follows your fingers smoothly (the whole page column is scaled on screen) and is
 *    committed when you let go, keeping the spot between your fingers where it was.
 *  - A tap shows or hides the top bar. A password-protected or damaged PDF goes to "Open with".
 */
class PdfScreen(act: MainActivity, private val path: String) : Screen(act) {

    private class Rendered(val bmp: Bitmap, val pw: Int, val ph: Int)

    private val bar = ViewerBar(act, path)
    private val pinch = PinchFrame(act)
    private val hs = HorizontalScrollView(act)
    private val rv = RecyclerView(act)
    private val adapter = Ad()
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "flit-pdf") }

    private var pfd: ParcelFileDescriptor? = null
    private var pr: PdfRenderer? = null
    private var sizes = ArrayList<IntArray>()
    private var zoom = 1f
    private var live = 1f
    private var pfx = 0f
    private var pfy = 0f
    private var started = false
    private var alive = true

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 6L / 1024L).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        setBackgroundColor(Ui.cSurface2)
        hs.isHorizontalScrollBarEnabled = false
        hs.isFillViewport = true
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, ViewerBar.height + Ui.dp(4), 0, Ui.dp(24))
        hs.addView(rv, LayoutParams(0, Ui.MATCH))
        pinch.addView(hs, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(pinch, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(bar, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))

        pinch.onTap = { bar.toggle() }
        pinch.onBegin = { fx, fy ->
            pfx = fx
            pfy = fy
            live = 1f
        }
        pinch.onScale = { f ->
            if (sizes.isNotEmpty()) {
                live = (live * f).coerceIn(1f / zoom, MAX_ZOOM / zoom)
                hs.pivotX = pfx
                hs.pivotY = pfy
                hs.scaleX = live
                hs.scaleY = live
            }
        }
        pinch.onEnd = { commit() }
    }

    override fun onShow() {
        if (started) return
        started = true
        exec.execute {
            try {
                val d = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                pfd = d
                val r = PdfRenderer(d) // throws for password-protected or damaged files
                pr = r
                val n = r.pageCount
                if (n <= 0) throw IllegalStateException("no pages")
                val first = r.openPage(0)
                val w = first.width.coerceAtLeast(1)
                val h = first.height.coerceAtLeast(1)
                first.close()
                val list = ArrayList<IntArray>(n)
                for (i in 0 until n) list.add(intArrayOf(w, h))
                FlitApp.main.post {
                    if (!alive) return@post
                    sizes = list
                    applyWidth()
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Throwable) {
                FlitApp.main.post { if (alive) Open.fallback(act, this, path) }
            }
        }
    }

    override fun onDestroyed() {
        alive = false
        exec.execute {
            try {
                pr?.close()
                pfd?.close()
            } catch (e: Exception) {
            }
        }
        exec.shutdown()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w != ow && sizes.isNotEmpty()) {
            applyWidth()
            adapter.notifyDataSetChanged()
        }
    }

    /** The page column is as wide as the screen times the zoom. */
    private fun applyWidth() {
        val lp = rv.layoutParams
        lp.width = (width * zoom).toInt().coerceAtLeast(1)
        rv.layoutParams = lp
    }

    /** Width the pages are rendered at: 1.5x the screen at least, up to a memory-safe limit. */
    private fun renderWidth(): Int = min((width * max(zoom, BASE_RENDER)).toInt(), MAX_RENDER).coerceAtLeast(1)

    /** End of a pinch: make the zoom real, keeping the content that was between the fingers in place. */
    private fun commit() {
        val ratio = live
        live = 1f
        hs.scaleX = 1f
        hs.scaleY = 1f
        if (sizes.isEmpty() || abs(ratio - 1f) < 0.02f) return
        val nz = (zoom * ratio).coerceIn(1f, MAX_ZOOM)
        val r = nz / zoom
        if (abs(r - 1f) < 0.005f) return
        val lm = rv.layoutManager as LinearLayoutManager
        val child = rv.findChildViewUnder(pfx + hs.scrollX, pfy)
        val pos = if (child != null) rv.getChildAdapterPosition(child) else lm.findFirstVisibleItemPosition()
        val frac = if (child != null && child.height > 0) ((pfy - child.top) / child.height).coerceIn(0f, 1f) else 0f
        val sx = hs.scrollX
        zoom = nz
        applyWidth()
        adapter.notifyDataSetChanged()
        if (pos >= 0 && pos < sizes.size) {
            val sz = sizes[pos]
            val newH = width * zoom * sz[1] / sz[0]
            lm.scrollToPositionWithOffset(pos, (pfy - frac * newH - rv.paddingTop).toInt())
        }
        hs.post { hs.scrollTo(((sx + pfx) * r - pfx).toInt().coerceAtLeast(0), 0) }
    }

    // ---- Pages ---------------------------------------------------------------------------------------

    private fun render(i: Int, w: Int): Rendered? {
        val r = pr ?: return null
        val p = r.openPage(i)
        return try {
            val pw = p.width.coerceAtLeast(1)
            val ph = p.height.coerceAtLeast(1)
            val h = (w.toFloat() * ph / pw).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Rendered(b, pw, ph)
        } finally {
            p.close()
        }
    }

    /** The widest picture of this page that is already rendered (shown stretched until the right one arrives). */
    private fun anyBitmap(position: Int): Bitmap? {
        var best: Bitmap? = null
        val prefix = "$position:"
        for ((k, v) in cache.snapshot()) {
            if (k.startsWith(prefix) && (best == null || v.width > best.width)) best = v
        }
        return best
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = sizes.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = ImageView(parent.context)
            v.setBackgroundColor(Color.WHITE)
            v.scaleType = ImageView.ScaleType.FIT_XY
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val img = h.v as ImageView
            val sz = sizes[position]
            val w = (width * zoom).toInt().coerceAtLeast(1)
            val ph = (w.toFloat() * sz[1] / sz[0]).toInt().coerceAtLeast(1)
            img.layoutParams = RecyclerView.LayoutParams(w, ph).also { it.bottomMargin = Ui.dp(6) }
            val rw = renderWidth()
            val key = "$position:$rw"
            img.tag = key
            val hit = cache.get(key)
            if (hit != null) {
                img.setImageBitmap(hit)
                return
            }
            img.setImageBitmap(anyBitmap(position)) // something right away; the proper render follows
            exec.execute {
                // The page may have scrolled away (and the view been reused) before its turn.
                if (!alive || img.tag != key) return@execute
                try {
                    val r = render(position, rw) ?: return@execute
                    cache.put(key, r.bmp)
                    FlitApp.main.post {
                        if (!alive) return@post
                        if (img.tag == key) img.setImageBitmap(r.bmp)
                        val s = sizes.getOrNull(position)
                        if (s != null && (s[0] != r.pw || s[1] != r.ph)) {
                            sizes[position] = intArrayOf(r.pw, r.ph)
                            adapter.notifyItemChanged(position)
                        }
                    }
                } catch (e: Throwable) {
                }
            }
        }
    }

    /** Reports taps and pinches; two-finger gestures are taken over, everything else passes through. */
    private class PinchFrame(c: Context) : FrameLayout(c) {

        var onTap: (() -> Unit)? = null
        var onBegin: ((Float, Float) -> Unit)? = null
        var onScale: ((Float) -> Unit)? = null
        var onEnd: (() -> Unit)? = null

        private val tap = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap?.invoke()
                return false
            }
        })

        private val det = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                this@PinchFrame.onBegin?.invoke(d.focusX, d.focusY)
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                this@PinchFrame.onScale?.invoke(d.scaleFactor)
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                this@PinchFrame.onEnd?.invoke()
            }
        })

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            tap.onTouchEvent(e)
            det.onTouchEvent(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.pointerCount > 1 || det.isInProgress) return true
            return super.onInterceptTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean = e.pointerCount > 1 || det.isInProgress
    }

    private companion object {
        const val MAX_ZOOM = 4f
        const val BASE_RENDER = 1.5f
        const val MAX_RENDER = 2400
    }
}
