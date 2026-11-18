package app.flit

import android.content.Intent

/**
 * The video player's own Activity (it still shows the same [VideoScreen]). Because it is separate from the
 * main one, it can stay in picture-in-picture while Flit's lists are used: opening another video then only
 * swaps the video inside the small window. It also receives "Open with" for video, and closing it
 * returns to the app the user came from.
 */
class PlayerActivity : MainActivity() {

    override val isMain: Boolean get() = false

    /** Opened from another app ("Open with"): closing returns to that app. */
    private var external = false

    override fun finish() {
        if (external && !isFinishing) moveTaskToBack(true)
        super.finish()
    }

    override fun handleGo(i: Intent?) {
        if (i == null) return
        val p = i.getStringExtra(EXTRA_PATH)
        if (p != null) {
            openVideo(p)
            return
        }
        val uri = i.data ?: return
        external = true
        val type = i.type
        val app = applicationContext
        Files.pool.execute {
            val f = Uris.resolve(app, uri, type)
            FlitApp.main.post {
                if (isFinishing || isDestroyed) return@post
                if (f != null) {
                    openVideo(f.path)
                } else {
                    Ui.toast(this, R.string.v_open_failed)
                    finish()
                }
            }
        }
    }

    private fun openVideo(path: String) {
        val t = top()
        if (t is VideoScreen) t.playNew(path) else push(VideoScreen(this, path))
    }

    companion object {
        const val EXTRA_PATH = "app.flit.VIDEO_PATH"
    }
}
