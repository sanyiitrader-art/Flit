package app.flit

import android.content.Context
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ScrollView
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Read-only viewer for plain text and Markdown. Text is a normal selectable TextView, so Android's
 * own long-press / double-tap selection with handles and Copy works. Markdown is rendered as
 * formatted text (headings, emphasis, strikethrough, lists, task lists, tables, quotes, code, links, inline
 * HTML; images are not loaded) by Markwon. Pinch changes the
 * text size (the text re-wraps to the screen), a tap shows or hides the top bar.
 */
class TextScreen(act: MainActivity, private val path: String, private val md: Boolean) : Screen(act) {

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
        addView(bar, LayoutParams(Ui.MATCH, Ui.WRAP, android.view.Gravity.TOP))
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
                if (md) {
                    // Everything Markdown has: headings, emphasis, strikethrough, lists, task lists,
                    // tables, block quotes, code, rules, links (also bare URLs) and inline HTML.
                    val mw = Markwon.builder(act)
                        .usePlugin(StrikethroughPlugin.create())
                        .usePlugin(TablePlugin.create(act))
                        .usePlugin(TaskListPlugin.create(act))
                        .usePlugin(HtmlPlugin.create())
                        .usePlugin(LinkifyPlugin.create())
                        .build()
                    val spanned = mw.render(mw.parse(text))
                    FlitApp.main.post {
                        if (!alive) return@post
                        mw.setParsedMarkdown(tv, spanned)
                        selectable()
                    }
                } else {
                    FlitApp.main.post {
                        if (!alive) return@post
                        tv.text = text
                        selectable()
                    }
                }
            } catch (e: Throwable) {
                FlitApp.main.post { if (alive) Open.fallback(act, this, path) }
            }
        }
    }

    /** Selection stays on, and links in Markdown can still be tapped. */
    private fun selectable() {
        tv.setTextIsSelectable(true)
        if (md) tv.movementMethod = LinkMovementMethod.getInstance()
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
