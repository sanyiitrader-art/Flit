package app.flit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/**
 * Crop screen for JPEG, PNG and WebP. Android has no dependable system crop screen, so this is a
 * single simple one: drag the box or its corners and edges. Revert (top-left) resets the box to the
 * whole image, Save (top-right) overwrites the original and its arrow offers "Save as copy",
 * Cancel (bottom-right) leaves without changes. The crop is cut from the full-resolution file.
 */
class CropScreen(act: MainActivity, private val path: String, private val onDone: (Boolean) -> Unit) : Screen(act) {

    private var crop: CropView? = null
    private var srcW = 0
    private var srcH = 0
    private var alive = true
    private var saving = false
    private val scrim = View(act)
    private val menu = TextView(act)

    init {
        setBackgroundColor(Color.BLACK)

        // Top bar: Revert (left), Save split button (right).
        val revert = textBtn(act, act.getString(R.string.v_crop_revert)) { crop?.reset() }
        addView(revert, LayoutParams(Ui.WRAP, Ui.dp(48), Gravity.TOP or Gravity.START).also {
            it.setMargins(Ui.dp(8), Ui.dp(6), 0, 0)
        })

        val split = LinearLayout(act)
        split.orientation = LinearLayout.HORIZONTAL
        split.gravity = Gravity.CENTER_VERTICAL
        split.background = Ui.shape(Ui.cAccent, 100)
        val save = Ui.label(act, act.getString(R.string.v_crop_save), 14f, Ui.cOnAccent, true)
        save.setPadding(Ui.dp(18), Ui.dp(10), Ui.dp(14), Ui.dp(10))
        save.setOnClickListener { save(false) }
        val line = View(act)
        line.setBackgroundColor(Ui.alpha(Ui.cOnAccent, 110))
        val arrow = ImageView(act)
        arrow.setImageDrawable(Icons.glyph(Icons.DOWN, Ui.cOnAccent))
        arrow.setPadding(Ui.dp(8), Ui.dp(10), Ui.dp(10), Ui.dp(10))
        arrow.setOnClickListener { showMenu(true) }
        split.addView(save, Ui.lin(Ui.WRAP, Ui.WRAP))
        split.addView(line, Ui.lin(Ui.dp(1), Ui.dp(22)))
        split.addView(arrow, Ui.lin(Ui.dp(36), Ui.dp(38)))
        addView(split, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).also {
            it.setMargins(0, Ui.dp(10), Ui.dp(12), 0)
        })

        // Bottom-right: Cancel.
        val cancel = textBtn(act, act.getString(R.string.v_crop_cancel)) { act.pop() }
        addView(cancel, LayoutParams(Ui.WRAP, Ui.dp(48), Gravity.BOTTOM or Gravity.END).also {
            it.setMargins(0, 0, Ui.dp(8), Ui.dp(6))
        })

        // "Save as copy" pops up under the split button.
        scrim.visibility = GONE
        scrim.setOnClickListener { showMenu(false) }
        addView(scrim, LayoutParams(Ui.MATCH, Ui.MATCH))
        menu.text = act.getString(R.string.v_crop_copy)
        menu.setTextColor(Ui.cText)
        menu.textSize = 14f
        menu.typeface = android.graphics.Typeface.DEFAULT_BOLD
        menu.setPadding(Ui.dp(18), Ui.dp(12), Ui.dp(18), Ui.dp(12))
        menu.background = Ui.shape(Ui.cSurface2, 12, Ui.cBorder, 1)
        menu.visibility = GONE
        menu.setOnClickListener {
            showMenu(false)
            save(true)
        }
        addView(menu, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.END).also {
            it.setMargins(0, Ui.dp(56), Ui.dp(12), 0)
        })
    }

    private fun textBtn(c: Context, text: String, onClick: () -> Unit): TextView {
        val t = Ui.label(c, text, 15f, Color.WHITE, true)
        t.gravity = Gravity.CENTER
        t.setPadding(Ui.dp(14), 0, Ui.dp(14), 0)
        t.background = Ui.ripple(null, 24)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun showMenu(on: Boolean) {
        scrim.visibility = if (on) VISIBLE else GONE
        menu.visibility = if (on) VISIBLE else GONE
    }

    override fun onShow() {
        act.viewerMode(true)
        if (crop == null) load()
    }

    override fun onBack(): Boolean {
        if (menu.visibility == VISIBLE) {
            showMenu(false)
            return true
        }
        return false
    }

    override fun onDestroyed() {
        alive = false
    }

    // ---- Loading ---------------------------------------------------------------------------

    private fun load() {
        FlitApp.io.execute {
            try {
                var w = 0
                var h = 0
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { dec, info, _ ->
                    w = info.size.width
                    h = info.size.height
                    val m = max(w, h)
                    if (m > SHOW_SIDE) {
                        val k = SHOW_SIDE.toFloat() / m
                        dec.setTargetSize((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                    }
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                FlitApp.main.post {
                    if (!alive) return@post
                    srcW = w
                    srcH = h
                    val cv = CropView(act, bmp)
                    crop = cv
                    addView(cv, 0, LayoutParams(Ui.MATCH, Ui.MATCH).also {
                        it.setMargins(0, Ui.dp(60), 0, Ui.dp(60))
                    })
                }
            } catch (e: Throwable) {
                FlitApp.main.post {
                    if (alive) {
                        Ui.toast(act, R.string.v_crop_failed)
                        act.pop()
                    }
                }
            }
        }
    }

    // ---- Saving ----------------------------------------------------------------------------

    private fun save(asCopy: Boolean) {
        val cv = crop ?: return
        if (saving) return
        val r = cv.region(srcW, srcH)
        if (r.width() < 1 || r.height() < 1) return
        saving = true
        alpha = 0.6f
        FlitApp.io.execute {
            var ok = false
            try {
                write(r, asCopy)
                ok = true
            } catch (e: Throwable) {
            }
            FlitApp.main.post {
                saving = false
                alpha = 1f
                if (!ok) {
                    Ui.toast(act, R.string.v_crop_failed)
                } else {
                    Ui.toast(act, if (asCopy) R.string.v_crop_copy_saved else R.string.v_crop_saved)
                    if (alive) act.pop()
                    onDone(!asCopy)
                }
            }
        }
    }

    /** Cuts [r] out of the full-resolution file and writes it (through a temp file, then rename). */
    private fun write(r: Rect, asCopy: Boolean) {
        val src = File(path)
        val ext = Types.ext(src.name)
        val dest = if (asCopy) unique(src, ext) else src
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(src)) { dec, _, _ ->
            dec.setCrop(r)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val tmp = File(dest.parentFile, ".flit_crop_${System.nanoTime()}")
        try {
            val fmt = when (ext) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            FileOutputStream(tmp).use { out ->
                if (!bmp.compress(fmt, 95, out)) throw IOException("compress")
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) throw IOException("rename")
        } finally {
            tmp.delete()
            bmp.recycle()
        }
        try {
            MediaScannerConnection.scanFile(FlitApp.app, arrayOf(dest.path), null, null)
        } catch (e: Exception) {
        }
    }

    private fun unique(src: File, ext: String): File {
        val base = src.name.removeSuffix("." + src.name.substringAfterLast('.'))
        var f = File(src.parentFile, "${base}_cropped.$ext")
        var i = 1
        while (f.exists()) {
            f = File(src.parentFile, "${base}_cropped ($i).$ext")
            i++
        }
        return f
    }

    // ---- The crop box ----------------------------------------------------------------------

    private class CropView(c: Context, private val bmp: Bitmap) : View(c) {

        private val img = RectF()
        private val box = RectF()
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val minSize = Ui.dpf(56)
        private val grab = Ui.dpf(30)
        private var mode = NONE
        private var lx = 0f
        private var ly = 0f

        fun reset() {
            box.set(img)
            invalidate()
        }

        /** The box in source-image pixels. */
        fun region(srcW: Int, srcH: Int): Rect {
            val sx = srcW / img.width()
            val sy = srcH / img.height()
            val l = ((box.left - img.left) * sx).toInt().coerceIn(0, srcW - 1)
            val t = ((box.top - img.top) * sy).toInt().coerceIn(0, srcH - 1)
            val r = ((box.right - img.left) * sx).toInt().coerceIn(l + 1, srcW)
            val b = ((box.bottom - img.top) * sy).toInt().coerceIn(t + 1, srcH)
            return Rect(l, t, r, b)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            val pad = Ui.dpf(20)
            val aw = w - 2 * pad
            val ah = h - 2 * pad
            val s = min(aw / bmp.width, ah / bmp.height)
            val iw = bmp.width * s
            val ih = bmp.height * s
            img.set((w - iw) / 2f, (h - ih) / 2f, (w + iw) / 2f, (h + ih) / 2f)
            box.set(img)
        }

        override fun onDraw(c: Canvas) {
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawBitmap(bmp, null, img, p)

            p.color = Ui.alpha(Color.BLACK, 150)
            c.drawRect(img.left, img.top, img.right, box.top, p)
            c.drawRect(img.left, box.bottom, img.right, img.bottom, p)
            c.drawRect(img.left, box.top, box.left, box.bottom, p)
            c.drawRect(box.right, box.top, img.right, box.bottom, p)

            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.dpf(1)
            p.color = Ui.alpha(Color.WHITE, 90)
            for (i in 1..2) {
                val x = box.left + box.width() * i / 3f
                val y = box.top + box.height() * i / 3f
                c.drawLine(x, box.top, x, box.bottom, p)
                c.drawLine(box.left, y, box.right, y, p)
            }
            p.strokeWidth = Ui.dpf(1.5f)
            p.color = Color.WHITE
            c.drawRect(box, p)

            // Corner brackets.
            p.strokeWidth = Ui.dpf(3)
            val k = Ui.dpf(18)
            c.drawLine(box.left, box.top, box.left + k, box.top, p)
            c.drawLine(box.left, box.top, box.left, box.top + k, p)
            c.drawLine(box.right, box.top, box.right - k, box.top, p)
            c.drawLine(box.right, box.top, box.right, box.top + k, p)
            c.drawLine(box.left, box.bottom, box.left + k, box.bottom, p)
            c.drawLine(box.left, box.bottom, box.left, box.bottom - k, p)
            c.drawLine(box.right, box.bottom, box.right - k, box.bottom, p)
            c.drawLine(box.right, box.bottom, box.right, box.bottom - k, p)
        }

        private fun near(a: Float, b: Float) = kotlin.math.abs(a - b) < grab

        private fun hit(x: Float, y: Float): Int {
            val l = near(x, box.left)
            val r = near(x, box.right)
            val t = near(y, box.top)
            val b = near(y, box.bottom)
            val inX = x > box.left - grab && x < box.right + grab
            val inY = y > box.top - grab && y < box.bottom + grab
            var m = NONE
            if (l && t) m = L or T
            else if (r && t) m = R or T
            else if (l && b) m = L or B
            else if (r && b) m = R or B
            else if (l && inY) m = L
            else if (r && inY) m = R
            else if (t && inX) m = T
            else if (b && inX) m = B
            else if (box.contains(x, y)) m = MOVE
            return m
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    mode = hit(e.x, e.y)
                    lx = e.x
                    ly = e.y
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return mode != NONE
                }
                MotionEvent.ACTION_MOVE -> {
                    if (mode == NONE) return false
                    val dx = e.x - lx
                    val dy = e.y - ly
                    lx = e.x
                    ly = e.y
                    if (mode == MOVE) {
                        val mx = dx.coerceIn(img.left - box.left, img.right - box.right)
                        val my = dy.coerceIn(img.top - box.top, img.bottom - box.bottom)
                        box.offset(mx, my)
                    } else {
                        if (mode and L != 0) box.left = (box.left + dx).coerceIn(img.left, box.right - minSize)
                        if (mode and R != 0) box.right = (box.right + dx).coerceIn(box.left + minSize, img.right)
                        if (mode and T != 0) box.top = (box.top + dy).coerceIn(img.top, box.bottom - minSize)
                        if (mode and B != 0) box.bottom = (box.bottom + dy).coerceIn(box.top + minSize, img.bottom)
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = NONE
            }
            return true
        }

        private companion object {
            const val NONE = 0
            const val L = 1
            const val R = 2
            const val T = 4
            const val B = 8
            const val MOVE = 16
        }
    }

    private companion object {
        const val SHOW_SIDE = 2048
    }
}
