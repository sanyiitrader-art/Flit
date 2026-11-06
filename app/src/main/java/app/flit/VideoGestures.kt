package app.flit

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Transparent layer over the video that turns touches into player actions:
 *  - tap: show / hide the controls; double tap in the centre: play / pause;
 *  - double tap on the right / left: forward / back 10 s. Further taps on the same side while the
 *    feedback is still showing keep seeking and show a running total ("10+", "20+", "30+" ... or
 *    "10−", "20−" ...) with a wave on that side;
 *  - vertical drag on the left half: volume, on the right half: brightness.
 * While [host] reports locked, only the tap works.
 */
class GestureLayer(c: Context) : View(c) {

    interface Host {
        fun onTap()
        fun onPlayPause()

        /** Seek by [seconds] (negative = back). */
        fun onSeek(seconds: Int)

        /** Vertical drag: [volume] true = left half. [delta] is a fraction of the full range. */
        fun onAdjust(volume: Boolean, delta: Float)
        fun onAdjustEnd()
        fun locked(): Boolean
    }

    var host: Host? = null
    var enabled2 = true

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var chainUntil = 0L
    private var acc = 0
    private var dir = 0 // -1 left, +1 right
    private var wave = 0f
    private var textAlpha = 0f
    private var adjusting = false
    private var adjustVolume = false
    private val slop = Ui.dpf(10)

    private val waveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 450L
        addUpdateListener {
            wave = it.animatedValue as Float
            invalidate()
        }
    }

    private val hideText = Runnable {
        textAlpha = 0f
        acc = 0
        dir = 0
        invalidate()
    }

    private fun zone(x: Float): Int = when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    private fun step(side: Int) {
        val now = SystemClock.uptimeMillis()
        acc = if (now < chainUntil && dir == side) acc + 10 else 10
        dir = side
        chainUntil = now + CHAIN_MS
        textAlpha = 1f
        host?.onSeek(side * 10)
        removeCallbacks(hideText)
        postDelayed(hideText, CHAIN_MS)
        waveAnim.cancel()
        waveAnim.start()
        invalidate()
    }

    private val det = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // A tap shortly after a double tap keeps seeking on that side.
            val side = zone(e.x)
            if (host?.locked() != true && side != 0 && SystemClock.uptimeMillis() < chainUntil) {
                step(side)
                return true
            }
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (host?.locked() != true && SystemClock.uptimeMillis() < chainUntil) return false
            host?.onTap()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val h = host ?: return false
            if (h.locked()) {
                h.onTap()
                return true
            }
            val side = zone(e.x)
            if (side == 0) h.onPlayPause() else step(side)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val h = host ?: return false
            if (h.locked() || e1 == null) return false
            val tdx = e2.x - e1.x
            val tdy = e2.y - e1.y
            if (!adjusting) {
                if (abs(tdy) < slop || abs(tdy) < abs(tdx) * 1.2f) return false
                adjusting = true
                adjustVolume = e1.x < width / 2f
            }
            h.onAdjust(adjustVolume, dy / (height * 0.8f))
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!enabled2) return false
        det.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (adjusting) {
                adjusting = false
                host?.onAdjustEnd()
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (dir == 0 || textAlpha <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = if (dir > 0) w else 0f
        // Wave: a soft circle that grows from the screen edge of the tapped side.
        p.style = Paint.Style.FILL
        p.color = Ui.alpha(Color.WHITE, ((1f - wave) * 70f).toInt())
        c.drawCircle(cx, h / 2f, h * (0.35f + 0.45f * wave), p)
        p.color = Ui.alpha(Color.WHITE, (textAlpha * 255f).toInt())
        p.textAlign = Paint.Align.CENTER
        p.isFakeBoldText = true
        p.textSize = Ui.dpf(24)
        val tx = if (dir > 0) w * 0.82f else w * 0.18f
        c.drawText(acc.toString() + if (dir > 0) "+" else "\u2212", tx, h / 2f + Ui.dpf(8), p)
    }

    override fun onDetachedFromWindow() {
        waveAnim.cancel()
        removeCallbacks(hideText)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CHAIN_MS = 700L
    }
}
