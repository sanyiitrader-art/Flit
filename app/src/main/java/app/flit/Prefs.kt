package app.flit

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * Settings and profile storage (SharedPreferences). Reads are cached in memory by Android,
 * writes use apply(), so none of this blocks the UI.
 */
object Prefs {

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    private const val MAX_NAME = 30

    private lateinit var sp: SharedPreferences
    private lateinit var filesDir: File

    fun init(app: Application) {
        sp = app.getSharedPreferences("flit", Context.MODE_PRIVATE)
        filesDir = app.filesDir
        if (!sp.contains("id")) {
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            sp.edit().putString("id", id).apply()
        }
    }

    /** Stable random identifier of this installation (never shown to the user). */
    val deviceId: String
        get() = sp.getString("id", "") ?: ""

    val defaultName: String
        get() = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"

    /** Name shown to nearby devices. Blank resets to the device model. */
    var name: String
        get() = sp.getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName
        set(v) {
            val t = v.trim().take(MAX_NAME)
            if (t.isEmpty()) sp.edit().remove("name").apply()
            else sp.edit().putString("name", t).apply()
        }

    /** THEME_SYSTEM (default), THEME_LIGHT or THEME_DARK. */
    var theme: Int
        get() = sp.getInt("theme", THEME_SYSTEM).coerceIn(0, 2)
        set(v) {
            sp.edit().putInt("theme", v.coerceIn(0, 2)).apply()
            FlitApp.invalidateLocale()
        }

    /** "en" (default) or "es". */
    var lang: String
        get() = sp.getString("lang", "en").let { if (it == "es") "es" else "en" }
        set(v) {
            sp.edit().putString("lang", if (v == "es") "es" else "en").apply()
            FlitApp.invalidateLocale()
        }

    /** Receiver toggle: allow several files at once. */
    var simultaneous: Boolean
        get() = sp.getBoolean("simultaneous", false)
        set(v) = sp.edit().putBoolean("simultaneous", v).apply()

    // ---- Avatar -------------------------------------------------------------

    /** Location of the saved avatar (256 px JPEG). May not exist. */
    fun avatarFile(): File = File(filesDir, "avatar.jpg")

    fun hasAvatar(): Boolean = avatarFile().isFile

    /** Incremented whenever the avatar changes, so caches and peers can detect it. */
    var avatarRev: Int
        get() = sp.getInt("avatar_rev", 0)
        private set(v) = sp.edit().putInt("avatar_rev", v).apply()

    fun avatarChanged() {
        avatarRev = avatarRev + 1
    }

    fun clearAvatar() {
        avatarFile().delete()
        avatarChanged()
    }

    // ---- Generic accessors (permission bookkeeping, tuning, recovery flags) ---

    fun flag(key: String, def: Boolean = false): Boolean = sp.getBoolean(key, def)
    fun setFlag(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    fun int(key: String, def: Int = 0): Int = sp.getInt(key, def)
    fun putInt(key: String, v: Int) = sp.edit().putInt(key, v).apply()

    fun long(key: String, def: Long = 0L): Long = sp.getLong(key, def)
    fun putLong(key: String, v: Long) = sp.edit().putLong(key, v).apply()

    fun str(key: String, def: String = ""): String = sp.getString(key, def) ?: def
    fun putStr(key: String, v: String) = sp.edit().putString(key, v).apply()
}