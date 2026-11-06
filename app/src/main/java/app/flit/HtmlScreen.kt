package app.flit

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

/**
 * Read-only HTML viewer using the system WebView (an actual rendered page, not source code).
 * JavaScript and network loading are switched off, so the file is displayed from the phone only;
 * pictures and styles next to it still load. Pinch zoom and scrolling are WebView's own. Links to
 * other files stay inside the viewer, web links open in the phone's browser. A tap shows or hides
 * the top bar.
 */
class HtmlScreen(act: MainActivity, private val path: String) : Screen(act) {

    private val bar = ViewerBar(act, path)
    private val frame = TapFrame(act)
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
            w.setBackgroundColor(Color.WHITE)
            w.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    if (u.scheme == "file") return false
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
            w.loadUrl(Uri.fromFile(File(path)).toString())
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
        val w = web ?: return
        frame.removeView(w)
        w.destroy()
    }
}
