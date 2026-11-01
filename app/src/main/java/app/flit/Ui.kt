package app.flit

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * UI toolkit: palette access, locale/theme context wrapping, drawable and view helpers.
 * No hex colour exists in this file: every colour comes from the flit_* resource tokens.
 */
object Ui {

    const val MATCH = -1
    const val WRAP = -2

    // Palette (resolved from resources for the current light/dark configuration).
    var cBg = 0
    var cSurface = 0
    var cSurface2 = 0
    var cSurface3 = 0
    var cText = 0
    var cText2 = 0
    var cBorder = 0
    var cAccent = 0
    var cOnAccent = 0

    /** Reads all tokens. Call with the Activity context after it was created. */
    fun load(c: Context) {
        cBg = c.getColor(R.color.flit_bg)
        cSurface = c.getColor(R.color.flit_surface)
        cSurface2 = c.getColor(R.color.flit_surface2)
        cSurface3 = c.getColor(R.color.flit_surface3)
        cText = c.getColor(R.color.flit_text)
        cText2 = c.getColor(R.color.flit_text2)
        cBorder = c.getColor(R.color.flit_border)
        cAccent = c.getColor(R.color.flit_accent)
        cOnAccent = c.getColor(R.color.flit_on_accent)
    }

    // ---- Units ---------------------------------------------------------------

    private val density: Float
        get() = Resources.getSystem().displayMetrics.density

    fun dp(v: Int): Int = (v * density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * density
    fun dpf(v: Int): Float = v * density

    fun alpha(color: Int, a: Int): Int = (color and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    // ---- Language and theme --------------------------------------------------

    /**
     * Applies the in-app language and theme choice to a context. Used in
     * Activity.attachBaseContext and for service/notification contexts.
     * For "System" the system night mode is left untouched.
     */
    fun wrap(base: Context): Context {
        val cfg = Configuration(base.resources.configuration)
        val loc = Locale.forLanguageTag(Prefs.lang)
        Locale.setDefault(loc)
        cfg.setLocale(loc)
        cfg.setLayoutDirection(loc)
        when (Prefs.theme) {
            Prefs.THEME_LIGHT ->
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
            Prefs.THEME_DARK ->
                cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        return base.createConfigurationContext(cfg)
    }

    // ---- Drawables -----------------------------------------------------------

    fun shape(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dpf(radiusDp)
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    /** Wraps a background in a ripple that follows the same corner radius. */
    fun ripple(content: Drawable?, radiusDp: Int): Drawable {
        val mask = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dpf(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(alpha(cText, 36)), content, mask)
    }

    /** Standard card: surface fill, hairline border, ripple. */
    fun card(radiusDp: Int = 16): Drawable =
        ripple(shape(cSurface, radiusDp, cBorder, 1), radiusDp)

    // ---- Views ---------------------------------------------------------------

    fun label(
        c: Context,
        s: CharSequence,
        sp: Float = 15f,
        color: Int = cText,
        bold: Boolean = false,
        lines: Int = 0
    ): TextView {
        val t = TextView(c)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.setTextColor(color)
        t.includeFontPadding = false
        if (bold) t.typeface = Typeface.DEFAULT_BOLD
        if (lines > 0) {
            t.maxLines = lines
            t.ellipsize = TextUtils.TruncateAt.END
        }
        return t
    }

    fun lin(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    fun toast(c: Context, resId: Int) {
        Toast.makeText(c, resId, Toast.LENGTH_SHORT).show()
    }

    /**
     * Pads [v] by the system bars, display cutout and keyboard so content never hides
     * under them (the app draws edge to edge on every supported Android version).
     */
    fun fitSystem(v: View) {
        v.setOnApplyWindowInsetsListener { view, ins ->
            val l: Int
            val t: Int
            val r: Int
            val b: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(
                    WindowInsets.Type.systemBars() or
                        WindowInsets.Type.displayCutout() or
                        WindowInsets.Type.ime()
                )
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else {
                @Suppress("DEPRECATION")
                run {
                    l = ins.systemWindowInsetLeft
                    t = ins.systemWindowInsetTop
                    r = ins.systemWindowInsetRight
                    b = ins.systemWindowInsetBottom
                }
            }
            view.setPadding(l, t, r, b)
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED
            else @Suppress("DEPRECATION") ins.consumeSystemWindowInsets()
        }
    }
}

/** Pill-shaped button. Filled = accent (primary action); otherwise neutral outline. */
class Pill(c: Context, label: CharSequence, var filled: Boolean = true) : TextView(c) {

    init {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        letterSpacing = 0.04f
        setPadding(Ui.dp(22), Ui.dp(13), Ui.dp(22), Ui.dp(13))
        isClickable = true
        isFocusable = true
        restyle()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        restyle()
    }

    fun restyle() {
        val on = isEnabled
        val fill = if (filled) (if (on) Ui.cAccent else Ui.cSurface3) else Ui.cSurface2
        val txt = if (filled) (if (on) Ui.cOnAccent else Ui.cText2) else (if (on) Ui.cText else Ui.cText2)
        setTextColor(txt)
        background = Ui.ripple(Ui.shape(fill, 100, if (filled) 0 else Ui.cBorder, if (filled) 0 else 1), 100)
    }
}

/** Round selection indicator. Checked = accent disc with a white check. */
class CheckView(c: Context, private val overlay: Boolean = false) : View(c) {

    var checked: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(Ui.dp(24), w), resolveSize(Ui.dp(24), h))
    }

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val sw = Ui.dpf(2)
        val r = s / 2f - sw / 2f - Ui.dpf(1)
        if (checked) {
            p.style = Paint.Style.FILL
            p.color = Ui.cAccent
            c.drawCircle(cx, cy, r + sw / 2f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw * 1.1f
            p.strokeCap = Paint.Cap.ROUND
            p.strokeJoin = Paint.Join.ROUND
            p.color = Ui.cOnAccent
            path.reset()
            path.moveTo(cx - r * 0.42f, cy + r * 0.02f)
            path.lineTo(cx - r * 0.10f, cy + r * 0.36f)
            path.lineTo(cx + r * 0.46f, cy - r * 0.30f)
            c.drawPath(path, p)
        } else {
            if (overlay) {
                p.style = Paint.Style.FILL
                p.color = Ui.alpha(Color.BLACK, 80)
                c.drawCircle(cx, cy, r, p)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = if (overlay) Color.WHITE else Ui.cText2
            c.drawCircle(cx, cy, r, p)
        }
    }
}

/** Horizontal progress bar: rounded track with an accent fill. */
class BarView(c: Context) : View(c) {

    /** 0..1 */
    var progress: Float = 0f
        set(v) {
            val n = v.coerceIn(0f, 1f)
            if (n != field) {
                field = n
                invalidate()
            }
        }

    /** 0 = use the default track colour. */
    var trackColor: Int = 0
        set(v) {
            field = v
            invalidate()
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val rad = h / 2f
        p.color = if (trackColor != 0) trackColor else Ui.cSurface2
        r.set(0f, 0f, w, h)
        c.drawRoundRect(r, rad, rad, p)
        if (progress > 0f) {
            p.color = Ui.cAccent
            r.set(0f, 0f, maxOf(h, w * progress), h)
            c.drawRoundRect(r, rad, rad, p)
        }
    }
}