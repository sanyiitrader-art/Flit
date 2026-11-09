package app.flit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/**
 * Built-in image viewer. Swipe (or drag) sideways to move to the next or previous picture: the
 * pictures are the ones of the Images tab (same order); if the file is not in the library, the images
 * of its folder. Decoding is done by Android's ImageDecoder (JPEG, PNG, WebP, GIF, BMP, HEIF, and AVIF on
 * Android 12+), plus SVG through AndroidSVG. If Android cannot decode a file the viewer closes and offers
 * it to other apps.
 *
 * HUD (back arrow + file name on top, Crop at the bottom) toggles with a tap anywhere on the image.
 * Pinch zooms but never below the fitted size (and while zoomed, one finger pans instead of swiping);
 * when not zoomed, pulling down closes. Crop is offered for JPEG, PNG and WebP.
 */
class ImageScreen(act: MainActivity, private val startPath: String) : Screen(act), ZoomImage.Host {

    private var files: List<File> = listOf(File(startPath))
    private val pager = ViewPager2(act)
    private val adapter = Pg()
    private val titleView: TextView = Ui.label(act, File(startPath).name, 16f, Color.WHITE, true, 1)
    private val top = LinearLayout(act)
    private val bottom = FrameLayout(act)
    private val cropBtn: ImageView
    private var hud = true
    private var alive = true
    private val animated = HashSet<String>()
    private val failed = HashSet<String>()

    private class Loaded(val d: Drawable, val w: Int, val h: Int, val animated: Boolean)

    private class PageVH(val zi: ZoomImage) : RecyclerView.ViewHolder(zi) {
        var path = ""
    }

    init {
        setBackgroundColor(Color.BLACK)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                val f = files.getOrNull(position) ?: return
                titleView.text = f.name
                updateCrop()
                if (failed.contains(f.path)) Open.fallback(act, this@ImageScreen, f.path)
            }
        })
        addView(pager, LayoutParams(Ui.MATCH, Ui.MATCH))

        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(16), Ui.dp(14))
        top.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.alpha(Color.BLACK, 170), 0)
        )
        top.addView(
            whiteBtn(act, { Icons.glyph(Icons.BACK, Color.WHITE) }, act.getString(R.string.v_back)) { act.pop() },
            Ui.lin(Ui.dp(48), Ui.dp(48))
        )
        top.addView(titleView, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) })
        addView(top, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))

        bottom.setPadding(0, Ui.dp(14), 0, Ui.dp(8))
        bottom.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.alpha(Color.BLACK, 170), 0)
        )
        cropBtn = whiteBtn(act, { PIcons.get(PIcons.CROP, Color.WHITE) }, act.getString(R.string.v_crop)) { openCrop() }
        cropBtn.visibility = GONE
        bottom.addView(cropBtn, LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.CENTER_HORIZONTAL))
        addView(bottom, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
        loadLibrary()
    }

    private fun whiteBtn(c: Context, icon: () -> Drawable, desc: String, onClick: () -> Unit): ImageView {
        val v = ImageView(c)
        v.setImageDrawable(icon())
        v.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
        v.background = Ui.ripple(null, 24)
        v.contentDescription = desc
        v.setOnClickListener { onClick() }
        return v
    }

    override fun onShow() {
        act.viewerMode(true)
    }

    override fun onDestroyed() {
        alive = false
        act.viewerMode(false)
    }

    private fun currentPath(): String? = files.getOrNull(pager.currentItem)?.path

    private fun cropable(path: String): Boolean =
        Types.ext(File(path).name) in setOf("jpg", "jpeg", "png", "webp") && !animated.contains(path)

    private fun updateCrop() {
        val p = currentPath()
        cropBtn.visibility = if (p != null && cropable(p)) VISIBLE else GONE
    }

    // ---- The pictures (Images tab order) ---------------------------------------------------------------

    private fun loadLibrary() {
        Files.pool.execute {
            val paths = ArrayList<String>()
            val seen = HashSet<String>()
            try {
                Files.streamMedia(Types.IMAGE, { false }) { list ->
                    for (m in list) if (seen.add(m.path)) paths.add(m.path)
                }
            } catch (e: Exception) {
            }
            var list: List<File> = paths.map { File(it) }.filter { Open.kind(it.path) == Open.K_IMAGE && it.isFile }
            if (list.none { it.path == startPath }) list = Open.siblings(startPath)
            FlitApp.main.post { applyLibrary(list) }
        }
    }

    private fun applyLibrary(list: List<File>) {
        if (!alive || list.size < 2 || files.size != 1) return
        val idx = list.indexOfFirst { it.path == startPath }
        if (idx < 0) return
        // The picture on screen stays; the others are added around it.
        files = list
        if (idx > 0) adapter.notifyItemRangeInserted(0, idx)
        if (idx < list.size - 1) adapter.notifyItemRangeInserted(idx + 1, list.size - idx - 1)
        pager.setCurrentItem(idx, false)
    }

    // ---- Pages -----------------------------------------------------------------------------------------------

    private inner class Pg : RecyclerView.Adapter<PageVH>() {

        override fun getItemCount(): Int = files.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val z = ZoomImage(parent.context)
            z.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            z.host = this@ImageScreen
            return PageVH(z)
        }

        override fun onBindViewHolder(h: PageVH, position: Int) {
            val path = files[position].path
            h.path = path
            h.zi.setImage(null, 0, 0)
            h.zi.translationY = 0f
            FlitApp.io.execute {
                try {
                    val l = decode(File(path))
                    FlitApp.main.post {
                        if (!alive || h.path != path) return@post
                        h.zi.setImage(l.d, l.w, l.h)
                        if (l.animated) animated.add(path)
                        if (currentPath() == path) updateCrop()
                    }
                } catch (e: Throwable) {
                    FlitApp.main.post {
                        if (!alive) return@post
                        failed.add(path)
                        if (currentPath() == path) Open.fallback(act, this@ImageScreen, path)
                    }
                }
            }
        }

        override fun onViewRecycled(h: PageVH) {
            h.zi.setImage(null, 0, 0)
            h.path = ""
        }
    }

    private fun decode(f: File): Loaded {
        if (Types.ext(f.name) == "svg") return decodeSvg(f)
        var w = 0
        var h = 0
        val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
            w = info.size.width
            h = info.size.height
            val m = max(w, h)
            if (m > MAX_SIDE) {
                val k = MAX_SIDE.toFloat() / m
                w = (w * k).toInt().coerceAtLeast(1)
                h = (h * k).toInt().coerceAtLeast(1)
                dec.setTargetSize(w, h)
            }
        }
        return Loaded(d, w, h, d is AnimatedImageDrawable)
    }

    private fun decodeSvg(f: File): Loaded {
        val svg = FileInputStream(f).use { SVG.getFromInputStream(it) }
        var w = svg.documentWidth
        var h = svg.documentHeight
        if (w <= 0f || h <= 0f) {
            val vb = svg.documentViewBox
            if (vb != null && vb.width() > 0f && vb.height() > 0f) {
                w = vb.width()
                h = vb.height()
            } else {
                w = 1024f
                h = 1024f
            }
        }
        val k = SVG_SIDE / max(w, h)
        val tw = (w * k).toInt().coerceAtLeast(1)
        val th = (h * k).toInt().coerceAtLeast(1)
        svg.documentWidth = tw.toFloat()
        svg.documentHeight = th.toFloat()
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp))
        return Loaded(BitmapDrawable(act.resources, bmp), tw, th, false)
    }

    // ---- HUD, close gesture, crop ---------------------------------------------------------------------

    override fun onTap() {
        setHud(!hud)
    }

    private fun setHud(on: Boolean) {
        hud = on
        for (v in arrayOf<View>(top, bottom)) {
            if (v === bottom && cropBtn.visibility != VISIBLE) continue
            v.animate().cancel()
            if (on) v.visibility = VISIBLE
            v.animate().alpha(if (on) 1f else 0f).setDuration(150L).withEndAction {
                if (!hud) v.visibility = GONE
            }.start()
        }
    }

    override fun onDrag(dy: Float) {
        pager.translationY = dy
        val a = (1f - dy / (height * 0.6f)).coerceIn(0.2f, 1f)
        top.alpha = if (hud) a else 0f
        bottom.alpha = if (hud) a else 0f
    }

    override fun onDragEnd(dy: Float, vy: Float) {
        if (dy > Ui.dpf(120) || vy > 1500f) {
            pager.animate().translationY(height.toFloat()).setDuration(160L).withEndAction {
                if (alive && act.top() === this) act.pop()
            }.start()
        } else {
            pager.animate().translationY(0f).setDuration(160L).start()
            top.alpha = if (hud) 1f else 0f
            bottom.alpha = if (hud) 1f else 0f
        }
    }

    private fun openCrop() {
        val p = currentPath() ?: return
        if (!cropable(p)) return
        val pos = pager.currentItem
        act.push(CropScreen(act, p) { overwritten ->
            if (overwritten && alive) adapter.notifyItemChanged(pos)
        })
    }

    private companion object {
        const val MAX_SIDE = 3072
        const val SVG_SIDE = 2048f
    }
}
