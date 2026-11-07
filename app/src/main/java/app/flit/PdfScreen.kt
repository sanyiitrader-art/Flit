package app.flit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.Gravity
import android.view.GestureDetector
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
import kotlin.math.min

/**
 * Minimal PDF viewer on Android's own PdfRenderer (no library): vertical scrolling through the
 * pages, a tap shows or hides the top bar. Pinch-to-zoom is live: the pages change size with every
 * movement of your fingers (using the sharpest picture already rendered), and are re-rendered sharp
 * as soon as you pause or let go. A password-protected or damaged PDF is offered to other apps.
 */
class PdfScreen(act: MainActivity, private val path: String) : Screen(act) {

    private val bar = ViewerBar(act, path)
    private val pinch = PinchFrame(act)
    private val hs = HorizontalScrollView(act)
    private val rv = RecyclerView(act)
    private val adapter = Ad()
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "flit-pdf") }

    private var pfd: ParcelFileDescriptor? = null
    private var pr: PdfRenderer? = null
    private var sizes: List<IntArray> = emptyList()
    private var zoom = 1f
    private var pinching = false
    private var started = false
    private var alive = true

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L / 1024L).toInt().coerceAtLeast(8 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** Re-renders the pages on screen at the current zoom (after a pause in the pinch, or its end). */
    private val sharpen = Runnable {
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i)
            val h = rv.getChildViewHolder(c)
            val p = h.bindingAdapterPosition
            if (p != RecyclerView.NO_POSITION) request(c as ImageView, p)
        }
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
        pinch.onBegin = { pinching = true }
        pinch.onScale = { f, fx, fy -> zoomBy(f, fx, fy) }
        pinch.onEnd = {
            pinching = false
            removeCallbacks(sharpen)
            post(sharpen)
        }
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
                val list = ArrayList<IntArray>(r.pageCount)
                for (i in 0 until r.pageCount) {
                    val p = r.openPage(i)
                    list.add(intArrayOf(p.width.coerceAtLeast(1), p.height.coerceAtLeast(1)))
                    p.close()
                }
                if (list.isEmpty()) throw IllegalStateException("no pages")
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
        removeCallbacks(sharpen)
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
        if (w != ow) {
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

    /** One step of the pinch: change the real size now, keeping the point between the fingers in place. */
    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        if (sizes.isEmpty()) return
        val nz = (zoom * f).coerceIn(1f, MAX_ZOOM)
        val ratio = nz / zoom
        if (ratio == 1f) return
        val lm = rv.layoutManager as LinearLayoutManager
        val first = lm.findFirstVisibleItemPosition()
        val off = lm.findViewByPosition(first)?.top ?: 0
        val sx = hs.scrollX
        zoom = nz
        applyWidth()
        adapter.notifyDataSetChanged()
        if (first >= 0) lm.scrollToPositionWithOffset(first, (fy + (off - fy) * ratio).toInt())
        hs.post { hs.scrollTo(((sx + fx) * ratio - fx).toInt().coerceAtLeast(0), 0) } // after the new width is laid out
        removeCallbacks(sharpen)
        postDelayed(sharpen, 180L)
    }

    // ---- Pages ---------------------------------------------------------------------------------------

    private fun render(i: Int, w: Int): Bitmap? {
        val r = pr ?: return null
        val p = r.openPage(i)
        return try {
            val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            b
        } finally {
            p.close()
        }
    }

    /** The widest picture of this page that is already rendered (shown stretched until a sharp one arrives). */
    private fun anyBitmap(position: Int): Bitmap? {
        var best: Bitmap? = null
        val prefix = "$position:"
        for ((k, v) in cache.snapshot()) {
            if (k.startsWith(prefix) && (best == null || v.width > best.width)) best = v
        }
        return best
    }

    /** Shows the sharp picture if there is one, otherwise a stretched one, and renders the sharp one. */
    private fun request(img: ImageView, position: Int) {
        val w = (width * zoom).toInt().coerceAtLeast(1)
        val rw = min(w, MAX_RENDER)
        val key = "$position:$rw"
        img.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            img.setImageBitmap(hit)
            return
        }
        if (img.drawable == null) img.setImageBitmap(anyBitmap(position))
        exec.execute {
            try {
                val b = render(position, rw) ?: return@execute
                cache.put(key, b)
                FlitApp.main.post { if (alive && img.tag == key) img.setImageBitmap(b) }
            } catch (e: Throwable) {
            }
        }
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
            val key = "$position:${min(w, MAX_RENDER)}"
            val hit = cache.get(key)
            img.tag = key
            if (hit != null) {
                img.setImageBitmap(hit)
                return
            }
            // Something is on screen at once (stretched if need be); the sharp render follows.
            img.setImageBitmap(anyBitmap(position))
            if (!pinching) request(img, position)
        }
    }

    /** Reports taps and pinches; two-finger gestures are taken over, everything else passes through. */
    private class PinchFrame(c: Context) : FrameLayout(c) {

        var onTap: (() -> Unit)? = null
        var onBegin: (() -> Unit)? = null
        var onScale: ((Float, Float, Float) -> Unit)? = null
        var onEnd: (() -> Unit)? = null

        private val tap = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap?.invoke()
                return false
            }
        })

        private val det = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
                this@PinchFrame.onBegin?.invoke()
                return true
            }

            override fun onScale(d: ScaleGestureDetector): Boolean {
                this@PinchFrame.onScale?.invoke(d.scaleFactor, d.focusX, d.focusY)
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
        const val MAX_RENDER = 2400
    }
}
