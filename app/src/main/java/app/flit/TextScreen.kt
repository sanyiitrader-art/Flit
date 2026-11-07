package app.flit

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Read-only viewer for plain text. The text is a normal selectable TextView, so Android's own
 * long-press / double-tap selection with handles and Copy works. Pinch changes the text size (the text
 * re-wraps to the screen), a tap shows or hides the top bar. (Markdown is shown by [HtmlScreen].)
 */
class TextScreen(act: MainActivity, private val path: String) : Screen(act) {

    private val bar = ViewerBar(act, path)
    private val frame = TapFrame(act)
    private val scroll = ZoomScroll(act)
    private val tv = TextView(act)
    private var scale = 1f
    private var alive = true

    init {
        tv.setTextColor(Ui.cText)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE)
        tv.setLineSpacing(0f, 1.2f)
        tv.setPadding(Ui.dp(16), ViewerBar.height + Ui.dp(8), Ui.dp(16), Ui.dp(32))
        tv.setTextIsSelectable(true)
        scroll.addView(tv, LayoutParams(Ui.MATCH, Ui.WRAP))
        scroll.onPinch = { f ->
            scale = (scale * f).coerceIn(0.6f, 4f)
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, BASE * scale)
        }
        frame.addView(scroll, LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.onTap = { bar.toggle() }
        addView(frame, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(bar, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))
        load()
    }

    override fun onDestroyed() {
        alive = false
    }

    private fun load() {
        FlitApp.io.execute {
            try {
                val text = read(File(path))
                if (text.length > MAX_CHARS) {
                    FlitApp.main.post { if (alive) Open.fallback(act, this, path) }
                    return@execute
                }
                FlitApp.main.post {
                    if (!alive) return@post
                    tv.text = text
                    tv.setTextIsSelectable(true)
                }
            } catch (e: Throwable) {
                FlitApp.main.post { if (alive) Open.fallback(act, this, path) }
            }
        }
    }

    /** UTF-8 (with or without BOM); a file that is not valid UTF-8 is read as Latin-1. */
    private fun read(f: File): String {
        val bytes = f.readBytes()
        val s = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
        return s.removePrefix("\uFEFF")
    }

    /** ScrollView that reports pinch gestures (two fingers) and leaves everything else alone. */
    private class ZoomScroll(c: Context) : ScrollView(c) {

        var onPinch: ((Float) -> Unit)? = null

        private val det = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                onPinch?.invoke(d.scaleFactor)
                return true
            }
        })

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            det.onTouchEvent(e)
            return super.dispatchTouchEvent(e)
        }

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            if (e.pointerCount > 1 || det.isInProgress) return true
            return super.onInterceptTouchEvent(e)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.pointerCount > 1 || det.isInProgress) return true
            return super.onTouchEvent(e)
        }
    }

    private companion object {
        const val BASE = 16f
        const val MAX_CHARS = 1_000_000
    }
}
