package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Avatar with accent waves radiating outward. Used full-size on the Receiver screen and small
 * as the in-app floating bubble. Animation runs only while the view is being drawn, so nothing
 * runs when the screen is off or the app is in the background.
 */
class WaveView(c: Context, private val avatarFrac: Float) : FrameLayout(c) {

    private val avatar = AvatarView(c)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setWillNotDraw(false)
        addView(avatar, LayoutParams(Ui.WRAP, Ui.WRAP))
        refresh()
    }

    /** Re-reads the saved profile picture (or the default avatar). */
    fun refresh() {
        avatar.bitmap = Avatar.load()
        avatar.initial = null
        avatar.ring = true
    }

    override fun onMeasure(w: Int, h: Int) {
        val s = minOf(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
        setMeasuredDimension(s, s)
        val a = MeasureSpec.makeMeasureSpec((s * avatarFrac).toInt(), MeasureSpec.EXACTLY)
        avatar.measure(a, a)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val a = avatar.measuredWidth
        val o = (r - l - a) / 2
        avatar.layout(o, o, o + a, o + a)
    }

    override fun dispatchDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r0 = width * avatarFrac / 2f
        val r1 = width / 2f
        val t = (SystemClock.uptimeMillis() % PERIOD).toFloat() / PERIOD
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(2)
        for (i in 0 until 3) {
            val f = (t + i / 3f) % 1f
            p.color = Ui.alpha(Ui.cAccent, ((1f - f) * 150f).toInt())
            c.drawCircle(cx, cy, r0 + (r1 - r0 - p.strokeWidth) * f, p)
        }
        super.dispatchDraw(c)
        postInvalidateOnAnimation()
    }

    private companion object {
        const val PERIOD = 2400L
    }
}

/**
 * Transfer progress bubble: three curved, segmented lines rotating at different speeds around
 * the overall percentage (based on bytes). Used by Phase 2 for Sender and Receiver transfers.
 */
class ProgressBubble(c: Context) : View(c) {

    /** 0..1 */
    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val speeds = floatArrayOf(0.12f, -0.09f, 0.16f)

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(Ui.dp(72), Ui.dp(72))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val big = minOf(width, height) / 2f

        p.style = Paint.Style.FILL
        p.color = Ui.cSurface2
        c.drawCircle(cx, cy, big - Ui.dpf(1), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(1)
        p.color = Ui.cBorder
        c.drawCircle(cx, cy, big - Ui.dpf(1), p)

        val t = SystemClock.uptimeMillis().toFloat()
        p.strokeWidth = Ui.dpf(3)
        p.strokeCap = Paint.Cap.ROUND
        p.color = Ui.cAccent
        for (i in 0 until 3) {
            val r = big - Ui.dpf(7) - i * Ui.dpf(6)
            rect.set(cx - r, cy - r, cx + r, cy + r)
            val a = (t * speeds[i]) % 360f
            c.drawArc(rect, a, 70f, false, p)
            c.drawArc(rect, a + 180f, 70f, false, p)
        }

        p.style = Paint.Style.FILL
        p.color = Ui.cText
        p.typeface = Typeface.DEFAULT_BOLD
        p.textAlign = Paint.Align.CENTER
        p.textSize = Ui.dpf(11)
        val y = cy - (p.descent() + p.ascent()) / 2f
        c.drawText("${(progress * 100f).toInt()}%", cx, y, p)

        postInvalidateOnAnimation()
    }
}

/** Touch handling for the floating views: drag anywhere in the layer, snap to a side, tap to open. */
object Drag {

    private var fx = 1f // horizontal position as a fraction of the free range (0 = left, 1 = right)
    private var fy = 0.35f

    fun attach(v: View, onTap: () -> Unit) {
        val slop = ViewConfiguration.get(v.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var moved = false
        var placed = false

        v.setOnTouchListener { _, e ->
            val parent = v.parent as? View
            if (parent == null) {
                false
            } else {
                val rx = (parent.width - v.width).coerceAtLeast(1).toFloat()
                val ry = (parent.height - v.height).coerceAtLeast(1).toFloat()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = v.translationX
                        startY = v.translationY
                        moved = false
                        v.parent.requestDisallowInterceptTouchEvent(true)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && hypot(dx, dy) > slop) moved = true
                        if (moved) {
                            v.translationX = (startX + dx).coerceIn(0f, rx)
                            v.translationY = (startY + dy).coerceIn(0f, ry)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            onTap()
                        } else {
                            fx = if (v.translationX > rx / 2f) 1f else 0f
                            fy = v.translationY / ry
                            v.animate().translationX(fx * rx).setDuration(150).start()
                        }
                    }
                }
                true
            }
        }

        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val parent = v.parent as? View
            if (!placed && parent != null && parent.width > 0 && v.width > 0) {
                placed = true
                v.translationX = fx * (parent.width - v.width)
                v.translationY = fy * (parent.height - v.height)
            }
        }
    }
}

/**
 * Manages the single floating bubble of the app (no overlay permission: it lives inside the
 * Activity). A transfer bubble replaces the Receiver avatar bubble. The bubble is hidden while the
 * full Receiver screen is on top.
 */
object Bubbles {

    private var layer: FrameLayout? = null
    private var tapReceiver: (() -> Unit)? = null
    private var tapTransfer: (() -> Unit)? = null
    private var receiverOn = false
    private var transferOn = false
    private var suppressed = false
    private var progress = 0f
    private var view: View? = null
    private var kind = 0 // 0 none, 1 receiver, 2 transfer

    fun attach(l: FrameLayout, onReceiver: () -> Unit, onTransfer: () -> Unit) {
        layer = l
        tapReceiver = onReceiver
        tapTransfer = onTransfer
        view = null
        kind = 0
        refresh()
    }

    fun detach(l: FrameLayout) {
        if (layer === l) {
            layer = null
            view = null
            kind = 0
            tapReceiver = null
            tapTransfer = null
        }
    }

    fun receiver(on: Boolean) {
        receiverOn = on
        refresh()
    }

    fun transfer(on: Boolean) {
        transferOn = on
        refresh()
    }

    fun suppress(on: Boolean) {
        suppressed = on
        refresh()
    }

    fun progress(f: Float) {
        progress = f
        (view as? ProgressBubble)?.progress = f
    }

    fun avatarChanged() {
        (view as? WaveView)?.refresh()
    }

    private fun refresh() {
        val l = layer ?: return
        val want = when {
            suppressed -> 0
            transferOn -> 2
            receiverOn -> 1
            else -> 0
        }
        if (want == kind) return
        view?.let { l.removeView(it) }
        view = null
        kind = want
        if (want == 0) return
        val size = Ui.dp(if (want == 2) 72 else 68)
        val v: View = if (want == 2) ProgressBubble(l.context).also { it.progress = progress }
        else WaveView(l.context, 0.56f)
        l.addView(v, FrameLayout.LayoutParams(size, size))
        Drag.attach(v) { if (want == 2) tapTransfer?.invoke() else tapReceiver?.invoke() }
        view = v
    }
}