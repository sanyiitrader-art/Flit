package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

/**
 * Image surface of the viewer. The picture starts fitted inside the view; pinch zooms in (never
 * below the fitted size) and one finger pans while zoomed. When not zoomed, pulling down is
 * reported to [Host] so the viewer can close. A tap is reported too (HUD toggle).
 */
class ZoomImage(c: Context) : View(c) {

    interface Host {
        fun onTap()

        /** Not zoomed and pulled down by [dy] pixels (reported repeatedly). */
        fun onDrag(dy: Float)

        /** The pull ended with total distance [dy] and downward speed [vy] (px/s). */
        fun onDragEnd(dy: Float, vy: Float)
    }

    var host: Host? = null

    private var drawable: Drawable? = null
    private var dw = 0
    private var dh = 0
    private val m = Matrix()
    private val v = FloatArray(9)
    private var zoom = 1f
    private var scaling = false
    private var dragging = false
    private var dragDy = 0f
    private var vt: VelocityTracker? = null
    private val slop = ViewConfiguration.get(c).scaledTouchSlop

    private val scaleDet = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(d: ScaleGestureDetector): Boolean {
            val target = (zoom * d.scaleFactor).coerceIn(1f, MAX)
            val real = target / zoom
            if (real != 1f) {
                m.postScale(real, real, d.focusX, d.focusY)
                zoom = target
                clamp()
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f)
            }
            return true
        }

        override fun onScaleEnd(d: ScaleGestureDetector) {
            scaling = false
        }
    })

    private val tapDet = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            host?.onTap()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling || e2.pointerCount > 1) return false
            if (zoom > 1.01f) {
                m.postTranslate(-dx, -dy)
                clamp()
                invalidate()
                return true
            }
            val tdy = e2.y - (e1?.y ?: e2.y)
            val tdx = e2.x - (e1?.x ?: e2.x)
            if (dragging || (tdy > slop && tdy > abs(tdx))) {
                dragging = true
                dragDy = tdy.coerceAtLeast(0f)
                host?.onDrag(dragDy)
                return true
            }
            return false
        }
    })

    /** Shows [d] with the given pixel size. Pass null to clear. */
    fun setImage(d: Drawable?, w: Int, h: Int) {
        drawable?.callback = null
        (drawable as? Animatable)?.stop()
        drawable = d
        dw = w
        dh = h
        if (d != null) {
            d.callback = this
            d.setBounds(0, 0, w, h)
            if (isAttachedToWindow) (d as? Animatable)?.start()
        }
        fit()
    }

    private fun fit() {
        if (drawable == null || width == 0 || height == 0 || dw == 0 || dh == 0) {
            invalidate()
            return
        }
        val base = min(width.toFloat() / dw, height.toFloat() / dh)
        m.reset()
        m.postScale(base, base)
        m.postTranslate((width - dw * base) / 2f, (height - dh * base) / 2f)
        zoom = 1f
        invalidate()
    }

    /** Keeps the picture inside the view: centred when smaller, no empty edges when larger. */
    private fun clamp() {
        m.getValues(v)
        val s = v[Matrix.MSCALE_X]
        val cw = dw * s
        val ch = dh * s
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (cw <= width) (width - cw) / 2f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2f else ty.coerceIn(height - ch, 0f)
        m.postTranslate(tx - v[Matrix.MTRANS_X], ty - v[Matrix.MTRANS_Y])
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fit()
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === drawable || super.verifyDrawable(who)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        (drawable as? Animatable)?.start()
    }

    override fun onDetachedFromWindow() {
        (drawable as? Animatable)?.stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val d = drawable ?: return
        c.save()
        c.concat(m)
        d.draw(c)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (drawable == null) return false
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            // Zoomed in (or pinching): one finger pans the picture; otherwise the pager may swipe sideways.
            parent?.requestDisallowInterceptTouchEvent(zoom > 1.01f || e.pointerCount > 1)
        }
        val t = vt ?: VelocityTracker.obtain().also { vt = it }
        t.addMovement(e)
        scaleDet.onTouchEvent(e)
        tapDet.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging) {
                t.computeCurrentVelocity(1000)
                dragging = false
                host?.onDragEnd(dragDy, t.yVelocity)
            }
            t.recycle()
            vt = null
        }
        return true
    }

    private companion object {
        const val MAX = 8f
    }
}
