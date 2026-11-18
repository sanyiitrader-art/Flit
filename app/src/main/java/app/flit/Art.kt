package app.flit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File

/** A track's picture: the picture itself, a tiny version for very blurry backgrounds, and its most used colour. */
class ArtInfo(val bmp: Bitmap, val blur: Bitmap, val color: Int)

/**
 * Album art of audio files: the picture embedded in the file, or cover.jpg / folder.jpg next to it.
 * Loaded once in the background and kept for the last few tracks.
 */
object Art {

    private val cache = LruCache<String, ArtInfo>(8)
    private val none = HashSet<String>()

    fun peek(path: String): ArtInfo? = cache.get(path)

    /** Calls [cb] on the main thread with the track's art, or null when it has none. */
    fun load(path: String, cb: (ArtInfo?) -> Unit) {
        cache.get(path)?.let {
            cb(it)
            return
        }
        if (none.contains(path)) {
            cb(null)
            return
        }
        Files.pool.execute {
            val a = try {
                read(path)
            } catch (e: Throwable) {
                null
            }
            FlitApp.main.post {
                if (a != null) cache.put(path, a) else none.add(path)
                cb(a)
            }
        }
    }

    private fun read(path: String): ArtInfo? {
        var raw: Bitmap? = null
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(path)
            val b = m.embeddedPicture
            if (b != null) raw = decode(b)
        } catch (e: Exception) {
        } finally {
            try {
                m.release()
            } catch (e: Exception) {
            }
        }
        if (raw == null) {
            val dir = File(path).parentFile
            for (n in arrayOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "albumart.jpg", "front.jpg")) {
                val f = File(dir, n)
                if (f.isFile) {
                    raw = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 })
                    if (raw != null) break
                }
            }
        }
        val r = raw ?: return null
        return ArtInfo(r, tiny(r), dominant(r))
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / s > 800 || o.outHeight / s > 800) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
    }

    /** Scaled far down: stretched back up it is a deeply blurred version of the picture. */
    private fun tiny(b: Bitmap): Bitmap {
        val w = 24
        val h = (24f * b.height / b.width).toInt().coerceIn(8, 48)
        return Bitmap.createScaledBitmap(b, w, h, true)
    }

    /** The most used colour of the picture (bright and colourful ones win over grey, black and white). */
    private fun dominant(b: Bitmap): Int {
        val s = Bitmap.createScaledBitmap(b, 32, 32, true)
        val bins = IntArray(24 * 3)
        val sumR = LongArray(bins.size)
        val sumG = LongArray(bins.size)
        val sumB = LongArray(bins.size)
        val hsv = FloatArray(3)
        var ar = 0L
        var ag = 0L
        var ab = 0L
        var n = 0
        for (y in 0 until 32) for (x in 0 until 32) {
            val c = s.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            ar += Color.red(c)
            ag += Color.green(c)
            ab += Color.blue(c)
            n++
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.2f || hsv[2] > 0.97f) continue
            val k = (hsv[0] / 15f).toInt().coerceIn(0, 23) * 3 + (if (hsv[1] > 0.6f) 2 else 1) - 1
            bins[k]++
            sumR[k] += Color.red(c)
            sumG[k] += Color.green(c)
            sumB[k] += Color.blue(c)
        }
        var best = -1
        for (k in bins.indices) if (bins[k] > 0 && (best < 0 || bins[k] > bins[best])) best = k
        val col = if (best >= 0) {
            Color.rgb((sumR[best] / bins[best]).toInt(), (sumG[best] / bins[best]).toInt(), (sumB[best] / bins[best]).toInt())
        } else if (n > 0) {
            Color.rgb((ar / n).toInt(), (ag / n).toInt(), (ab / n).toInt())
        } else {
            PSlider.ACCENT
        }
        // Never too dark to see.
        Color.colorToHSV(col, hsv)
        hsv[2] = hsv[2].coerceAtLeast(0.62f)
        return Color.HSVToColor(hsv)
    }
}

/** Rounded background made of a (blurred) picture, darkened a little so white text and icons read well. */
class ArtBg(private val bmp: Bitmap, private val radius: Float) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val r = RectF()

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val m = Matrix()
        val s = maxOf(b.width().toFloat() / bmp.width, b.height().toFloat() / bmp.height)
        m.setScale(s, s)
        m.postTranslate((b.width() - bmp.width * s) / 2f + b.left, (b.height() - bmp.height * s) / 2f + b.top)
        shader.setLocalMatrix(m)
        p.shader = shader
        p.color = Color.WHITE
        r.set(b)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        p.color = Color.argb(110, 0, 0, 0)
        c.drawRoundRect(r, radius, radius, p)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(cf: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
