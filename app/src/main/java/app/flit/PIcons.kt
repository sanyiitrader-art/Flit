package app.flit

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** "mm:ss", or "hh:mm:ss" for an hour or more. */
fun clockText(ms: Long): String {
    val s = if (ms < 0L) 0L else ms / 1000L
    val h = s / 3600L
    val m = (s % 3600L) / 60L
    val sec = s % 60L
    return if (h > 0L) String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%02d:%02d", m, sec)
}

/**
 * Icons of the viewers and players that the main icon set does not have. Drawn from a 24-unit grid
 * with round strokes, so they look like the existing icons at any size.
 * BACK / CLOSE / PLAY / PAUSE / DOWN come from [Icons] (with a white colour).
 */
object PIcons {
    const val CROP = 0
    const val NEXT = 1
    const val PREV = 2
    const val REW10 = 3
    const val FWD10 = 4
    const val SHUFFLE = 5
    const val REPEAT = 6
    const val REPEAT_ONE = 7
    const val LOCK = 8
    const val UNLOCK = 9
    const val VOL = 10
    const val MUTE = 11
    const val PIP = 12
    const val ASPECT = 13
    const val LIST = 14
    const val BRIGHT = 15
    const val ROTATE = 16
    const val HEADSET = 17
    const val SQUARE = 18

    fun get(kind: Int, color: Int = Color.WHITE): PIcon = PIcon(kind, color)
}

class PIcon(private val kind: Int, color: Int) : Drawable() {

    /** Brightness icon only: 0..1, how far the glow and rays reach. */
    var level = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidateSelf()
        }

    var color = color
        set(v) {
            field = v
            invalidateSelf()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var a = 255

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        if (s <= 0f) return
        c.save()
        c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f)
        c.scale(s, s)
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 2f
        p.color = color
        p.alpha = (Color.alpha(color) * a) / 255
        stroke()
        when (kind) {
            PIcons.CROP -> {
                poly(c, false, 6f, 2f, 6f, 18f, 22f, 18f)
                poly(c, false, 2f, 6f, 18f, 6f, 18f, 22f)
            }
            PIcons.NEXT -> {
                fill()
                poly(c, true, 6f, 5f, 16f, 12f, 6f, 19f)
                stroke()
                c.drawLine(19f, 5f, 19f, 19f, p)
            }
            PIcons.PREV -> {
                fill()
                poly(c, true, 18f, 5f, 8f, 12f, 18f, 19f)
                stroke()
                c.drawLine(5f, 5f, 5f, 19f, p)
            }
            PIcons.REW10 -> {
                c.save()
                c.translate(24f, 0f)
                c.scale(-1f, 1f)
                skip(c)
                c.restore()
                label(c, "10", 16.2f, 9f)
            }
            PIcons.FWD10 -> {
                skip(c)
                label(c, "10", 16.2f, 9f)
            }
            PIcons.SHUFFLE -> {
                val q = Path()
                q.moveTo(3f, 17f); q.lineTo(7f, 17f); q.cubicTo(11f, 17f, 13f, 7f, 17f, 7f); q.lineTo(20f, 7f)
                c.drawPath(q, p)
                poly(c, false, 18f, 5f, 20f, 7f, 18f, 9f)
                val r = Path()
                r.moveTo(3f, 7f); r.lineTo(7f, 7f); r.cubicTo(8.5f, 7f, 9.7f, 8f, 10.7f, 9.4f)
                r.moveTo(13.3f, 14.6f); r.cubicTo(14.3f, 16f, 15.5f, 17f, 17f, 17f); r.lineTo(20f, 17f)
                c.drawPath(r, p)
                poly(c, false, 18f, 15f, 20f, 17f, 18f, 19f)
            }
            PIcons.REPEAT, PIcons.REPEAT_ONE -> {
                val q = Path()
                q.moveTo(4f, 11f); q.lineTo(4f, 9f); q.quadTo(4f, 6f, 7f, 6f); q.lineTo(20f, 6f)
                q.moveTo(20f, 13f); q.lineTo(20f, 15f); q.quadTo(20f, 18f, 17f, 18f); q.lineTo(4f, 18f)
                c.drawPath(q, p)
                poly(c, false, 17f, 3f, 20f, 6f, 17f, 9f)
                poly(c, false, 7f, 15f, 4f, 18f, 7f, 21f)
                if (kind == PIcons.REPEAT_ONE) label(c, "1", 15.2f, 8.5f)
            }
            PIcons.LOCK, PIcons.UNLOCK -> {
                c.drawRoundRect(RectF(5f, 11f, 19f, 21f), 2.5f, 2.5f, p)
                val q = Path()
                q.moveTo(8f, 11f); q.lineTo(8f, 8f)
                q.arcTo(RectF(8f, 4f, 16f, 12f), 180f, 180f)
                if (kind == PIcons.LOCK) q.lineTo(16f, 11f)
                c.drawPath(q, p)
                fill()
                c.drawCircle(12f, 16f, 1.4f, p)
            }
            PIcons.VOL, PIcons.MUTE -> {
                speaker(c)
                if (kind == PIcons.VOL) {
                    c.drawArc(RectF(9f, 8f, 17f, 16f), -50f, 100f, false, p)
                    c.drawArc(RectF(6f, 5f, 20f, 19f), -50f, 100f, false, p)
                } else {
                    c.drawLine(16f, 9f, 22f, 15f, p)
                    c.drawLine(22f, 9f, 16f, 15f, p)
                }
            }
            PIcons.PIP -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, p)
                fill()
                c.drawRoundRect(RectF(12f, 11f, 19f, 16f), 1f, 1f, p)
            }
            PIcons.ASPECT -> {
                poly(c, false, 4f, 9f, 4f, 4f, 9f, 4f)
                poly(c, false, 15f, 4f, 20f, 4f, 20f, 9f)
                poly(c, false, 20f, 15f, 20f, 20f, 15f, 20f)
                poly(c, false, 9f, 20f, 4f, 20f, 4f, 15f)
            }
            PIcons.LIST -> {
                for (i in 0..2) {
                    val y = 6f + 6f * i
                    c.drawLine(9f, y, 20f, y, p)
                    fill()
                    c.drawCircle(4.5f, y, 1.3f, p)
                    stroke()
                }
            }
            PIcons.ROTATE -> {
                stroke()
                c.drawArc(RectF(4f, 4f, 20f, 20f), 195f, 105f, false, p)
                head(c, 12f + 8f * cos(Math.toRadians(300.0)).toFloat(), 12f + 8f * sin(Math.toRadians(300.0)).toFloat(), 0.866f, 0.5f)
                c.drawArc(RectF(4f, 4f, 20f, 20f), 15f, 105f, false, p)
                head(c, 12f + 8f * cos(Math.toRadians(120.0)).toFloat(), 12f + 8f * sin(Math.toRadians(120.0)).toFloat(), -0.866f, -0.5f)
                c.drawRoundRect(RectF(9.6f, 8f, 14.4f, 16f), 1.2f, 1.2f, p)
            }
            PIcons.HEADSET -> {
                stroke()
                c.drawArc(RectF(4.5f, 3.5f, 19.5f, 18.5f), 180f, 180f, false, p)
                fill()
                c.drawRoundRect(RectF(3.5f, 11.5f, 8f, 19.5f), 2f, 2f, p)
                c.drawRoundRect(RectF(16f, 11.5f, 20.5f, 19.5f), 2f, 2f, p)
            }
            PIcons.SQUARE -> {
                c.drawRoundRect(RectF(5f, 5f, 19f, 19f), 3f, 3f, p)
            }
            PIcons.BRIGHT -> {
                fill()
                p.alpha = (Color.alpha(color) * a / 255) * 35 / 100
                c.drawCircle(12f, 12f, 5.5f + level * 5.5f, p)
                p.alpha = (Color.alpha(color) * a) / 255
                c.drawCircle(12f, 12f, 3.6f, p)
                stroke()
                val r1 = 6.2f
                val r2 = 7.2f + level * 4.3f
                for (i in 0 until 8) {
                    val ang = Math.PI / 4.0 * i
                    c.drawLine(
                        12f + (r1 * cos(ang)).toFloat(), 12f + (r1 * sin(ang)).toFloat(),
                        12f + (r2 * cos(ang)).toFloat(), 12f + (r2 * sin(ang)).toFloat(), p
                    )
                }
            }
        }
        c.restore()
    }

    private fun stroke() {
        p.style = Paint.Style.STROKE
    }

    private fun fill() {
        p.style = Paint.Style.FILL
    }

    private fun poly(c: Canvas, close: Boolean, vararg xy: Float) {
        val q = Path()
        q.moveTo(xy[0], xy[1])
        var i = 2
        while (i < xy.size) {
            q.lineTo(xy[i], xy[i + 1])
            i += 2
        }
        if (close) q.close()
        c.drawPath(q, p)
    }

    private fun speaker(c: Canvas) {
        stroke()
        poly(c, true, 4f, 9f, 8f, 9f, 13f, 5f, 13f, 19f, 8f, 15f, 4f, 15f)
    }

    /** Filled arrow head with its tip at (x, y) + 2.6 * direction. */
    private fun head(c: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val px = -dy
        val py = dx
        fill()
        val t = Path()
        t.moveTo(x + dx * 3f, y + dy * 3f)
        t.lineTo(x + px * 2.8f, y + py * 2.8f)
        t.lineTo(x - px * 2.8f, y - py * 2.8f)
        t.close()
        c.drawPath(t, p)
        stroke()
    }

    /** Circular arrow of the 10-second skip icons (clockwise, arrow head at the top). */
    private fun skip(c: Canvas) {
        stroke()
        c.drawArc(RectF(2.5f, 3.5f, 21.5f, 22.5f), -60f, 300f, false, p)
        val ex = 12f + 9.5f * cos(Math.toRadians(240.0)).toFloat()
        val ey = 13f + 9.5f * sin(Math.toRadians(240.0)).toFloat()
        head(c, ex, ey, 0.866f, -0.5f)
    }

    private fun label(c: Canvas, text: String, baseline: Float, size: Float) {
        fill()
        p.textSize = size
        p.textAlign = Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        c.drawText(text, 12f, baseline, p)
        stroke()
    }

    override fun setAlpha(alpha: Int) {
        a = alpha
        invalidateSelf()
    }

    override fun setColorFilter(cf: ColorFilter?) {
        p.colorFilter = cf
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
