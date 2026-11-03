package app.flit

import android.app.ActivityManager
import android.content.Context

/**
 * Every tunable of the transfer engine in one place. Defaults are starting points to be confirmed
 * by measurement; each can be overridden from the hidden tuning panel (Prefs keys "t_*").
 */
object Tuning {

    const val ATTEMPTS = 3
    const val SMALL_FILE = 512 * 1024
    const val BATCH_FILES = 64
    const val BATCH_BYTES = 4L shl 20
    const val CHECKPOINT_MS = 2000L
    const val CHECKPOINT_BYTES = 32L shl 20
    const val PROBE_MIN_REMAINING = 64L shl 20
    const val GAIN = 1.12
    const val MAX_STREAMS = 4
    const val MAX_FILES = 4

    val cores: Int = Runtime.getRuntime().availableProcessors()

    val lowRam: Boolean by lazy {
        (FlitApp.app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    }

    /** Chunk size in bytes (the Sender decides; the Receiver follows the REQUEST). Default 2 MiB. */
    val chunkSize: Int
        get() = Prefs.int("t_chunk", 2048).coerceIn(256, 16384) * 1024

    /** I/O buffer per stream. Default 128 KiB (64 KiB on low-RAM devices). */
    val bufSize: Int
        get() = Prefs.int("t_buf", if (lowRam) 64 else 128).coerceIn(16, 1024) * 1024

    /** Outstanding GET requests per data stream. */
    val outstanding: Int
        get() = Prefs.int("t_out", 2).coerceIn(1, 4)

    val fallocate: Boolean
        get() = Prefs.flag("t_falloc", true)

    /** Ask Android 10+ for a 5 GHz group. Off by default (not every device supports it). */
    val band5: Boolean
        get() = Prefs.flag("t_band5", false)

    /** Explicit socket buffer in bytes; 0 keeps the OS default (and its auto-tuning). */
    val sockBuf: Int
        get() = Prefs.int("t_sockbuf", 0).coerceIn(0, 4096) * 1024

    /** Upper bound of simultaneous data streams for one session. */
    fun maxStreams(multiPeer: Boolean): Int {
        val forced = Prefs.int("t_streams", 0)
        if (forced in 1..MAX_STREAMS) return forced
        var m = if (lowRam || cores <= 2) 2 else if (cores <= 4) 3 else MAX_STREAMS
        if (multiPeer) m = minOf(m, 2) // several receivers already share one radio
        return m
    }

    // ---- Results of the last transfers (shown / copied by the tuning panel) ----------------

    fun report(line: String) {
        Prefs.putStr("t_last", (line + "\n" + Prefs.str("t_last")).take(4000))
    }

    fun lastResults(): String = Prefs.str("t_last")
}

/**
 * Adaptive stream count: hill-climbing on measured goodput. One instance per receiving session.
 * Call [tick] once per second from the session's monitor thread. It returns +1 (open one more data
 * stream), -1 (close one) or 0. The caller acts on it and, if the action did not happen, [sync]s.
 *
 *  1. Warm up (>= 2 s and >= 16 MiB moved).
 *  2. Probe: remember the base rate, add a stream, discard a 2 s settle, measure 3 s.
 *  3. Keep it only if goodput rose by at least 12 %; otherwise drop it and back off (15 s, 30 s ... 120 s).
 *  Paused periods freeze everything.
 */
class StreamController(private val max: Int) {

    var n = 1
        private set

    private var phase = 0 // 0 warm-up, 1 stable, 2 settle, 3 measure
    private var sec = 0
    private var last = 0L
    private var warm = 0L
    private var warmSec = 0
    private var phaseAt = 0
    private var nextProbe = 0
    private var backoff = 15
    private var base = 0.0
    private var sum = 0L
    private var cnt = 0
    private val rates = ArrayList<Long>()

    fun sync(count: Int) {
        n = count.coerceIn(1, max)
    }

    /** A stream failed or stalled: stop probing for a while. */
    fun trouble(count: Int) {
        n = count.coerceIn(1, max)
        phase = 1
        nextProbe = sec + 30
    }

    private fun avg(k: Int): Double {
        val m = minOf(k, rates.size)
        if (m == 0) return 0.0
        var s = 0L
        for (i in rates.size - m until rates.size) s += rates[i]
        return s.toDouble() / m
    }

    fun tick(total: Long, remaining: Long, paused: Boolean): Int {
        val d = total - last
        last = total
        if (paused || max <= 1) return 0
        sec++
        rates.add(d)
        if (rates.size > 8) rates.removeAt(0)
        when (phase) {
            0 -> {
                warm += d
                warmSec++
                if (warmSec >= 2 && warm >= (16L shl 20)) {
                    phase = 1
                    nextProbe = sec
                }
            }
            1 -> if (n < max && sec >= nextProbe && remaining >= Tuning.PROBE_MIN_REMAINING) {
                base = avg(2)
                phase = 2
                phaseAt = sec
                n++
                return 1
            }
            2 -> if (sec - phaseAt >= 2) {
                phase = 3
                phaseAt = sec
                sum = 0L
                cnt = 0
            }
            3 -> {
                sum += d
                cnt++
                if (cnt >= 3) {
                    val r = sum.toDouble() / cnt
                    phase = 1
                    if (r >= base * Tuning.GAIN) {
                        nextProbe = sec + 2
                        backoff = 15
                    } else {
                        nextProbe = sec + backoff
                        backoff = minOf(backoff * 2, 120)
                        n--
                        return -1
                    }
                }
            }
        }
        return 0
    }
}