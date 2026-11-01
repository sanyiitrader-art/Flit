package app.flit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/** Icon kinds drawn by [Glyph]. All icons are monochrome and drawn in code (no image files). */
object Icons {
    const val BACK = 0
    const val CLOSE = 1
    const val MINIMIZE = 2
    const val SETTINGS = 3
    const val PHONE = 4
    const val FOLDER = 5
    const val CHEVRON = 6
    const val NOTE = 7
    const val PLAY = 8
    const val PAUSE = 9
    const val STOP = 10
    const val CHECK = 11
    const val PERSON = 12
    const val IMAGE = 13
    const val FILM = 14
    const val UP = 15
    const val DOWN = 16

    fun glyph(kind: Int, color: Int = Ui.cText): Drawable = Glyph(kind, color)

    /** Icon for a file: media types get a pictogram, everything else a labelled document. */
    fun forFile(type: Int, ext: String, color: Int = Ui.cText2): Drawable = when (type) {
        Types.IMAGE -> Glyph(IMAGE, color)
        Types.VIDEO -> Glyph(FILM, color)
        Types.AUDIO -> Glyph(NOTE, color)
        else -> DocGlyph(Types.badge(type, ext), color)
    }

    /** A square ImageView showing a glyph. */
    fun view(c: Context, kind: Int, sizeDp: Int, color: Int = Ui.cText): ImageView {
        val v = ImageView(c)
        v.setImageDrawable(Glyph(kind, color))
        v.layoutParams = LinearLayout.LayoutParams(Ui.dp(sizeDp), Ui.dp(sizeDp))
        return v
    }
}

/** Simple vector-style pictograms on a 24x24 grid, scaled to whatever bounds they get. */
class Glyph(private val kind: Int, private val color: Int) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private var alphaV = 255

    private fun st(w: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = w
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
    }

    private fun fl() {
        p.style = Paint.Style.FILL_AND_STROKE
        p.strokeWidth = 1.2f
        p.strokeJoin = Paint.Join.ROUND
    }

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, p)

    private fun poly(c: Canvas, vararg v: Float) {
        path.reset()
        path.moveTo(v[0], v[1])
        var i = 2
        while (i < v.size) {
            path.lineTo(v[i], v[i + 1])
            i += 2
        }
        c.drawPath(path, p)
    }

    private fun rr(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float) {
        rect.set(l, t, r, b)
        c.drawRoundRect(rect, rad, rad, p)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        p.color = color
        p.alpha = p.alpha * alphaV / 255

        when (kind) {
            Icons.BACK -> {
                st(2f); line(c, 20f, 12f, 5f, 12f); poly(c, 11f, 5f, 4f, 12f, 11f, 19f)
            }
            Icons.CLOSE -> {
                st(2f); line(c, 6f, 6f, 18f, 18f); line(c, 18f, 6f, 6f, 18f)
            }
            Icons.MINIMIZE -> {
                st(2f); poly(c, 6f, 8f, 12f, 14f, 18f, 8f); line(c, 6f, 19f, 18f, 19f)
            }
            Icons.SETTINGS -> {
                st(3f)
                p.strokeCap = Paint.Cap.BUTT
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4.0
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    line(c, 12f + ca * 7f, 12f + sa * 7f, 12f + ca * 10f, 12f + sa * 10f)
                }
                st(2f)
                c.drawCircle(12f, 12f, 6.5f, p)
                c.drawCircle(12f, 12f, 2.8f, p)
            }
            Icons.PHONE -> {
                st(2f); rr(c, 7f, 2f, 17f, 22f, 2.5f); line(c, 10.5f, 18.5f, 13.5f, 18.5f)
            }
            Icons.FOLDER -> {
                fl(); poly(c, 3f, 6f, 10f, 6f, 12.5f, 8.5f, 21f, 8.5f, 21f, 19f, 3f, 19f); path.close()
                c.drawPath(path, p)
            }
            Icons.CHEVRON -> {
                st(2.2f); poly(c, 9f, 5f, 16f, 12f, 9f, 19f)
            }
            Icons.NOTE -> {
                fl(); c.drawCircle(8f, 18f, 3f, p)
                st(2f); line(c, 11f, 18f, 11f, 4.5f); line(c, 11f, 4.5f, 18f, 7f)
            }
            Icons.PLAY -> {
                fl(); poly(c, 8f, 5f, 19f, 12f, 8f, 19f); path.close(); c.drawPath(path, p)
            }
            Icons.PAUSE -> {
                fl(); rr(c, 6f, 5f, 10f, 19f, 1f); rr(c, 14f, 5f, 18f, 19f, 1f)
            }
            Icons.STOP -> {
                fl(); rr(c, 6f, 6f, 18f, 18f, 2f)
            }
            Icons.CHECK -> {
                st(2.6f); poly(c, 5f, 12.5f, 10f, 17.5f, 19f, 7f)
            }
            Icons.PERSON -> {
                fl(); c.drawCircle(12f, 8f, 4f, p)
                rect.set(4f, 14f, 20f, 30f)
                c.drawArc(rect, 180f, 180f, true, p)
            }
            Icons.IMAGE -> {
                st(2f); rr(c, 3f, 4f, 21f, 20f, 2.5f)
                poly(c, 4f, 19f, 10f, 13f, 14f, 17f, 17f, 14f, 20.5f, 17.5f)
                fl(); c.drawCircle(8.5f, 9.5f, 1.8f, p)
            }
            Icons.FILM -> {
                st(2f); rr(c, 3f, 5f, 21f, 19f, 2.5f)
                fl(); poly(c, 10.5f, 9f, 15.5f, 12f, 10.5f, 15f); path.close(); c.drawPath(path, p)
            }
            Icons.UP -> {
                st(2.2f); line(c, 12f, 20f, 12f, 5f); poly(c, 5f, 11f, 12f, 4f, 19f, 11f)
            }
            Icons.DOWN -> {
                st(2.2f); line(c, 12f, 4f, 12f, 19f); poly(c, 5f, 13f, 12f, 20f, 19f, 13f)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {
        alphaV = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = Ui.dp(24)
    override fun getIntrinsicHeight(): Int = Ui.dp(24)
}

/** Document outline with a folded corner and a short type label (PDF, DOC, ZIP, ...). */
class DocGlyph(private val label: String, private val color: Int) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var alphaV = 255

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)

        p.color = color
        p.alpha = p.alpha * alphaV / 255
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.8f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND

        path.reset()
        path.moveTo(5f, 2.5f)
        path.lineTo(14f, 2.5f)
        path.lineTo(19f, 7.5f)
        path.lineTo(19f, 21.5f)
        path.lineTo(5f, 21.5f)
        path.close()
        c.drawPath(path, p)

        path.reset()
        path.moveTo(14f, 2.5f)
        path.lineTo(14f, 7.5f)
        path.lineTo(19f, 7.5f)
        c.drawPath(path, p)

        if (label.isNotEmpty()) {
            p.style = Paint.Style.FILL
            p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.DEFAULT_BOLD
            var ts = 6f
            p.textSize = ts
            val w = p.measureText(label)
            if (w > 11f) {
                ts = ts * 11f / w
                p.textSize = ts
            }
            c.drawText(label, 12f, 16.4f + ts * 0.35f, p)
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {
        alphaV = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf
        invalidateSelf()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = Ui.dp(24)
    override fun getIntrinsicHeight(): Int = Ui.dp(24)
}

/**
 * Circular avatar. Shows [bitmap] if set; otherwise a grey disc with [initial] or a person glyph.
 * [ring] adds an accent ring (used while discoverable / selected).
 */
class AvatarView(c: Context) : View(c) {

    var bitmap: Bitmap? = null
        set(v) {
            field = v
            shader = null
            invalidate()
        }

    var initial: String? = null
        set(v) {
            field = v
            invalidate()
        }

    var ring: Boolean = false
        set(v) {
            field = v
            invalidate()
        }

    private var shader: BitmapShader? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val box = Path()
    private val person by lazy { Glyph(Icons.PERSON, Ui.cText2) }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val ringW = Ui.dpf(2.5f)
        val r = if (ring) s / 2f - ringW - Ui.dpf(2) else s / 2f

        if (ring) {
            p.shader = null
            p.style = Paint.Style.STROKE
            p.strokeWidth = ringW
            p.color = Ui.cAccent
            c.drawCircle(cx, cy, s / 2f - ringW / 2f, p)
        }

        val bmp = bitmap
        if (bmp != null && !bmp.isRecycled) {
            var sh = shader
            if (sh == null) {
                sh = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                shader = sh
            }
            val d = r * 2f
            val sc = maxOf(d / bmp.width, d / bmp.height)
            m.reset()
            m.setScale(sc, sc)
            m.postTranslate(cx - bmp.width * sc / 2f, cy - bmp.height * sc / 2f)
            sh.setLocalMatrix(m)
            p.style = Paint.Style.FILL
            p.shader = sh
            c.drawCircle(cx, cy, r, p)
            p.shader = null
            return
        }

        p.shader = null
        p.style = Paint.Style.FILL
        p.color = Ui.cSurface3
        c.drawCircle(cx, cy, r, p)

        val ini = initial
        if (!ini.isNullOrBlank()) {
            p.color = Ui.cText2
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            p.textSize = r * 0.95f
            val y = cy - (p.descent() + p.ascent()) / 2f
            c.drawText(ini.trim().take(1).uppercase(), cx, y, p)
        } else {
            val g = r * 1.25f
            person.setBounds(
                (cx - g / 2f).toInt(), (cy - g / 2f).toInt(),
                (cx + g / 2f).toInt(), (cy + g / 2f).toInt()
            )
            // Clip the body so it stays inside the disc.
            box.reset()
            box.addCircle(cx, cy, r, Path.Direction.CW)
            c.save()
            c.clipPath(box)
            person.draw(c)
            c.restore()
        }
    }
}