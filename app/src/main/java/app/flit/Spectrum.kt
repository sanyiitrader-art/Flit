package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** One shared analyser: the audio player sends its sound through [levels], the visualizer reads it. */
object Spectrum {
    val levels = Levels()
}

/**
 * Listens to the sound that is being played (no microphone, no permission: it sits inside the player's
 * own audio pipeline and passes every sample through unchanged) and keeps [BANDS] frequency bands, the
 * overall [energy] and a [beat] pulse for the visualizer. Values are 0..1.
 */
@OptIn(UnstableApi::class)
class Levels : BaseAudioProcessor() {

    val bands = FloatArray(BANDS)

    @Volatile
    var energy = 0f

    /** Set to 1 on a beat; the visualizer takes it and sets it back to 0. */
    @Volatile
    var beat = 0f

    private val ring = FloatArray(N)
    private val win = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private var fill = 0
    private var lowAvg = 0f
    private var cool = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Only 16-bit sound is analysed; anything else passes untouched (the processor stays inactive).
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush() {
        fill = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val ch = inputAudioFormat.channelCount.coerceAtLeast(1)
        var i = inputBuffer.position()
        val end = inputBuffer.limit()
        while (i + 2 * ch <= end) {
            var sum = 0
            for (k in 0 until ch) sum += inputBuffer.getShort(i + 2 * k).toInt()
            ring[fill++] = sum / (32768f * ch)
            i += 2 * ch
            if (fill == N) {
                analyse()
                System.arraycopy(ring, N / 2, ring, 0, N / 2)
                fill = N / 2
            }
        }
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    private fun analyse() {
        for (i in 0 until N) {
            re[i] = ring[i] * win[i]
            im[i] = 0f
        }
        fft()
        var total = 0f
        for (b in 0 until BANDS) {
            val lo = Math.pow(MAXBIN, b.toDouble() / BANDS).toInt().coerceAtLeast(1)
            val hi = Math.pow(MAXBIN, (b + 1).toDouble() / BANDS).toInt().coerceAtLeast(lo + 1)
            var m = 0f
            var c = 0
            for (k in lo until hi.coerceAtMost(N / 2)) {
                m += sqrt(re[k] * re[k] + im[k] * im[k])
                c++
            }
            val mag = if (c > 0) m / c / (N / 4f) else 0f
            val v = ((20.0 * log10(mag + 1e-6) + 62.0) / 62.0).toFloat().coerceIn(0f, 1f)
            bands[b] += (v - bands[b]) * (if (v > bands[b]) 0.65f else 0.2f)
            total += bands[b]
        }
        energy = (total / BANDS).coerceIn(0f, 1f)
        var low = 0f
        for (b in 0 until 4) low += bands[b]
        low /= 4f
        lowAvg = lowAvg * 0.93f + low * 0.07f
        if (cool > 0) cool--
        if (cool == 0 && low > lowAvg * 1.3f + 0.06f) {
            beat = 1f
            cool = 6
        }
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= N) {
            val ang = -2.0 * PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            var i = 0
            while (i < N) {
                var cr = 1f
                var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k
                    val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    companion object {
        const val BANDS = 32
        private const val N = 1024
        private const val MAXBIN = 300.0
    }
}

/**
 * Radial audio visualizer drawn around the disk: a spiky circular waveform that follows the music, and
 * small triangles and polygons that fly outward from it, turning, shrinking and fading until they vanish
 * and are reused. The louder the music, the longer the spikes and the denser the particles; beats make a
 * burst. Colour is the accent colour, or the picture's most used colour.
 */
class VisualView(c: Context) : View(c) {

    var color = PSlider.ACCENT
    var diskRadius = 0f

    private class P {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var rot = 0f
        var vr = 0f
        var size = 0f
        var life = 0f
        var max = 1f
        var sides = 3
        var alive = false
    }

    private val ps = Array(150) { P() }
    private val sm = FloatArray(SPIKES)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rnd = java.util.Random(7)
    private var last = 0L
    private var acc = 0f
    private var pulse = 0f

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceIn(0.001f, 0.05f)
        last = now
        val lv = Spectrum.levels
        val playing = Audio.playing
        val e = if (playing) lv.energy else 0f
        if (lv.beat > 0f) {
            if (playing) pulse = 1f
            lv.beat = 0f
        }
        pulse = (pulse - dt * 3.5f).coerceAtLeast(0f)
        val cx = width / 2f
        val cy = height / 2f
        val r0 = diskRadius * 1.05f + Ui.dpf(3)
        val maxLen = diskRadius * (0.28f + 0.5f * e + 0.25f * pulse)

        // ---- Spiky circular waveform ----
        var busy = false
        for (i in 0 until SPIKES) {
            val t = abs((i.toFloat() / SPIKES) * 2f - 1f) // mirrored around the circle
            val band = (t * (Levels.BANDS - 1)).toInt()
            val v = if (playing) lv.bands[band] else 0f
            val jitter = if (v > 0.05f) 0.75f + 0.5f * rnd.nextFloat() else 1f
            val target = v * v * maxLen * 1.6f * jitter
            sm[i] += (target - sm[i]) * (if (target > sm[i]) 0.5f else 0.18f)
            if (sm[i] > 0.4f) busy = true
        }
        path.reset()
        for (i in 0..SPIKES) {
            val k = i % SPIKES
            val a = (k.toFloat() / SPIKES) * 2f * PI.toFloat() - PI.toFloat() / 2f
            val r = r0 + sm[k]
            val x = cx + r * cos(a)
            val y = cy + r * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = (color and 0x00FFFFFF) or (0x2A shl 24)
        c.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dpf(2f)
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = (color and 0x00FFFFFF) or (0xE6 shl 24)
        c.drawPath(path, paint)

        // ---- Geometric particles ----
        // Very quiet: a trickle; loud: dense. A beat adds a burst.
        acc += dt * (4f + e * e * 130f)
        var spawn = acc.toInt()
        acc -= spawn
        if (pulse > 0.95f) spawn += 6 + (e * 14f).toInt()
        if (playing) {
            for (p in ps) {
                if (spawn <= 0) break
                if (!p.alive) {
                    val a = rnd.nextFloat() * 2f * PI.toFloat()
                    val k = (((a + PI.toFloat() / 2f) / (2f * PI.toFloat()) * SPIKES).toInt() % SPIKES + SPIKES) % SPIKES
                    val r = r0 + sm[k]
                    val sp = Ui.dpf(28f) + Ui.dpf(150f) * e * (0.4f + rnd.nextFloat())
                    p.x = cx + r * cos(a)
                    p.y = cy + r * sin(a)
                    p.vx = cos(a) * sp + (rnd.nextFloat() - 0.5f) * Ui.dpf(30f)
                    p.vy = sin(a) * sp + (rnd.nextFloat() - 0.5f) * Ui.dpf(30f)
                    p.rot = rnd.nextFloat() * 6.28f
                    p.vr = (rnd.nextFloat() - 0.5f) * 8f
                    p.size = Ui.dpf(4f + rnd.nextFloat() * 7f) * (0.7f + e)
                    p.max = 0.9f + rnd.nextFloat() * 0.9f
                    p.life = p.max
                    p.sides = 3 + rnd.nextInt(3)
                    p.alive = true
                    spawn--
                }
            }
        }
        paint.style = Paint.Style.FILL
        for (p in ps) {
            if (!p.alive) continue
            busy = true
            p.life -= dt
            if (p.life <= 0f) {
                p.alive = false
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.rot += p.vr * dt
            // Direction drifts a little as it travels.
            val tw = 0.6f * dt
            val vx = p.vx * cos(tw) - p.vy * sin(tw)
            p.vy = p.vx * sin(tw) + p.vy * cos(tw)
            p.vx = vx
            val t = p.life / p.max // 1 -> 0: smaller and fainter the further it has gone
            val s = p.size * (0.25f + 0.75f * t)
            paint.color = (color and 0x00FFFFFF) or ((230f * t).toInt().coerceIn(0, 255) shl 24)
            path.reset()
            for (q in 0 until p.sides) {
                val a = p.rot + q * 2f * PI.toFloat() / p.sides
                val x = p.x + s * cos(a)
                val y = p.y + s * sin(a)
                if (q == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, paint)
        }
        if (playing || busy) postInvalidateOnAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        last = 0L
        invalidate()
    }

    private companion object {
        const val SPIKES = 120
    }
}
