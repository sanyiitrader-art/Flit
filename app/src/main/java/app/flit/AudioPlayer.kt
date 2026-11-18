package app.flit

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * The one audio player of the app (main thread only). It outlives the audio screen: the in-app
 * mini-player and the background notification control the same instance. Playback runs through
 * ExoPlayer, which also provides queue handling, shuffle and repeat. When Android cannot decode a
 * file, playback stops and the file is offered to other apps ("Open with").
 */
object Audio {

    const val ORDER = 0
    const val ONE = 1
    const val ALL = 2

    fun interface Listener {
        fun onAudio()
    }

    private val listeners = ArrayList<Listener>()
    private var p: ExoPlayer? = null

    var files: List<File> = emptyList()
        private set

    var shuffle = false
        private set

    /** True while the queue is only what the user ticked in the playlist (the library order must not replace it). */
    var restricted = false
        private set

    /** [ORDER], [ONE] (repeat one) or [ALL] (repeat whole list). */
    var mode = ORDER
        private set

    val active: Boolean get() = p != null && files.isNotEmpty()
    val index: Int get() = p?.currentMediaItemIndex ?: -1
    val current: File? get() = files.getOrNull(index)

    /** Really producing sound right now (drives the spinning disk). */
    val playing: Boolean get() = p?.isPlaying == true

    /** Wants to play (true also while buffering). */
    val wantsPlay: Boolean get() = p?.playWhenReady == true && p?.playbackState != Player.STATE_ENDED

    val position: Long get() = p?.currentPosition ?: 0L

    val duration: Long
        get() {
            val d = p?.duration ?: C.TIME_UNSET
            return if (d == C.TIME_UNSET || d < 0L) 0L else d
        }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    private fun changed() {
        for (l in ArrayList(listeners)) l.onAudio()
        BgService.update()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun build(): ExoPlayer {
        try {
            // The visualizer listens inside the player's own audio pipeline (no permission needed).
            val factory = object : androidx.media3.exoplayer.DefaultRenderersFactory(FlitApp.app) {
                override fun buildAudioSink(
                    context: android.content.Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): androidx.media3.exoplayer.audio.AudioSink? =
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(arrayOf<androidx.media3.common.audio.AudioProcessor>(Spectrum.levels))
                        .build()
            }
            return ExoPlayer.Builder(FlitApp.app, factory).build()
        } catch (e: Throwable) {
            return ExoPlayer.Builder(FlitApp.app).build()
        }
    }

    private fun ensure(): ExoPlayer {
        p?.let { return it }
        val pl = build()
        pl.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true
        )
        pl.setHandleAudioBecomingNoisy(true)
        pl.setWakeMode(C.WAKE_MODE_LOCAL)
        pl.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = changed()
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = changed()
            override fun onPlaybackStateChanged(playbackState: Int) = changed()
            override fun onPlayerError(error: PlaybackException) {
                val f = current
                stop()
                if (f != null) Open.external(FlitApp.ctx(), f.path)
            }
        })
        p = pl
        return pl
    }

    /** Plays [list] starting at [start]. If it is the list already loaded, just jumps to the track. */
    fun start(list: List<File>, start: Int) {
        val pl = ensure()
        val same = files.size == list.size && files.indices.all { files[it].path == list[it].path }
        if (same) {
            pl.seekTo(start, 0L)
        } else {
            files = list
            pl.setMediaItems(list.map { MediaItem.fromUri(Uri.fromFile(it)) }, start, 0L)
            pl.shuffleModeEnabled = shuffle
            pl.repeatMode = repeatOf(mode)
            pl.prepare()
        }
        pl.playWhenReady = true
        changed()
    }

    /**
     * Replaces the queue by [list] (the same tracks in the library's order) around the track that is
     * playing now. That track keeps playing without a break.
     */
    fun reorder(list: List<File>) {
        if (restricted) return
        val pl = p ?: return
        val cur = current ?: return
        val idx = list.indexOfFirst { it.path == cur.path }
        if (idx < 0) return
        val i = pl.currentMediaItemIndex
        if (i < pl.mediaItemCount - 1) pl.removeMediaItems(i + 1, pl.mediaItemCount)
        if (i > 0) pl.removeMediaItems(0, i)
        if (idx > 0) pl.addMediaItems(0, list.subList(0, idx).map { MediaItem.fromUri(Uri.fromFile(it)) })
        if (idx < list.size - 1) pl.addMediaItems(list.subList(idx + 1, list.size).map { MediaItem.fromUri(Uri.fromFile(it)) })
        files = list
        changed()
    }

    /** Plays only [list] (the ticked playlist files); shuffle and repeat then stay among them. */
    fun playOnly(list: List<File>) {
        if (list.isEmpty()) return
        restricted = true
        start(list, 0)
    }

    /** Another audio file was opened: switch to it (in the queue if it is there), staying wherever the player is. */
    fun openTrack(path: String) {
        val i = files.indexOfFirst { it.path == path }
        if (active && i >= 0) {
            jump(i)
        } else {
            restricted = false
            val l = Open.siblings(path)
            start(l, l.indexOfFirst { it.path == path }.coerceAtLeast(0))
        }
    }

    fun toggle() {
        val pl = p ?: return
        if (pl.playbackState == Player.STATE_ENDED) {
            pl.seekTo(0, 0L)
            pl.playWhenReady = true
        } else {
            pl.playWhenReady = !pl.playWhenReady
        }
        changed()
    }

    fun pause() {
        val pl = p ?: return
        if (pl.playWhenReady) {
            pl.playWhenReady = false
            changed()
        }
    }

    fun next() {
        p?.seekToNextMediaItem()
    }

    fun prev() {
        p?.seekToPrevious()
    }

    fun jump(i: Int) {
        val pl = p ?: return
        if (i in files.indices) {
            pl.seekTo(i, 0L)
            pl.playWhenReady = true
            changed()
        }
    }

    fun seek(ms: Long) {
        p?.seekTo(ms)
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        p?.shuffleModeEnabled = shuffle
        changed()
    }

    /** Order, then Repeat One, then Repeat Whole, then Order again. */
    fun cycleMode() {
        mode = (mode + 1) % 3
        p?.repeatMode = repeatOf(mode)
        changed()
    }

    private fun repeatOf(m: Int): Int = when (m) {
        ONE -> Player.REPEAT_MODE_ONE
        ALL -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    /** Stops playback, releases the player and removes the notification. */
    fun stop() {
        val pl = p ?: return
        p = null
        files = emptyList()
        restricted = false
        Picks.audio.clear()
        try {
            pl.release()
        } catch (e: Exception) {
        }
        changed()
    }
}
