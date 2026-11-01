package app.flit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors

/**
 * Lazy thumbnail and icon loading. A small bounded LruCache, two low-priority worker threads,
 * and tag-based cancellation so recycled views never show a stale image.
 */
object Thumbs {

    private val cache: LruCache<String, Bitmap> = run {
        val kb = (Runtime.getRuntime().maxMemory() / 1024 / 10).toInt().coerceIn(2048, 24 * 1024)
        object : LruCache<String, Bitmap>(kb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private val durations = LruCache<String, Long>(1024)

    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "flit-thumb").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // ---- View binding ----------------------------------------------------------

    /**
     * Shows [placeholder] immediately (or the cached bitmap), then decodes in the background.
     * The view's tag is the request key: if the view was recycled meanwhile, the result is dropped.
     */
    fun load(v: ImageView, key: String, placeholder: Drawable?, padDp: Int, decode: () -> Bitmap?) {
        v.tag = key
        val hit = cache.get(key)
        if (hit != null) {
            showBitmap(v, hit)
            return
        }
        showPlaceholder(v, placeholder, padDp)
        pool.execute {
            if (v.tag != key) return@execute
            val bmp = try {
                decode()
            } catch (e: Throwable) {
                null
            }
            if (bmp != null) cache.put(key, bmp)
            FlitApp.main.post {
                if (v.tag == key && bmp != null) showBitmap(v, bmp)
            }
        }
    }

    /** Shows a static icon and cancels any pending load for this view. */
    fun icon(v: ImageView, d: Drawable, padDp: Int) {
        v.tag = null
        showPlaceholder(v, d, padDp)
    }

    private fun showBitmap(v: ImageView, b: Bitmap) {
        v.setPadding(0, 0, 0, 0)
        v.scaleType = ImageView.ScaleType.CENTER_CROP
        v.setImageBitmap(b)
    }

    private fun showPlaceholder(v: ImageView, d: Drawable?, padDp: Int) {
        val p = Ui.dp(padDp)
        v.setPadding(p, p, p, p)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        v.setImageDrawable(d)
    }

    /** Duration in ms (0 if unknown), cached. [done] runs on the main thread. */
    fun duration(path: String, done: (Long) -> Unit) {
        val hit = durations.get(path)
        if (hit != null) {
            done(hit)
            return
        }
        pool.execute {
            val d = readDuration(path)
            durations.put(path, d)
            FlitApp.main.post { done(d) }
        }
    }

    private fun readDuration(path: String): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    // ---- Decoders (called on the worker threads) -----------------------------------

    fun image(path: String, uri: Uri?, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29 && uri != null) {
            try {
                return FlitApp.app.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } catch (e: Exception) {
            }
        }
        val b = sampleFile(path, px) ?: return null
        val ext = Types.ext(path)
        return if (ext == "jpg" || ext == "jpeg") rotated(b, path) else b
    }

    @Suppress("DEPRECATION")
    fun video(path: String, uri: Uri?, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            if (uri != null) {
                try {
                    return FlitApp.app.contentResolver.loadThumbnail(uri, Size(px, px), null)
                } catch (e: Exception) {
                }
            }
            return try {
                ThumbnailUtils.createVideoThumbnail(File(path), Size(px, px), null)
            } catch (e: Exception) {
                null
            }
        }
        return try {
            ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
        } catch (e: Exception) {
            null
        }
    }

    /** Embedded album art, or null (the caller keeps its music icon). */
    fun audio(path: String, uri: Uri?, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29 && uri != null) {
            try {
                return FlitApp.app.contentResolver.loadThumbnail(uri, Size(px, px), null)
            } catch (e: Exception) {
            }
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val bytes = r.embeddedPicture ?: return null
            sampleBytes(bytes, px)
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }

    fun appIcon(pkg: String, px: Int): Bitmap? = try {
        toBitmap(FlitApp.app.packageManager.getApplicationIcon(pkg), px)
    } catch (e: Exception) {
        null
    }

    /** The real icon of an APK file, or null if Android cannot read it. */
    fun apkIcon(path: String, px: Int): Bitmap? {
        return try {
            val pm = FlitApp.app.packageManager
            val pi = pm.getPackageArchiveInfo(path, 0) ?: return null
            val ai = pi.applicationInfo ?: return null
            ai.sourceDir = path
            ai.publicSourceDir = path
            toBitmap(ai.loadIcon(pm), px)
        } catch (e: Exception) {
            null
        }
    }

    // ---- Helpers ---------------------------------------------------------------------

    private fun toBitmap(d: Drawable, px: Int): Bitmap {
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, px, px)
        d.draw(c)
        return b
    }

    private fun sampleFile(path: String, px: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sampleSize(o.outWidth, o.outHeight, px)
        return BitmapFactory.decodeFile(path, o2)
    }

    private fun sampleBytes(data: ByteArray, px: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(data, 0, data.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = sampleSize(o.outWidth, o.outHeight, px)
        return BitmapFactory.decodeByteArray(data, 0, data.size, o2)
    }

    private fun sampleSize(w: Int, h: Int, px: Int): Int {
        var s = 1
        while (w / (s * 2) >= px && h / (s * 2) >= px) s *= 2
        return s
    }

    private fun rotated(b: Bitmap, path: String): Bitmap {
        return try {
            val o = ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
            val deg = when (o) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) b
            else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)
        } catch (e: Exception) {
            b
        }
    }
}