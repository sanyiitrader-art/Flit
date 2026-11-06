package app.flit

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.provider.Settings
import android.util.Rational
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale

/**
 * Built-in video player (Media3 ExoPlayer; only containers and codecs Android decodes, anything
 * else is offered to other apps). Portrait and landscape; icon-only white controls; native
 * picture-in-picture; playlist of the other videos in the same folder; gestures from [GestureLayer].
 * Brightness and volume gestures change the window brightness and the media volume.
 */
class VideoScreen(act: MainActivity, startPath: String) : Screen(act), GestureLayer.Host {

    private val files: List<File> = Open.siblings(startPath)
    private val player: ExoPlayer = ExoPlayer.Builder(act).build()
    private val box = VideoBox(act)
    private val gestures = GestureLayer(act)

    private val top = LinearLayout(act)
    private val title = Ui.label(act, "", 16f, Color.WHITE, true, 1)
    private val bottom = LinearLayout(act)
    private val elapsed = Ui.label(act, "00:00", 12f, Color.WHITE, false, 1)
    private val total = Ui.label(act, "00:00", 12f, Color.WHITE, false, 1)
    private val seek = PSlider(act)
    private val playBtn: ImageView
    private val prevBtn: ImageView
    private val nextBtn: ImageView
    private val rewBtn: ImageView
    private val fwdBtn: ImageView
    private val speedBtn: TextView
    private val lockBtn: ImageView
    private val muteBtn: ImageView
    private val volSlider = PSlider(act, true)
    private val briSlider = PSlider(act, true)
    private val sun = PIcons.get(PIcons.BRIGHT)
    private val volGroup = LinearLayout(act)
    private val briGroup = LinearLayout(act)
    private val label = TextView(act)

    private val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volFrac = 0f
    private var briFrac = 0.5f

    private var hud = true
    private var locked = false
    private var lockShown = false
    private var adjusting = false
    private var inPip = false
    private var muted = false
    private var scrubbing = false
    private var alive = true
    private var speed = 1f
    private var aspect = 0
    private var playlist: PlaylistPanel? = null
    private var speedMenu: View? = null

    private val autoHide = Runnable {
        if (speedMenu != null || playlist != null || scrubbing) {
            bump()
        } else {
            if (locked) lockShown = false else hud = false
            applyHud()
        }
    }
    private val hideLabel = Runnable { label.visibility = View.GONE }
    private val endAdjust = Runnable {
        adjusting = false
        applyHud()
    }
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (alive) postDelayed(this, 250L)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        keepScreenOn = true
        Audio.pause() // never two things playing at once

        addView(box, LayoutParams(Ui.MATCH, Ui.MATCH))
        gestures.host = this
        addView(gestures, LayoutParams(Ui.MATCH, Ui.MATCH))

        // ---- Top bar: back, title, playlist ----
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(14))
        top.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.alpha(Color.BLACK, 170), 0))
        top.addView(btn(Icons.glyph(Icons.BACK, Color.WHITE), 48, act.getString(R.string.v_back)) { act.pop() }, Ui.lin(Ui.dp(48), Ui.dp(48)))
        top.addView(title, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) })
        top.addView(btn(PIcons.get(PIcons.LIST), 48, act.getString(R.string.v_playlist)) { openPlaylist() }, Ui.lin(Ui.dp(48), Ui.dp(48)))
        addView(top, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP))

        // ---- Bottom: seek bar with times, then the controls ----
        bottom.orientation = LinearLayout.VERTICAL
        bottom.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(8))
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.alpha(Color.BLACK, 170), 0))

        val row1 = LinearLayout(act)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        row1.addView(elapsed, Ui.lin(Ui.WRAP, Ui.WRAP))
        row1.addView(seek, Ui.lin(0, Ui.dp(32), 1f).also { it.setMargins(Ui.dp(4), 0, Ui.dp(4), 0) })
        row1.addView(total, Ui.lin(Ui.WRAP, Ui.WRAP))
        bottom.addView(row1, Ui.lin(Ui.MATCH, Ui.WRAP))

        val pipBtn = btn(PIcons.get(PIcons.PIP), 44, act.getString(R.string.v_pip)) { enterPip() }
        speedBtn = Ui.label(act, speedText(1f), 14f, Color.WHITE, true, 1)
        speedBtn.gravity = Gravity.CENTER
        speedBtn.setPadding(Ui.dp(10), 0, Ui.dp(10), 0)
        speedBtn.contentDescription = act.getString(R.string.v_speed)
        speedBtn.setOnClickListener {
            bump()
            toggleSpeedMenu()
        }
        rewBtn = btn(PIcons.get(PIcons.REW10), 44, act.getString(R.string.v_rewind)) { onSeek(-10) }
        prevBtn = btn(PIcons.get(PIcons.PREV), 44, act.getString(R.string.v_prev)) { player.seekToPrevious() }
        playBtn = btn(Icons.glyph(Icons.PAUSE, Color.WHITE), 56, act.getString(R.string.v_play_pause)) { onPlayPause() }
        nextBtn = btn(PIcons.get(PIcons.NEXT), 44, act.getString(R.string.v_next)) { player.seekToNextMediaItem() }
        fwdBtn = btn(PIcons.get(PIcons.FWD10), 44, act.getString(R.string.v_forward)) { onSeek(10) }
        val aspectBtn = btn(PIcons.get(PIcons.ASPECT), 44, act.getString(R.string.v_aspect)) { cycleAspect() }

        val left = LinearLayout(act)
        left.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        left.addView(pipBtn, Ui.lin(Ui.dp(44), Ui.dp(44)))
        left.addView(speedBtn, Ui.lin(Ui.WRAP, Ui.dp(44)))
        val center = LinearLayout(act)
        center.gravity = Gravity.CENTER
        center.addView(rewBtn, Ui.lin(Ui.dp(44), Ui.dp(44)))
        center.addView(prevBtn, Ui.lin(Ui.dp(44), Ui.dp(44)))
        center.addView(playBtn, Ui.lin(Ui.dp(56), Ui.dp(56)).also { it.setMargins(Ui.dp(6), 0, Ui.dp(6), 0) })
        center.addView(nextBtn, Ui.lin(Ui.dp(44), Ui.dp(44)))
        center.addView(fwdBtn, Ui.lin(Ui.dp(44), Ui.dp(44)))
        val right = LinearLayout(act)
        right.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        right.addView(aspectBtn, Ui.lin(Ui.dp(44), Ui.dp(44)))

        val row2 = LinearLayout(act)
        row2.orientation = LinearLayout.HORIZONTAL
        row2.gravity = Gravity.CENTER_VERTICAL
        row2.addView(left, Ui.lin(0, Ui.WRAP, 1f))
        row2.addView(center, Ui.lin(Ui.WRAP, Ui.WRAP))
        row2.addView(right, Ui.lin(0, Ui.WRAP, 1f))
        bottom.addView(row2, Ui.lin(Ui.MATCH, Ui.WRAP))
        addView(bottom, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))

        // ---- Left column: volume slider, lock. Right column: brightness slider, sound. ----
        volGroup.orientation = LinearLayout.VERTICAL
        volGroup.gravity = Gravity.CENTER_HORIZONTAL
        volGroup.addView(
            ImageView(act).also { it.setImageDrawable(PIcons.get(PIcons.VOL)) },
            Ui.lin(Ui.dp(26), Ui.dp(26))
        )
        volGroup.addView(volSlider, Ui.lin(Ui.dp(40), Ui.dp(130)))
        briGroup.orientation = LinearLayout.VERTICAL
        briGroup.gravity = Gravity.CENTER_HORIZONTAL
        briGroup.addView(
            ImageView(act).also { it.setImageDrawable(sun) },
            Ui.lin(Ui.dp(30), Ui.dp(30))
        )
        briGroup.addView(briSlider, Ui.lin(Ui.dp(40), Ui.dp(126)))

        lockBtn = btn(PIcons.get(PIcons.UNLOCK), 48, act.getString(R.string.v_lock)) { toggleLock() }
        muteBtn = btn(PIcons.get(PIcons.VOL), 48, act.getString(R.string.v_sound)) { toggleMute() }

        val lc = LinearLayout(act)
        lc.orientation = LinearLayout.VERTICAL
        lc.gravity = Gravity.CENTER_HORIZONTAL
        lc.addView(volGroup, Ui.lin(Ui.WRAP, Ui.WRAP))
        lc.addView(lockBtn, Ui.lin(Ui.dp(48), Ui.dp(48)).also { it.topMargin = Ui.dp(8) })
        addView(lc, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.START or Gravity.CENTER_VERTICAL).also { it.leftMargin = Ui.dp(10) })

        val rc = LinearLayout(act)
        rc.orientation = LinearLayout.VERTICAL
        rc.gravity = Gravity.CENTER_HORIZONTAL
        rc.addView(briGroup, Ui.lin(Ui.WRAP, Ui.WRAP))
        rc.addView(muteBtn, Ui.lin(Ui.dp(48), Ui.dp(48)).also { it.topMargin = Ui.dp(8) })
        addView(rc, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = Ui.dp(10) })

        label.setTextColor(Color.WHITE)
        label.textSize = 20f
        label.typeface = android.graphics.Typeface.DEFAULT_BOLD
        label.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8))
        label.background = Ui.shape(Ui.alpha(Color.BLACK, 150), 12)
        label.visibility = GONE
        addView(label, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = Ui.dp(76) })

        // ---- Sliders ----
        seek.onStart = {
            scrubbing = true
            bump()
        }
        seek.onChange = { f, user -> if (user) elapsed.text = clockText((f * player.duration.coerceAtLeast(0L)).toLong()) }
        seek.onStop = {
            val d = player.duration
            if (d > 0L) player.seekTo((seek.progress * d).toLong())
            scrubbing = false
            bump()
        }
        volSlider.onChange = { f, user -> if (user) { setVolume(f); bump() } }
        briSlider.onChange = { f, user -> if (user) { setBrightness(f); bump() } }

        // ---- Player ----
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true
        )
        player.setHandleAudioBecomingNoisy(true)
        player.setVideoSurfaceView(box.surface)
        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                box.setVideo(videoSize)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateTitle()
                updateNav()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    hud = true
                    applyHud()
                    bump()
                }
                updatePlayIcon()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayIcon()
            }

            override fun onPlayerError(error: PlaybackException) {
                val f = files.getOrNull(player.currentMediaItemIndex) ?: files.firstOrNull()
                if (f != null) Open.fallback(act, this@VideoScreen, f.path)
            }
        })
        val start = files.indexOfFirst { it.path == startPath }.coerceAtLeast(0)
        player.setMediaItems(files.map { MediaItem.fromUri(android.net.Uri.fromFile(it)) }, start, 0L)
        player.prepare()
        player.playWhenReady = true

        // Starting volume and brightness.
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        volFrac = am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        volSlider.progress = volFrac
        val cur = act.window.attributes.screenBrightness
        briFrac = if (cur >= 0f) cur else try {
            Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
        briSlider.progress = briFrac
        sun.level = briFrac

        updateTitle()
        updateNav()
        applyHud()
        bump()
        post(ticker)
    }

    private fun btn(d: Drawable, sizeDp: Int, desc: String, onClick: () -> Unit): ImageView {
        val v = ImageView(act)
        v.setImageDrawable(d)
        val pad = Ui.dp(if (sizeDp >= 56) 12 else 10)
        v.setPadding(pad, pad, pad, pad)
        v.contentDescription = desc
        v.setOnClickListener {
            bump()
            onClick()
        }
        return v
    }

    // ---- Lifecycle -----------------------------------------------------------------------------

    override fun onShow() {
        act.viewerMode(true)
        act.rotatable(true)
    }

    override fun onDestroyed() {
        alive = false
        removeCallbacks(ticker)
        removeCallbacks(autoHide)
        removeCallbacks(hideLabel)
        removeCallbacks(endAdjust)
        playlist?.let { (it.parent as? ViewGroup)?.removeView(it) }
        try {
            player.release()
        } catch (e: Exception) {
        }
        val lp = act.window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        act.window.attributes = lp
        act.viewerMode(false)
        act.rotatable(false)
    }

    /** The window went away (home button, screen off, PiP closed): pause, unless in picture-in-picture. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && !inPip && alive) player.pause()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // Portrait: no separate 10-second buttons (gestures still seek).
        val land = w > h
        rewBtn.visibility = if (land) VISIBLE else GONE
        fwdBtn.visibility = if (land) VISIBLE else GONE
        if (ow != 0 && (w > h) != (ow > oh)) {
            playlist?.close()
            closeSpeed()
        }
    }

    // ---- Updating the UI -------------------------------------------------------------------------

    private fun tick() {
        if (!alive) return
        val d = player.duration
        val pos = player.currentPosition
        total.text = clockText(if (d > 0L) d else 0L)
        if (!scrubbing) {
            elapsed.text = clockText(pos)
            seek.progress = if (d > 0L) pos.toFloat() / d else 0f
        }
    }

    private fun updateTitle() {
        title.text = files.getOrNull(player.currentMediaItemIndex)?.name ?: ""
    }

    private fun updateNav() {
        nextBtn.alpha = if (player.hasNextMediaItem()) 1f else 0.4f
        prevBtn.alpha = if (files.size > 1) 1f else 0.4f
    }

    private fun updatePlayIcon() {
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        playBtn.setImageDrawable(Icons.glyph(if (playing) Icons.PAUSE else Icons.PLAY, Color.WHITE))
    }

    private fun showLabel(text: String) {
        label.text = text
        label.visibility = VISIBLE
        removeCallbacks(hideLabel)
        postDelayed(hideLabel, 900L)
    }

    // ---- HUD ---------------------------------------------------------------------------------------

    private fun bump() {
        removeCallbacks(autoHide)
        postDelayed(autoHide, 3000L)
    }

    private fun fade(v: View, on: Boolean) {
        v.animate().cancel()
        if (on) {
            v.visibility = VISIBLE
            v.animate().alpha(1f).setDuration(150L).start()
        } else if (v.visibility == VISIBLE) {
            v.animate().alpha(0f).setDuration(150L).withEndAction { v.visibility = INVISIBLE }.start()
        }
    }

    /** One place decides what is visible: controls, sliders, and the lock button. */
    private fun applyHud() {
        val show = hud && !locked && !inPip
        fade(top, show)
        fade(bottom, show)
        fade(muteBtn, show)
        val sliders = (show || adjusting) && !locked && !inPip
        fade(volGroup, sliders)
        fade(briGroup, sliders)
        fade(lockBtn, if (inPip) false else if (locked) lockShown else hud)
    }

    private fun toggleLock() {
        if (!locked) {
            locked = true
            lockShown = true
        } else {
            locked = false
            hud = true
        }
        lockBtn.setImageDrawable(PIcons.get(if (locked) PIcons.LOCK else PIcons.UNLOCK))
        closeSpeed()
        applyHud()
        bump()
    }

    private fun toggleMute() {
        muted = !muted
        player.volume = if (muted) 0f else 1f
        muteBtn.setImageDrawable(PIcons.get(if (muted) PIcons.MUTE else PIcons.VOL))
    }

    // ---- GestureLayer.Host ------------------------------------------------------------------------

    override fun locked(): Boolean = locked

    override fun onTap() {
        if (speedMenu != null) {
            closeSpeed()
            return
        }
        if (locked) lockShown = !lockShown else hud = !hud
        applyHud()
        if (hud || lockShown) bump()
    }

    override fun onPlayPause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0L)
            player.playWhenReady = true
        } else {
            player.playWhenReady = !player.playWhenReady
        }
        updatePlayIcon()
    }

    override fun onSeek(seconds: Int) {
        val d = player.duration
        var t = player.currentPosition + seconds * 1000L
        t = if (d > 0L) t.coerceIn(0L, d) else t.coerceAtLeast(0L)
        player.seekTo(t)
        if (!scrubbing) tick()
    }

    override fun onAdjust(volume: Boolean, delta: Float) {
        removeCallbacks(endAdjust)
        if (!adjusting) {
            adjusting = true
            applyHud()
        }
        if (volume) {
            setVolume((volFrac + delta).coerceIn(0f, 1f))
            volSlider.progress = volFrac
            showLabel("${(volFrac * 100f).toInt()}%")
        } else {
            setBrightness((briFrac + delta).coerceIn(0f, 1f))
            briSlider.progress = briFrac
            showLabel("${(briFrac * 100f).toInt()}%")
        }
    }

    override fun onAdjustEnd() {
        removeCallbacks(endAdjust)
        postDelayed(endAdjust, 700L)
    }

    private fun setVolume(f: Float) {
        volFrac = f
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(f * max), 0)
    }

    private fun setBrightness(f: Float) {
        briFrac = f
        sun.level = f
        val lp = act.window.attributes
        lp.screenBrightness = f.coerceIn(0.02f, 1f)
        act.window.attributes = lp
    }

    // ---- Speed, aspect ratio, playlist, picture-in-picture ----------------------------------------

    private fun speedText(s: Float): String =
        if (s == 0.25f) "0.25x" else String.format(Locale.US, "%.1fx", s)

    private fun toggleSpeedMenu() {
        if (speedMenu != null) {
            closeSpeed()
            return
        }
        val m = LinearLayout(act)
        m.orientation = LinearLayout.VERTICAL
        m.background = Ui.shape(0xF2161616.toInt(), 12)
        m.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(6), Ui.dp(6))
        for (s in floatArrayOf(4f, 3f, 2f, 1f, 0.25f)) {
            val t = Ui.label(act, speedText(s), 14f, Color.WHITE, s == speed, 1)
            t.gravity = Gravity.CENTER
            t.setPadding(Ui.dp(18), Ui.dp(9), Ui.dp(18), Ui.dp(9))
            if (s == speed) t.background = Ui.shape(Ui.alpha(PSlider.ACCENT, 90), 8)
            t.setOnClickListener {
                speed = s
                player.setPlaybackSpeed(s)
                speedBtn.text = speedText(s)
                closeSpeed()
                bump()
            }
            m.addView(t, Ui.lin(Ui.WRAP, Ui.WRAP))
        }
        val a = IntArray(2)
        val r = IntArray(2)
        speedBtn.getLocationInWindow(a)
        getLocationInWindow(r)
        val lp = LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START)
        lp.leftMargin = (a[0] - r[0]).coerceAtLeast(0)
        lp.bottomMargin = (height - (a[1] - r[1]) + Ui.dp(6)).coerceAtLeast(0)
        addView(m, lp)
        speedMenu = m
    }

    private fun closeSpeed() {
        speedMenu?.let { removeView(it) }
        speedMenu = null
    }

    private fun cycleAspect() {
        aspect = (aspect + 1) % 6
        box.setMode(aspect)
        showLabel(
            when (aspect) {
                0 -> act.getString(R.string.v_aspect_original)
                1 -> act.getString(R.string.v_aspect_full)
                2 -> act.getString(R.string.v_aspect_189)
                3 -> act.getString(R.string.v_aspect_169)
                4 -> act.getString(R.string.v_aspect_43)
                else -> act.getString(R.string.v_aspect_best)
            }
        )
    }

    private fun openPlaylist() {
        if (playlist != null) return
        closeSpeed()
        val pl = PlaylistPanel(
            act, files, player.currentMediaItemIndex, true, width > height,
            { i ->
                player.seekTo(i, 0L)
                player.playWhenReady = true
            },
            {
                playlist = null
                bump()
            }
        )
        playlist = pl
        pl.open(this)
    }

    private fun enterPip() {
        if (!act.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Ui.toast(act, R.string.v_pip_unavailable)
            return
        }
        val vs = player.videoSize
        var w = if (vs.width > 0) vs.width else 16
        var h = if (vs.height > 0) vs.height else 9
        if (vs.unappliedRotationDegrees == 90 || vs.unappliedRotationDegrees == 270) {
            val t = w
            w = h
            h = t
        }
        val r = (w.toFloat() * (if (vs.pixelWidthHeightRatio > 0f) vs.pixelWidthHeightRatio else 1f) / h).coerceIn(0.43f, 2.38f)
        try {
            playlist?.close()
            closeSpeed()
            val ok = act.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational((r * 1000f).toInt(), 1000)).build()
            )
            if (!ok) Ui.toast(act, R.string.v_pip_unavailable)
        } catch (e: Exception) {
            Ui.toast(act, R.string.v_pip_unavailable)
        }
    }

    override fun onPip(on: Boolean) {
        inPip = on
        gestures.enabled2 = !on
        if (on) {
            playlist?.close()
            closeSpeed()
        } else {
            hud = true
            bump()
        }
        applyHud()
    }

    // ---- The video surface with its aspect-ratio modes ------------------------------------------------

    /**
     * Holds the SurfaceView and sizes it for the chosen mode: 0 Original (fit, native ratio),
     * 1 Fullscreen (stretch), 2-4 forced 18:9 / 16:9 / 4:3 (fit), 5 Best Fit (fill and crop).
     */
    private class VideoBox(c: Context) : ViewGroup(c) {

        val surface = SurfaceView(c)
        private var ratio = 0f
        private var mode = 0

        init {
            setBackgroundColor(Color.BLACK)
            addView(surface)
        }

        fun setVideo(v: VideoSize) {
            if (v.width <= 0 || v.height <= 0) return
            var w = v.width.toFloat() * (if (v.pixelWidthHeightRatio > 0f) v.pixelWidthHeightRatio else 1f)
            var h = v.height.toFloat()
            if (v.unappliedRotationDegrees == 90 || v.unappliedRotationDegrees == 270) {
                val t = w
                w = h
                h = t
            }
            ratio = w / h
            requestLayout()
        }

        fun setMode(m: Int) {
            mode = m
            requestLayout()
        }

        override fun onMeasure(wSpec: Int, hSpec: Int) {
            setMeasuredDimension(getDefaultSize(0, wSpec), getDefaultSize(0, hSpec))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            val bw = (r - l).toFloat()
            val bh = (b - t).toFloat()
            var w = bw
            var h = bh
            val target = when (mode) {
                2 -> 2f
                3 -> 16f / 9f
                4 -> 4f / 3f
                else -> ratio
            }
            if (mode != 1 && target > 0f) {
                val fitW = bw / bh > target
                if (mode == 5) {
                    // Best Fit: fill the screen, cropping what does not fit.
                    if (fitW) {
                        w = bw
                        h = bw / target
                    } else {
                        h = bh
                        w = bh * target
                    }
                } else if (fitW) {
                    h = bh
                    w = bh * target
                } else {
                    w = bw
                    h = bw / target
                }
            }
            surface.measure(
                MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h.toInt(), MeasureSpec.EXACTLY)
            )
            val x = ((bw - w) / 2f).toInt()
            val y = ((bh - h) / 2f).toInt()
            surface.layout(x, y, x + w.toInt(), y + h.toInt())
        }
    }
}
