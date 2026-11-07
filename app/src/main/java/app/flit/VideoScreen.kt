package app.flit

import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
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

/** Videos the user opened, most recent first (kept in the app's preferences). */
private object Recents {
    private const val KEY = "vid_recent"
    private const val MAX = 400

    fun list(): List<String> = Prefs.str(KEY).split('\n').filter { it.isNotEmpty() }

    fun touch(path: String) {
        val out = ArrayList<String>()
        out.add(path)
        for (p in list()) if (p != path && out.size < MAX) out.add(p)
        Prefs.putStr(KEY, out.joinToString("\n"))
    }
}

/**
 * Built-in video player (Media3 ExoPlayer; only containers and codecs Android decodes, anything
 * else is offered to other apps). Portrait and landscape; icon-only white controls; native
 * picture-in-picture; gestures from [GestureLayer].
 *
 *  - Volume and brightness sliders appear only while you drag on that side, one at a time. The
 *    brightness thumb is the sun, which turns and grows as brightness goes up.
 *  - Left edge: lock and rotate. Right edge: sound (mute) and "audio only in background": when on
 *    (terracotta), leaving the app keeps the sound playing, and coming back continues the video at
 *    the point the sound reached.
 *  - Playlist: every video on the phone, most recently opened first. Videos Flit cannot play are
 *    listed too and open in Android's "Open with".
 */
class VideoScreen(act: MainActivity, private val startPath: String) : Screen(act), GestureLayer.Host {

    /** The playable queue. Starts with the opened video and is completed when the library list arrives. */
    private var files: List<File> = listOf(File(startPath))

    /** Every video on the phone (for the playlist panel). */
    private var all: List<File> = emptyList()

    private val player: ExoPlayer = ExoPlayer.Builder(act).build()
    private val box = VideoBox(act)
    private val gestures = GestureLayer(act)

    private val top = LinearLayout(act)
    private val title = Ui.label(act, "", 16f, Color.WHITE, true, 1)
    private val bottom = LinearLayout(act)
    private val elapsed = Ui.label(act, "00:00", 12f, Color.WHITE, false, 1)
    private val total = Ui.label(act, "00:00", 12f, Color.WHITE, false, 1)
    private val seek = PSlider(act)
    private val ctlRow = FrameLayout(act)
    private val utilRow = LinearLayout(act)

    private val pipBtn: ImageView
    private val speedBtn: TextView
    private val rewBtn: ImageView
    private val prevBtn: ImageView
    private val playBtn: ImageView
    private val nextBtn: ImageView
    private val fwdBtn: ImageView
    private val aspectBtn: ImageView
    private val lockBtn: ImageView
    private val rotateBtn: ImageView
    private val muteBtn: ImageView
    private val audioBtn: ImageView
    private val volSlider = PSlider(act, true)
    private val briSlider = PSlider(act, true)
    private val sun = PIcons.get(PIcons.BRIGHT)
    private val label = TextView(act)

    private val am = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var volFrac = 0f
    private var briFrac = 0.5f

    private var hud = true
    private var locked = false
    private var lockShown = false
    private var adjVol = false
    private var adjBri = false
    private var inPip = false
    private var muted = false
    private var audioMode = false
    private var inBg = false
    private var scrubbing = false
    private var alive = true
    private var speed = 1f
    private var aspect = 0
    private var landMode: Boolean? = null
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
        adjVol = false
        adjBri = false
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
        Recents.touch(startPath)

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

        // ---- Bottom: seek bar with times, then the control rows (arranged by layoutControls) ----
        bottom.orientation = LinearLayout.VERTICAL
        bottom.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Ui.alpha(Color.BLACK, 170), 0))

        val row1 = LinearLayout(act)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL
        row1.addView(elapsed, Ui.lin(Ui.WRAP, Ui.WRAP))
        row1.addView(seek, Ui.lin(0, Ui.dp(32), 1f).also { it.setMargins(Ui.dp(4), 0, Ui.dp(4), 0) })
        row1.addView(total, Ui.lin(Ui.WRAP, Ui.WRAP))
        bottom.addView(row1, Ui.lin(Ui.MATCH, Ui.WRAP))

        pipBtn = btn(PIcons.get(PIcons.PIP), 48, act.getString(R.string.v_pip)) { enterPip() }
        speedBtn = Ui.label(act, speedText(1f), 15f, Color.WHITE, true, 1)
        speedBtn.gravity = Gravity.CENTER
        speedBtn.setPadding(Ui.dp(12), 0, Ui.dp(12), 0)
        speedBtn.contentDescription = act.getString(R.string.v_speed)
        speedBtn.setOnClickListener {
            bump()
            toggleSpeedMenu()
        }
        rewBtn = btn(PIcons.get(PIcons.REW10), 56, act.getString(R.string.v_rewind)) { onSeek(-10) }
        prevBtn = btn(PIcons.get(PIcons.PREV), 56, act.getString(R.string.v_prev)) { player.seekToPrevious() }
        playBtn = btn(Icons.glyph(Icons.PAUSE, Color.WHITE), 72, act.getString(R.string.v_play_pause)) { onPlayPause() }
        nextBtn = btn(PIcons.get(PIcons.NEXT), 56, act.getString(R.string.v_next)) { player.seekToNextMediaItem() }
        fwdBtn = btn(PIcons.get(PIcons.FWD10), 56, act.getString(R.string.v_forward)) { onSeek(10) }
        aspectBtn = btn(PIcons.get(PIcons.ASPECT), 48, act.getString(R.string.v_aspect)) { cycleAspect() }

        bottom.addView(ctlRow, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(8) })
        utilRow.orientation = LinearLayout.HORIZONTAL
        utilRow.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(utilRow, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(6) })
        addView(bottom, LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM))

        // ---- Edge buttons: left lock + rotate, right sound + audio-in-background ----
        lockBtn = btn(PIcons.get(PIcons.UNLOCK), 48, act.getString(R.string.v_lock)) { toggleLock() }
        rotateBtn = btn(PIcons.get(PIcons.ROTATE), 48, act.getString(R.string.v_rotate)) { act.forceOrientation(width <= height) }
        muteBtn = btn(PIcons.get(PIcons.VOL), 48, act.getString(R.string.v_sound)) { toggleMute() }
        audioBtn = btn(PIcons.get(PIcons.HEADSET, Color.WHITE), 48, act.getString(R.string.v_audio_bg)) { toggleAudioMode() }

        val lc = LinearLayout(act)
        lc.orientation = LinearLayout.VERTICAL
        lc.addView(lockBtn, Ui.lin(Ui.dp(48), Ui.dp(48)))
        lc.addView(rotateBtn, Ui.lin(Ui.dp(48), Ui.dp(48)).also { it.topMargin = Ui.dp(10) })
        addView(lc, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.START or Gravity.CENTER_VERTICAL).also { it.leftMargin = Ui.dp(10) })

        val rc = LinearLayout(act)
        rc.orientation = LinearLayout.VERTICAL
        rc.addView(muteBtn, Ui.lin(Ui.dp(48), Ui.dp(48)))
        rc.addView(audioBtn, Ui.lin(Ui.dp(48), Ui.dp(48)).also { it.topMargin = Ui.dp(10) })
        addView(rc, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = Ui.dp(10) })

        // ---- Sliders: shown only while dragging, one at a time, no icons ----
        briSlider.thumbIcon = sun
        volSlider.visibility = INVISIBLE
        briSlider.visibility = INVISIBLE
        addView(volSlider, LayoutParams(Ui.dp(40), Ui.dp(190), Gravity.START or Gravity.CENTER_VERTICAL).also { it.leftMargin = Ui.dp(76) })
        addView(briSlider, LayoutParams(Ui.dp(56), Ui.dp(190), Gravity.END or Gravity.CENTER_VERTICAL).also { it.rightMargin = Ui.dp(68) })

        label.setTextColor(Color.WHITE)
        label.textSize = 20f
        label.typeface = android.graphics.Typeface.DEFAULT_BOLD
        label.setPadding(Ui.dp(16), Ui.dp(8), Ui.dp(16), Ui.dp(8))
        label.background = Ui.shape(Ui.alpha(Color.BLACK, 150), 12)
        label.visibility = GONE
        addView(label, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).also { it.topMargin = Ui.dp(76) })

        layoutControls(false)

        // ---- Slider events ----
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
                files.getOrNull(player.currentMediaItemIndex)?.let { Recents.touch(it.path) }
                updateTitle()
                updateNav()
                if (inBg) syncBg()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    hud = true
                    applyHud()
                    bump()
                }
                updatePlayIcon()
                if (inBg) syncBg()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlayIcon()
                if (inBg) syncBg()
            }

            override fun onPlayerError(error: PlaybackException) {
                val f = files.getOrNull(player.currentMediaItemIndex) ?: files.firstOrNull()
                if (f != null) Open.fallback(act, this@VideoScreen, f.path)
            }
        })
        player.setMediaItems(files.map { item(it) }, 0, 0L)
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
        loadLibrary()
    }

    private fun item(f: File): MediaItem = MediaItem.fromUri(Uri.fromFile(f))

    private fun btn(d: Drawable, sizeDp: Int, desc: String, onClick: () -> Unit): ImageView {
        val v = ImageView(act)
        v.setImageDrawable(d)
        val pad = Ui.dp(if (sizeDp >= 72) 12 else 10)
        v.setPadding(pad, pad, pad, pad)
        v.contentDescription = desc
        v.setOnClickListener {
            bump()
            onClick()
        }
        return v
    }

    // ---- Control rows: arranged for portrait or landscape ------------------------------------------

    private fun free(vararg views: View) {
        for (v in views) (v.parent as? ViewGroup)?.removeView(v)
    }

    private fun lp(dp: Int, endMarginDp: Int = 0): LinearLayout.LayoutParams =
        Ui.lin(Ui.dp(dp), Ui.dp(dp)).also { it.rightMargin = Ui.dp(endMarginDp) }

    /**
     * Landscape: one row, play in the middle with 10-second buttons, previous and next around it,
     * PiP and speed on the left, aspect ratio on the right. Portrait: previous / play / next
     * centred with room around them, and a second row with PiP, speed and aspect ratio spread evenly.
     * Re-parenting the buttons (instead of hiding some) keeps every layout exact after a rotation.
     */
    private fun layoutControls(land: Boolean) {
        if (landMode == land) return
        landMode = land
        free(pipBtn, speedBtn, rewBtn, prevBtn, playBtn, nextBtn, fwdBtn, aspectBtn)
        ctlRow.removeAllViews()
        utilRow.removeAllViews()
        if (land) {
            val left = LinearLayout(act)
            left.gravity = Gravity.CENTER_VERTICAL
            left.addView(pipBtn, lp(48))
            left.addView(speedBtn, Ui.lin(Ui.WRAP, Ui.dp(48)))
            val center = LinearLayout(act)
            center.gravity = Gravity.CENTER
            center.addView(rewBtn, lp(60, 16))
            center.addView(prevBtn, lp(60, 16))
            center.addView(playBtn, lp(76, 16))
            center.addView(nextBtn, lp(60, 16))
            center.addView(fwdBtn, lp(60))
            val right = LinearLayout(act)
            right.gravity = Gravity.CENTER_VERTICAL
            right.addView(aspectBtn, lp(48))
            ctlRow.addView(left, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.START or Gravity.CENTER_VERTICAL))
            ctlRow.addView(center, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
            ctlRow.addView(right, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.END or Gravity.CENTER_VERTICAL))
            utilRow.visibility = GONE
            bottom.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(8))
        } else {
            val center = LinearLayout(act)
            center.gravity = Gravity.CENTER
            center.addView(prevBtn, lp(60, 28))
            center.addView(playBtn, lp(76, 28))
            center.addView(nextBtn, lp(60))
            ctlRow.addView(center, LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
            utilRow.addView(pipBtn, lp(48))
            utilRow.addView(View(act), Ui.lin(0, 1, 1f))
            utilRow.addView(speedBtn, Ui.lin(Ui.WRAP, Ui.dp(48)))
            utilRow.addView(View(act), Ui.lin(0, 1, 1f))
            utilRow.addView(aspectBtn, lp(48))
            utilRow.visibility = VISIBLE
            bottom.setPadding(Ui.dp(24), Ui.dp(16), Ui.dp(24), Ui.dp(56))
        }
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
        if (VBg.active) {
            VBg.active = false
            VBg.onToggle = null
            VBg.onClose = null
            BgService.update()
        }
        val lpw = act.window.attributes
        lpw.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        act.window.attributes = lpw
        act.viewerMode(false)
        act.rotatable(false)
    }

    /**
     * The window went away (home button, screen off, PiP closed). Normally the video pauses. With
     * "audio in background" on, only the picture stops (the video track is switched off) and the sound
     * keeps playing through the background service; when the window is back, the picture comes back at
     * the point the sound has reached.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!alive || inPip) return
        if (visibility != VISIBLE) {
            if (audioMode && player.playWhenReady) goBackground() else player.pause()
        } else if (inBg) {
            comeBack()
        }
    }

    private fun setVideoTrack(on: Boolean) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !on).build()
    }

    private fun goBackground() {
        inBg = true
        setVideoTrack(false)
        VBg.onToggle = {
            player.playWhenReady = !player.playWhenReady
            syncBg()
        }
        VBg.onClose = {
            player.pause()
            VBg.active = false
            BgService.update()
        }
        VBg.active = true
        syncBg()
    }

    private fun comeBack() {
        inBg = false
        setVideoTrack(true)
        VBg.active = false
        VBg.onToggle = null
        VBg.onClose = null
        BgService.update()
        updatePlayIcon()
    }

    private fun syncBg() {
        VBg.name = files.getOrNull(player.currentMediaItemIndex)?.name ?: ""
        VBg.playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
        BgService.update()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // Re-arranging inside a layout pass can leave stale sizes, so do it right after.
        post { if (alive) layoutControls(w > h) }
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

    /** One place decides what is visible: controls, edge buttons, the lock button and the sliders. */
    private fun applyHud() {
        val show = hud && !locked && !inPip
        fade(top, show)
        fade(bottom, show)
        fade(muteBtn, show)
        fade(audioBtn, show)
        fade(rotateBtn, show)
        val sliders = !locked && !inPip
        fade(volSlider, adjVol && sliders)
        fade(briSlider, adjBri && sliders)
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

    private fun toggleAudioMode() {
        audioMode = !audioMode
        audioBtn.setImageDrawable(PIcons.get(PIcons.HEADSET, if (audioMode) PSlider.ACCENT else Color.WHITE))
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
        if (volume) {
            if (!adjVol) {
                adjVol = true
                adjBri = false
                applyHud()
            }
            setVolume((volFrac + delta).coerceIn(0f, 1f))
            volSlider.progress = volFrac
            showLabel("${(volFrac * 100f).toInt()}%")
        } else {
            if (!adjBri) {
                adjBri = true
                adjVol = false
                applyHud()
            }
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
        val lpw = act.window.attributes
        lpw.screenBrightness = f.coerceIn(0.02f, 1f)
        act.window.attributes = lpw
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
        val lpm = LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START)
        lpm.leftMargin = (a[0] - r[0]).coerceAtLeast(0)
        lpm.bottomMargin = (height - (a[1] - r[1]) + Ui.dp(6)).coerceAtLeast(0)
        addView(m, lpm)
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

    // ---- Playlist: every video on the phone, most recently opened first -------------------------------

    /** Reads all videos from the media library in the background, orders them, and completes the queue. */
    private fun loadLibrary() {
        Files.pool.execute {
            val paths = ArrayList<String>()
            val seen = HashSet<String>()
            try {
                Files.streamMedia(Types.VIDEO, { false }) { list ->
                    for (m in list) if (seen.add(m.path)) paths.add(m.path)
                }
            } catch (e: Exception) {
            }
            if (seen.add(startPath)) paths.add(0, startPath)
            val rank = HashMap<String, Int>()
            Recents.list().forEachIndexed { i, p -> rank[p] = i }
            // Stable sort: never-opened videos stay in the library's own newest-first order.
            val sorted = paths.filter { File(it).isFile }.sortedWith(compareBy<String> { rank[it] ?: Int.MAX_VALUE })
            val allFiles = sorted.map { File(it) }
            val playable = allFiles.filter { Open.kind(it.path) == Open.K_VIDEO }
            FlitApp.main.post { applyLibrary(allFiles, playable) }
        }
    }

    private fun applyLibrary(allFiles: List<File>, playable: List<File>) {
        if (!alive) return
        all = allFiles
        if (player.mediaItemCount != 1) return
        val curPath = files.getOrNull(player.currentMediaItemIndex)?.path ?: return
        val idx = playable.indexOfFirst { it.path == curPath }
        if (idx < 0) return
        // The current video keeps playing; the others are added around it.
        if (idx > 0) player.addMediaItems(0, playable.subList(0, idx).map { item(it) })
        if (idx < playable.size - 1) player.addMediaItems(playable.subList(idx + 1, playable.size).map { item(it) })
        files = playable
        updateTitle()
        updateNav()
    }

    private fun openPlaylist() {
        if (playlist != null) return
        closeSpeed()
        val list = if (all.isNotEmpty()) all else files
        val curPath = files.getOrNull(player.currentMediaItemIndex)?.path
        val pl = PlaylistPanel(
            act, list, list.indexOfFirst { it.path == curPath }.coerceAtLeast(0), true, width > height,
            { i -> pick(list[i]) },
            {
                playlist = null
                bump()
            }
        )
        playlist = pl
        pl.open(this)
    }

    /** A row of the playlist was tapped: play it here, or hand it to Android's "Open with" if Flit cannot. */
    private fun pick(f: File) {
        Recents.touch(f.path)
        if (Open.kind(f.path) == Open.K_VIDEO) {
            val i = files.indexOfFirst { it.path == f.path }
            if (i >= 0) {
                player.seekTo(i, 0L)
                player.playWhenReady = true
                return
            }
        }
        Open.external(act, f.path)
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
