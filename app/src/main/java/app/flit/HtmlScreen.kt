package app.flit

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Read-only viewer for HTML files and Markdown files, using the system WebView (a real rendered
 * page, never source). Markdown is converted to HTML first, with tables, strikethrough, task lists,
 * footnotes, automatic links, code blocks, block quotes, headings, lists, images next to the file and
 * inline HTML, then shown with a style that matches the app's colours.
 *
 * JavaScript and network loading are switched off, so only the phone's own file is displayed.
 * Pinch zoom, scrolling and text selection (long-press, with handles) are WebView's own. Links to other
 * files stay inside the viewer, web links open in the phone's browser. A tap shows or hides the top bar.
 * Not rendered: LaTeX maths and definition lists (shown as plain text).
 */
class HtmlScreen(act: MainActivity, private val path: String, private val md: Boolean = false) : Screen(act) {

    private val bar = ViewerBar(act, path)
    private val frame = TapFrame(act)
    private var alive = true
    private val web: WebView? = try {
        WebView(act)
    } catch (e: Exception) {
        null
    }

    init {
        addView(frame, LayoutParams(Ui.MATCH, Ui.MATCH))
        addView(bar, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))
        val w = web
        if (w == null) {
            FlitApp.main.post { Open.fallback(act, this, path) }
        } else {
            val s = w.settings
            s.javaScriptEnabled = false
            s.allowFileAccess = true
            s.allowContentAccess = false
            s.blockNetworkLoads = true
            s.setSupportZoom(true)
            s.builtInZoomControls = true
            s.displayZoomControls = false
            s.useWideViewPort = true
            s.loadWithOverviewMode = true
            w.setBackgroundColor(if (md) Ui.cBg else Color.WHITE)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "file" || u.scheme == "about" || u.scheme == "data") return false
                    try {
                        act.startActivity(Intent(Intent.ACTION_VIEW, u))
                    } catch (e: Exception) {
                        Ui.toast(act, R.string.v_open_no_app)
                    }
                    return true
                }
            }
            frame.addView(w, LayoutParams(Ui.MATCH, Ui.MATCH).also { it.topMargin = ViewerBar.height })
            frame.onTap = {
                bar.toggle()
                val lp = w.layoutParams as LayoutParams
                lp.topMargin = if (bar.shown) ViewerBar.height else 0
                w.layoutParams = lp
            }
            if (md) loadMarkdown(w) else w.loadUrl(Uri.fromFile(File(path)).toString())
        }
    }

    private fun loadMarkdown(w: WebView) {
        FlitApp.io.execute {
            try {
                val html = page(read(File(path)))
                val base = Uri.fromFile(File(path).parentFile ?: File("/")).toString().trimEnd('/') + "/"
                FlitApp.main.post {
                    if (alive) w.loadDataWithBaseURL(base, html, "text/html", "UTF-8", null)
                }
            } catch (e: Throwable) {
                FlitApp.main.post { if (alive) Open.fallback(act, this, path) }
            }
        }
    }

    override fun onBack(): Boolean {
        val w = web ?: return false
        if (w.canGoBack()) {
            w.goBack()
            return true
        }
        return false
    }

    override fun onDestroyed() {
        alive = false
        val w = web ?: return
        frame.removeView(w)
        w.destroy()
    }

    // ---- Markdown -> HTML ---------------------------------------------------------------------------

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

    private fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

    private fun page(markdown: String): String {
        val ext = listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            TaskListItemsExtension.create(),
            AutolinkExtension.create(),
            FootnotesExtension.create()
        )
        val parser = Parser.builder().extensions(ext).build()
        val body = HtmlRenderer.builder().extensions(ext).build().render(parser.parse(markdown))
        val bg = hex(Ui.cBg)
        val tx = hex(Ui.cText)
        val tx2 = hex(Ui.cText2)
        val bd = hex(Ui.cBorder)
        val sf = hex(Ui.cSurface2)
        val ac = hex(Ui.cAccent)
        return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
html{-webkit-text-size-adjust:100%}
body{margin:0;padding:16px 16px 40px;background:$bg;color:$tx;font-family:sans-serif;font-size:16px;line-height:1.6;overflow-wrap:anywhere}
h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.3em 0 .5em;font-weight:700}
h1{font-size:2em;padding-bottom:.3em;border-bottom:1px solid $bd}
h2{font-size:1.6em;padding-bottom:.25em;border-bottom:1px solid $bd}
h3{font-size:1.35em}h4{font-size:1.15em}h5{font-size:1em}h6{font-size:.9em;color:$tx2}
p{margin:.8em 0}
a{color:$ac}
img{max-width:100%;height:auto}
code{font-family:monospace;background:$sf;padding:.15em .35em;border-radius:4px;font-size:.92em}
pre{background:$sf;padding:12px;border-radius:8px;overflow-x:auto;line-height:1.45}
pre code{background:none;padding:0;font-size:.88em}
blockquote{margin:1em 0;padding:.1em 1em;border-left:4px solid $ac;color:$tx2}
blockquote>:first-child{margin-top:.5em}blockquote>:last-child{margin-bottom:.5em}
table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%;margin:1em 0}
th,td{border:1px solid $bd;padding:6px 12px}
th{background:$sf}
hr{border:0;border-top:1px solid $bd;margin:1.6em 0}
ul,ol{padding-left:1.6em}
li{margin:.25em 0}
li:has(>input[type=checkbox]){list-style:none;margin-left:-1.4em}
li>input[type=checkbox]{margin-right:.5em}
del{opacity:.7}
section.footnotes{font-size:.9em;border-top:1px solid $bd;margin-top:2em;color:$tx2}
</style></head><body>$body</body></html>"""
    }
}
