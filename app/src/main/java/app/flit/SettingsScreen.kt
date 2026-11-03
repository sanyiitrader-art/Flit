package app.flit

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** Profile picture storage: decode a picked image to a 256 px square, save as JPEG, cached load. */
object Avatar {

    private const val SIZE = 256
    private var cached: Bitmap? = null
    private var cachedRev = -1

    /** The saved avatar, or null for the default avatar. */
    fun load(): Bitmap? {
        if (!Prefs.hasAvatar()) {
            cached = null
            cachedRev = -1
            return null
        }
        val rev = Prefs.avatarRev
        if (cached == null || cachedRev != rev) {
            cached = BitmapFactory.decodeFile(Prefs.avatarFile().path)
            cachedRev = rev
        }
        return cached
    }

    fun save(b: Bitmap) {
        try {
            FileOutputStream(Prefs.avatarFile()).use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            Prefs.avatarChanged()
        } catch (e: Exception) {
        }
    }

    /** Background-thread decode: sampled, EXIF-rotated, centre-cropped to a square. */
    fun decode(uri: Uri): Bitmap? {
        val cr = FlitApp.app.contentResolver
        return try {
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= SIZE * 2 && o.outHeight / (s * 2) >= SIZE * 2) s *= 2
            val o2 = BitmapFactory.Options()
            o2.inSampleSize = s
            val src = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            val deg = cr.openInputStream(uri)?.use { degrees(it) } ?: 0f
            val side = minOf(src.width, src.height)
            val m = Matrix()
            m.postRotate(deg)
            m.postScale(SIZE.toFloat() / side, SIZE.toFloat() / side)
            Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side, m, true)
        } catch (e: Throwable) {
            null
        }
    }

    private fun degrees(s: InputStream): Float {
        return try {
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
    }
}

/**
 * Settings: four cards (Custom profile, Language, Theme, Rate).
 * Hidden: a long-press on the "Settings" title opens the developer panel (transfer tuning and the
 * connection log copy buttons). Removable before the final release.
 */
class SettingsScreen(act: MainActivity) : Screen(act) {

    private val profileSub = Ui.label(act, "", 13f, Ui.cText2)
    private val langSub = Ui.label(act, "", 13f, Ui.cText2)
    private val themeSub = Ui.label(act, "", 13f, Ui.cText2)

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        val bar = topBar(act, act.getString(R.string.settings_title)) { act.pop() }
        bar.getChildAt(1)?.setOnLongClickListener {
            tuning()
            true
        }
        col.addView(bar, Ui.lin(Ui.MATCH, Ui.WRAP))

        val list = LinearLayout(act)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8))

        list.addView(card(act.getString(R.string.settings_profile), profileSub) { act.push(ProfileScreen(act)) })
        list.addView(card(act.getString(R.string.settings_language), langSub) { pickLanguage() })
        list.addView(card(act.getString(R.string.settings_theme), themeSub) { pickTheme() })
        list.addView(card(act.getString(R.string.settings_rate), null) { /* Rate does nothing for now. */ })

        col.addView(list, Ui.lin(Ui.MATCH, Ui.WRAP))
        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    private fun card(title: String, sub: TextView?, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.card(18)
        row.minimumHeight = Ui.dp(68)
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(14), Ui.dp(14))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick() }

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.label(act, title, 16f, Ui.cText, true), Ui.lin(Ui.MATCH, Ui.WRAP))
        if (sub != null) col.addView(sub, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(3) })
        row.addView(col, Ui.lin(0, Ui.WRAP, 1f))
        row.addView(Icons.view(act, Icons.CHEVRON, 20, Ui.cText2))

        row.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).also {
            it.setMargins(0, Ui.dp(5), 0, Ui.dp(5))
        }
        return row
    }

    private fun pickLanguage() {
        val opts = listOf(act.getString(R.string.lang_english), act.getString(R.string.lang_spanish))
        Dialogs.choose(act, act.getString(R.string.settings_language), opts, if (Prefs.lang == "es") 1 else 0) { i ->
            val n = if (i == 1) "es" else "en"
            if (n != Prefs.lang) {
                Prefs.lang = n
                Prefs.setFlag("reopen_settings", true)
                act.recreate()
            }
        }
    }

    private fun pickTheme() {
        val opts = listOf(
            act.getString(R.string.theme_system),
            act.getString(R.string.theme_light),
            act.getString(R.string.theme_dark)
        )
        Dialogs.choose(act, act.getString(R.string.settings_theme), opts, Prefs.theme) { i ->
            if (i != Prefs.theme) {
                Prefs.theme = i
                Prefs.setFlag("reopen_settings", true)
                act.recreate()
            }
        }
    }

    // ---- Hidden developer panel ------------------------------------------------------------

    private fun tuningRow(title: String, value: () -> String, next: () -> Unit): LinearLayout {
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.ripple(null, 12)
        row.setPadding(Ui.dp(4), 0, Ui.dp(4), 0)
        val v = Ui.label(act, value(), 14f, Ui.cText, true)
        row.addView(Ui.label(act, title, 14f, Ui.cText), Ui.lin(0, Ui.WRAP, 1f))
        row.addView(v, Ui.lin(Ui.WRAP, Ui.WRAP))
        row.setOnClickListener {
            next()
            v.text = value()
        }
        return row
    }

    private fun intRow(title: String, key: String, def: Int, values: IntArray, fmt: (Int) -> String): LinearLayout =
        tuningRow(title, { fmt(Prefs.int(key, def)) }) {
            val i = values.indexOf(Prefs.int(key, def))
            Prefs.putInt(key, values[(i + 1) % values.size])
        }

    private fun flagRow(title: String, key: String, def: Boolean): LinearLayout =
        tuningRow(title, { if (Prefs.flag(key, def)) "on" else "off" }) {
            Prefs.setFlag(key, !Prefs.flag(key, def))
        }

    /**
     * Copies Flit's own connection log, the last crash (if the app crashed), and the last transfer
     * speed results to the clipboard. Android lets an app read its own log lines without any
     * permission. Nothing is sent anywhere.
     */
    private fun copyLog() {
        Toast.makeText(act, "Collecting log...", Toast.LENGTH_SHORT).show()
        FlitApp.io.execute {
            val body = try {
                val pb = ProcessBuilder(
                    "logcat", "-d", "-v", "time", "-s",
                    "FlitP2p:D", "FlitLink:D", "FlitSend:D", "FlitCrash:D", "AndroidRuntime:E"
                )
                pb.redirectErrorStream(true)
                val p = pb.start()
                val s = p.inputStream.bufferedReader().use { it.readText() }
                p.destroy()
                s
            } catch (e: Exception) {
                "could not read the log: " + e.message
            }
            val lines = body.lines().filter { it.isNotBlank() }.takeLast(220)
            val crash = try {
                val f = File(act.filesDir, FlitApp.CRASH_FILE)
                if (f.isFile) f.readText() else "(none recorded)"
            } catch (e: Exception) {
                "(could not read)"
            }
            val header = "Flit log | " + Build.MANUFACTURER + " " + Build.MODEL +
                " | Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
            val logText = if (lines.isEmpty()) "(no connection log yet: try a send first)" else lines.joinToString("\n")
            val results = Tuning.lastResults().ifBlank { "-" }
            val text = header + "\n" + logText +
                "\n\nLast crash:\n" + crash +
                "\n\nLast receives:\n" + results
            FlitApp.main.post {
                val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Flit log", text))
                Toast.makeText(act, "Log copied (" + lines.size + " lines). Paste it into a message.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tuning() {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        fun add(v: LinearLayout) = col.addView(v, Ui.lin(Ui.MATCH, Ui.dp(48)))

        add(tuningRow("Connection log", { "tap to copy" }) { copyLog() })
        add(intRow("Data streams", "t_streams", 0, intArrayOf(0, 1, 2, 3, 4)) { if (it == 0) "auto" else "$it" })
        add(intRow("Chunk size", "t_chunk", 2048, intArrayOf(1024, 2048, 4096, 8192)) { "$it KiB" })
        add(intRow("I/O buffer", "t_buf", if (Tuning.lowRam) 64 else 128, intArrayOf(64, 128, 256, 512)) { "$it KiB" })
        add(intRow("Requests per stream", "t_out", 2, intArrayOf(1, 2, 3, 4)) { "$it" })
        add(intRow("Socket buffer", "t_sockbuf", 0, intArrayOf(0, 256, 512, 1024)) { if (it == 0) "OS default" else "$it KiB" })
        add(flagRow("Preallocate (fallocate)", "t_falloc", true))
        add(flagRow("Request 5 GHz group (no effect now)", "t_band5", false))

        val note = Ui.label(act, "Applies to the next transfer. Last receives (newest first):", 12f, Ui.cText2)
        col.addView(note, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(14) })
        val last = Tuning.lastResults().ifBlank { "-" }
        val res = Ui.label(act, last, 11.5f, Ui.cText)
        res.setLineSpacing(0f, 1.1f)
        col.addView(res, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(6) })

        val sv = ScrollView(act)
        sv.addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        val h = (act.resources.displayMetrics.heightPixels * 0.5f).toInt()
        val wrap = FrameLayout(act)
        wrap.addView(sv, FrameLayout.LayoutParams(Ui.MATCH, h))

        Dialogs.custom(
            act, act.getString(R.string.dev_title), wrap,
            act.getString(R.string.dev_copy), { copyLog() },
            act.getString(R.string.dialog_ok), null
        )
    }

    override fun onShow() {
        profileSub.text = Prefs.name
        langSub.text = act.getString(if (Prefs.lang == "es") R.string.lang_spanish else R.string.lang_english)
        themeSub.text = act.getString(
            when (Prefs.theme) {
                Prefs.THEME_LIGHT -> R.string.theme_light
                Prefs.THEME_DARK -> R.string.theme_dark
                else -> R.string.theme_system
            }
        )
    }
}

/**
 * Custom profile: circular avatar (tap to pick an image), editable device name, and floating
 * Cancel (left) and Save (right). Nothing is persisted until Save; Cancel discards.
 */
class ProfileScreen(act: MainActivity) : Screen(act) {

    private val avatar = AvatarView(act)
    private val edit = EditText(act)
    private var pending: Bitmap? = null

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(topBar(act, act.getString(R.string.profile_title)) { act.pop() }, Ui.lin(Ui.MATCH, Ui.WRAP))

        val content = LinearLayout(act)
        content.orientation = LinearLayout.VERTICAL
        content.gravity = Gravity.CENTER_HORIZONTAL
        content.setPadding(Ui.dp(24), Ui.dp(24), Ui.dp(24), Ui.dp(96))
        content.isFocusable = true
        content.isFocusableInTouchMode = true
        content.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

        avatar.bitmap = Avatar.load()
        avatar.contentDescription = act.getString(R.string.cd_avatar)
        avatar.setOnClickListener { pick() }
        content.addView(avatar, Ui.lin(Ui.dp(128), Ui.dp(128)))
        content.addView(
            Ui.label(act, act.getString(R.string.profile_pick_picture), 13f, Ui.cText2),
            Ui.lin(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(10) }
        )

        content.addView(
            Ui.label(act, act.getString(R.string.profile_name_label), 13f, Ui.cText2, true),
            Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(32) }
        )
        edit.setText(Prefs.name)
        edit.hint = act.getString(R.string.profile_name_hint)
        edit.setTextColor(Ui.cText)
        edit.setHintTextColor(Ui.cText2)
        edit.textSize = 16f
        edit.setSingleLine()
        edit.inputType = InputType.TYPE_CLASS_TEXT
        edit.filters = arrayOf(InputFilter.LengthFilter(30))
        edit.background = Ui.shape(Ui.cSurface, 14, Ui.cBorder, 1)
        edit.setPadding(Ui.dp(16), Ui.dp(14), Ui.dp(16), Ui.dp(14))
        content.addView(edit, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(8) })

        val scroll = ScrollView(act)
        scroll.isFillViewport = true
        scroll.addView(content, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP))
        col.addView(scroll, Ui.lin(Ui.MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val cancel = Pill(act, act.getString(R.string.btn_cancel), false)
        cancel.setOnClickListener {
            hideKeyboard()
            act.pop()
        }
        val save = Pill(act, act.getString(R.string.btn_save), true)
        save.setOnClickListener { save() }

        val bottom = LinearLayout(act)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.addView(cancel, Ui.lin(Ui.WRAP, Ui.WRAP))
        bottom.addView(android.view.View(act), Ui.lin(0, 1, 1f))
        bottom.addView(save, Ui.lin(Ui.WRAP, Ui.WRAP))
        addView(
            bottom,
            FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.BOTTOM).also {
                it.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(16))
            }
        )
    }

    private fun pick() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "image/*"
        try {
            act.startActivityForResult(i, REQ_PICK)
        } catch (e: ActivityNotFoundException) {
            Ui.toast(act, R.string.profile_pick_failed)
        }
    }

    override fun onResult(req: Int, ok: Boolean, data: Intent?) {
        if (req != REQ_PICK || !ok) return
        val uri = data?.data ?: return
        FlitApp.io.execute {
            val b = Avatar.decode(uri)
            FlitApp.main.post {
                if (b != null) {
                    pending = b
                    avatar.bitmap = b
                } else {
                    Ui.toast(act, R.string.profile_pick_failed)
                }
            }
        }
    }

    private fun save() {
        Prefs.name = edit.text.toString()
        pending?.let { Avatar.save(it) }
        Bubbles.avatarChanged()
        hideKeyboard()
        Ui.toast(act, R.string.profile_saved)
        act.pop()
    }

    private fun hideKeyboard() {
        val m = act.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        m?.hideSoftInputFromWindow(windowToken, 0)
    }

    companion object {
        const val REQ_PICK = 91
    }
}