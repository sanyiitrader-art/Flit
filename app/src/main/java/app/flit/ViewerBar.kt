package app.flit

import android.content.Context
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import java.io.File

/**
 * Top bar of the PDF, text, Markdown and HTML viewers: back arrow and the file name (truncated
 * with "…"). Hidden and shown by a tap on the content ([TapFrame]).
 */
class ViewerBar(act: MainActivity, name: String) : LinearLayout(act) {

    var shown = true
        private set

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Ui.alpha(Ui.cBg, 240))
        setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(16), Ui.dp(4))
        isClickable = true // a tap on the bar itself never toggles it
        addView(iconBtn(act, Icons.BACK, act.getString(R.string.v_back)) { act.pop() }, Ui.lin(Ui.dp(48), Ui.dp(48)))
        addView(
            Ui.label(act, File(name).name, 17f, Ui.cText, true, 1),
            Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) }
        )
    }

    fun toggle() = show(!shown)

    fun show(on: Boolean) {
        shown = on
        animate().cancel()
        if (on) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(150L).start()
        } else {
            animate().alpha(0f).setDuration(150L).withEndAction { if (!shown) visibility = GONE }.start()
        }
    }

    companion object {
        val height: Int get() = Ui.dp(56)
    }
}

/** A FrameLayout that reports plain taps on its content without taking part in the touch handling. */
class TapFrame(c: Context) : FrameLayout(c) {

    var onTap: (() -> Unit)? = null

    private val det = GestureDetector(c, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap?.invoke()
            return false
        }
    })

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        det.onTouchEvent(e)
        return super.dispatchTouchEvent(e)
    }
}
