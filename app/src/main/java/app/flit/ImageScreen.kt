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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.caverock.androidsvg.SVG
import java.io.File
import java.io.FileInputStream
import kotlin.math.max

/**
 * Built-in image viewer. Decoding is done by Android's ImageDecoder (JPEG, PNG, WebP, GIF, BMP,
 * HEIF, and AVIF on Android 12+), plus SVG through AndroidSVG. If Android cannot decode the file
 * the viewer closes and offers it to other apps ("Open with").
 *
 * HUD (back arrow + file name on top, Crop at the bottom) toggles with a tap anywhere on the
 * image. Pinch zooms but never below the fitted size; when not zoomed, pulling down closes.
 * Crop is offered for JPEG, PNG and WebP.
 */
class ImageScreen(act: MainActivity, private val path: String) : Screen(act), ZoomImage.Host {

    private val zoom = ZoomImage(act)
    private val top = LinearLayout(act)
    private val bottom = FrameLayout(act)
    private val cropBtn: ImageView
    private var hud = true
    private var alive = true
    private val cropable = Types.ext(File(path).name) in setOf("jpg", "jpeg", "png", "webp")

    private class Loaded(val d: Drawable, val w: Int, val h: Int, val animated: Boolean)

    init {
        setBackgroundColor(Color.BLACK)
        zoom.host = this
        addView(zoom, LayoutParams(Ui.MATCH, Ui.MATCH))

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
        top.addView(
            Ui.label(act, File(path).name, 16f, Color.WHITE, true, 1),
            Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) }
        )
        addView(top, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))

        bottom.setPadding(0, Ui.dp(14), 0, Ui.dp(8))
        bottom.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.alpha(Color.BLACK, 170), 0)
        )
        cropBtn = whiteBtn(act, { PIcons.get(PIcons.CROP, Color.WHITE) }, act.getString(R.string.v_crop)) { openCrop() }
        cropBtn.visibility = GONE
        bottom.addView(cropBtn, LayoutParams(Ui.dp(52), Ui.dp(52), Gravity.CENTER_HORIZONTAL))
        addView(bottom, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))
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
        if (alive && !loaded && !loading) load()
    }

    override fun onDestroyed() {
        alive = false
        zoom.setImage(null, 0, 0)
        act.viewerMode(false)
    }

    // ---- Loading ---------------------------------------------------------------------------

    private var loaded = false
    private var loading = false

    private fun load() {
        loading = true
        FlitApp.io.execute {
            try {
                val l = decode()
                FlitApp.main.post { shown(l) }
            } catch (e: Throwable) {
                FlitApp.main.post { failed() }
            }
        }
    }

    private fun shown(l: Loaded) {
        loading = false
        if (!alive) return
        loaded = true
        zoom.setImage(l.d, l.w, l.h)
        cropBtn.visibility = if (cropable && !l.animated) VISIBLE else GONE
    }

    private fun failed() {
        loading = false
        if (alive) Open.fallback(act, this, path)
    }

    private fun decode(): Loaded {
        val f = File(path)
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

    // ---- HUD, close gesture, crop ---------------------------------------------------------

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
        zoom.translationY = dy
        val a = (1f - dy / (height * 0.6f)).coerceIn(0.2f, 1f)
        top.alpha = if (hud) a else 0f
        bottom.alpha = if (hud) a else 0f
    }

    override fun onDragEnd(dy: Float, vy: Float) {
        if (dy > Ui.dpf(120) || vy > 1500f) {
            zoom.animate().translationY(height.toFloat()).setDuration(160L).withEndAction {
                if (alive && act.top() === this) act.pop()
            }.start()
        } else {
            zoom.animate().translationY(0f).setDuration(160L).start()
            top.alpha = if (hud) 1f else 0f
            bottom.alpha = if (hud) 1f else 0f
        }
    }

    private fun openCrop() {
        if (!cropable) return
        act.push(CropScreen(act, path) { overwritten ->
            if (overwritten && alive) {
                loaded = false
                if (!loading) load()
            }
        })
    }

    private companion object {
        const val MAX_SIDE = 4096
        const val SVG_SIDE = 2048f
    }
}
