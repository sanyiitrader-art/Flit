package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Slider of the video and audio players: filled part and thumb in terracotta (#B05D42), unfilled
 * part white/grey. Horizontal (seek bar) or vertical (volume, brightness; bottom = 0%).
 */
class PSlider(c: Context, private val vertical: Boolean = false) : View(c) {

    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** (fraction, fromUser). */
    var onChange: ((Float, Boolean) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null

    var dragging = false
        private set

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = Ui.dpf(12)
    private val track = Ui.dpf(3)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = track
        val thumbX: Float
        val thumbY: Float
        if (vertical) {
            val x = w / 2f
            val top = pad
            val bottom = h - pad
            thumbX = x
            thumbY = bottom - (bottom - top) * progress
            p.color = Ui.alpha(Color.WHITE, 90)
            c.drawLine(x, top, x, bottom, p)
            p.color = ACCENT
            c.drawLine(x, thumbY, x, bottom, p)
        } else {
            val y = h / 2f
            val left = pad
            val right = w - pad
            thumbX = left + (right - left) * progress
            thumbY = y
            p.color = Ui.alpha(Color.WHITE, 90)
            c.drawLine(left, y, right, y, p)
            p.color = ACCENT
            c.drawLine(left, y, thumbX, y, p)
        }
        p.color = ACCENT
        c.drawCircle(thumbX, thumbY, Ui.dpf(if (dragging) 9 else 7), p)
    }

    private fun fraction(e: MotionEvent): Float {
        return if (vertical) {
            val top = pad
            val bottom = height - pad
            1f - ((e.y - top) / (bottom - top)).coerceIn(0f, 1f)
        } else {
            val left = pad
            val right = width - pad
            ((e.x - left) / (right - left)).coerceIn(0f, 1f)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onStart?.invoke()
                progress = fraction(e)
                onChange?.invoke(progress, true)
            }
            MotionEvent.ACTION_MOVE -> {
                progress = fraction(e)
                onChange?.invoke(progress, true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
                onStop?.invoke()
            }
        }
        return true
    }

    companion object {
        val ACCENT: Int = 0xFFB05D42.toInt()
    }
}
