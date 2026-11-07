package app.flit

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority

/**
 * Version-aware permission and system-state logic.
 *
 *  - Storage: API 28-29 legacy runtime permissions; API 30+ all-files access (a Settings screen).
 *  - Wi-Fi Direct: API 28-32 ACCESS_FINE_LOCATION (+ Location services ON); API 33+ NEARBY_WIFI_DEVICES.
 *  - Notifications: runtime permission on API 33+ only.
 *
 * [gate] runs before every discovery start. While it runs, [busy] is true and the Activity greys
 * out and disables the whole screen (the system prompts are separate windows, so they stay usable).
 * Every prompt that is still needed is shown once, in order. A prompt the user declines does NOT
 * stop the others from appearing; when nothing is left to ask, the gate finishes: [ready] runs only
 * if everything is on, and [busy] turns false either way so the screen is usable again.
 */
object Perms {

    const val REQ_STORAGE = 71
    const val REQ_WIFI = 72
    const val REQ_NOTIF = 73
    const val REQ_LOC = 74

    private const val MODE_NONE = 0
    private const val MODE_STARTUP = 1
    private const val MODE_GATE = 2

    private const val W_NONE = 0
    private const val W_LOCSVC = 1
    private const val W_WIFI = 2
    private const val W_STORAGE = 3

    private var mode = MODE_NONE
    private var step = 0
    private var waiting = W_NONE
    private var gateReady: (() -> Unit)? = null

    /** True while the gate is running (the Activity shades and disables the screen). */
    @Volatile
    var busy = false
        private set

    /** Set by the Activity: called on the main thread whenever [busy] changes. */
    @Volatile
    var onBusy: ((Boolean) -> Unit)? = null

    // Native Location popup bookkeeping.
    private var locBusy = false
    private var locBusyAt = 0L
    private var locWait = false
    private var locTrust = false // the user accepted the popup; Android may not report it yet
    private var locSkipped = false // the user declined the Location prompt in this gate run
    private var wifiSkipped = false // the user declined the Wi-Fi prompt in this gate run

    private fun setBusy(on: Boolean) {
        if (busy == on) return
        busy = on
        onBusy?.invoke(on)
    }

    // ---- State queries -------------------------------------------------------------

    private fun granted(p: String): Boolean =
        FlitApp.app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
            granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** The runtime permission Wi-Fi Direct needs on this Android version. */
    fun wifiPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasWifiPerm(): Boolean = granted(wifiPerm())

    fun canDiscover(): Boolean = hasWifiPerm()

    /** Location services must be on for Wi-Fi Direct discovery on Android 9-12L. */
    fun needsLocationServices(): Boolean = Build.VERSION.SDK_INT <= 32

    fun locationOn(): Boolean {
        val lm = FlitApp.app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isLocationEnabled
    }

    fun wifiOn(): Boolean {
        val wm = FlitApp.app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return wm.isWifiEnabled
    }

    fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun locationOk(): Boolean = locationOn() || locTrust

    // ---- Standalone actions -----------------------------------------------------------

    fun requestStorage(a: Activity) {
        if (hasStorage()) return
        if (Build.VERSION.SDK_INT >= 30) {
            askAllFiles(a, null)
        } else {
            a.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    fun openAppSettings(a: Activity) {
        launch(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
    }

    // ---- Startup flow --------------------------------------------------------------------

    fun startup(a: Activity) {
        if (mode != MODE_NONE) return
        resetFlags()
        mode = MODE_STARTUP
        step = 0
        advance(a)
    }

    private fun once(k: String): Boolean {
        if (Prefs.flag("asked_$k")) return false
        Prefs.setFlag("asked_$k", true)
        return true
    }

    private fun advance(a: Activity) {
        while (mode == MODE_STARTUP) {
            when (step++) {
                0 -> if (!hasStorage() && once("storage")) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        askAllFiles(a) { advance(a) }
                    } else {
                        a.requestPermissions(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ),
                            REQ_STORAGE
                        )
                    }
                    return
                }
                1 -> if (needsLocationServices() && !locationOk() && once("locsvc")) {
                    askLocation(a)
                    return
                }
                2 -> if (!hasWifiPerm() && once("wifi")) {
                    a.requestPermissions(arrayOf(wifiPerm()), REQ_WIFI)
                    return
                }
                3 -> if (Build.VERSION.SDK_INT >= 33 && !hasNotif() && once("notif")) {
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                    return
                }
                else -> mode = MODE_NONE
            }
        }
    }

    // ---- Discovery gate ------------------------------------------------------------------

    /**
     * Runs [ready] once Wi-Fi Direct can be used: permission granted, Location services on
     * (Android 9-12L) and Wi-Fi enabled. Only missing items are requested, each through Android's
     * own UI. Every call starts from a clean state.
     */
    fun gate(a: Activity, ready: () -> Unit) {
        resetFlags()
        mode = MODE_GATE
        gateReady = ready
        waiting = W_NONE
        setBusy(true)
        gateStep(a)
    }

    /** Abandons a running gate (back press while the screen is shaded). */
    fun abort() {
        if (mode == MODE_GATE) cancel()
    }

    private fun gateStep(a: Activity) {
        if (mode != MODE_GATE) return
        if (!hasWifiPerm()) {
            a.requestPermissions(arrayOf(wifiPerm()), REQ_WIFI)
            return
        }
        if (needsLocationServices() && !locationOk() && !locSkipped) {
            askLocation(a)
            return
        }
        if (!wifiOn() && !wifiSkipped) {
            waiting = W_WIFI
            // Android 11+: the Wi-Fi-only panel (just the Wi-Fi switch). Android 10 has only the
            // combined internet panel; Android 9 opens the Wi-Fi settings page.
            val i = when {
                Build.VERSION.SDK_INT >= 30 -> Intent(Settings.Panel.ACTION_WIFI)
                Build.VERSION.SDK_INT == 29 -> Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                else -> Intent(Settings.ACTION_WIFI_SETTINGS)
            }
            if (!launch(a, i)) {
                wifiSkipped = true
                gateStep(a)
            }
            return
        }
        // Nothing left to ask. Continue only if everything is really on.
        val allOn = (!needsLocationServices() || locationOk()) && wifiOn()
        val r = gateReady
        cancel()
        if (allOn) r?.invoke()
    }

    private fun cancel() {
        mode = MODE_NONE
        gateReady = null
        waiting = W_NONE
        resetFlags()
        setBusy(false)
    }

    private fun resetFlags() {
        locBusy = false
        locWait = false
        locTrust = false
        locSkipped = false
        wifiSkipped = false
    }

    // ---- Location services ---------------------------------------------------------------

    private fun askLocation(a: Activity) {
        val now = SystemClock.elapsedRealtime()
        if (locBusy && now - locBusyAt < 15_000L) return
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(a) != ConnectionResult.SUCCESS) {
            locationFallback(a)
            return
        }
        locBusy = true
        locBusyAt = now
        try {
            val req = LocationSettingsRequest.Builder()
                .addLocationRequest(
                    LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 10_000L).build()
                )
                .setAlwaysShow(true)
                .build()
            LocationServices.getSettingsClient(a).checkLocationSettings(req).addOnCompleteListener { t ->
                if (a.isFinishing || a.isDestroyed) {
                    locBusy = false
                    cancel()
                    return@addOnCompleteListener
                }
                if (t.isSuccessful) {
                    locBusy = false
                    locTrust = true
                    locationDone(a, true)
                    return@addOnCompleteListener
                }
                val e = t.exception
                if (e is ResolvableApiException) {
                    try {
                        locWait = true
                        e.startResolutionForResult(a, REQ_LOC)
                    } catch (x: Exception) {
                        locWait = false
                        locBusy = false
                        locationFallback(a)
                    }
                } else {
                    locBusy = false
                    locationFallback(a)
                }
            }
        } catch (e: Exception) {
            locBusy = false
            locationFallback(a)
        }
    }

    /** Flit's own LOCATION SERVICES dialog, used only when the native popup is unavailable. */
    private fun locationFallback(a: Activity) {
        Dialogs.location(a, {
            waiting = W_LOCSVC
            launch(a, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }, {
            when (mode) {
                MODE_STARTUP -> advance(a)
                MODE_GATE -> {
                    locSkipped = true
                    gateStep(a)
                }
                else -> cancel()
            }
        })
    }

    private fun locationDone(a: Activity, ok: Boolean) {
        when (mode) {
            MODE_STARTUP -> advance(a)
            MODE_GATE -> {
                if (!ok) locSkipped = true // declined: still show the remaining prompts
                gateStep(a)
            }
        }
    }

    private fun acceptedLocation(a: Activity, tries: Int) {
        if (mode == MODE_NONE) return
        if (a.isFinishing || a.isDestroyed) {
            cancel()
            return
        }
        if (locationOn() || tries >= 10) {
            locTrust = true
            locationDone(a, true)
        } else {
            FlitApp.main.postDelayed({ acceptedLocation(a, tries + 1) }, 200L)
        }
    }

    // ---- Activity callbacks --------------------------------------------------------------

    fun onActivityResult(a: Activity, req: Int, result: Int): Boolean {
        if (req != REQ_LOC) return false
        locBusy = false
        if (!locWait) return true
        locWait = false
        if (result == Activity.RESULT_OK) {
            acceptedLocation(a, 0)
        } else {
            locationDone(a, locationOn())
        }
        return true
    }

    fun onResult(a: Activity, req: Int, results: IntArray) {
        val ok = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (req) {
            REQ_WIFI -> {
                if (mode == MODE_GATE) {
                    if (ok) {
                        gateStep(a)
                    } else {
                        cancel()
                        if (!a.shouldShowRequestPermissionRationale(wifiPerm())) deniedForever(a)
                    }
                } else if (mode == MODE_STARTUP) {
                    advance(a)
                }
            }
            REQ_STORAGE -> {
                if (mode == MODE_STARTUP) {
                    advance(a)
                } else if (!ok &&
                    !a.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                ) {
                    deniedForever(a)
                }
            }
            REQ_NOTIF -> if (mode == MODE_STARTUP) advance(a)
        }
    }

    /** Forward from Activity.onResume: continues a flow after the user came back from Settings. */
    fun onResume(a: Activity) {
        val w = waiting
        waiting = W_NONE
        if (w == W_NONE) return
        when (mode) {
            MODE_STARTUP -> advance(a)
            MODE_GATE -> when (w) {
                W_LOCSVC -> waitFor(a, { locationOn() }, 0, 8) {
                    locSkipped = true
                    gateStep(a)
                }
                W_WIFI -> waitFor(a, { wifiOn() }, 0, 12) {
                    wifiSkipped = true
                    gateStep(a)
                }
                else -> gateStep(a)
            }
        }
    }

    /**
     * Android needs a moment to switch Wi-Fi or Location on after the user did it. Checks at once,
     * then every 250 ms up to [max] times; [onFail] runs if it never turned on (the user declined).
     */
    private fun waitFor(a: Activity, check: () -> Boolean, tries: Int, max: Int, onFail: () -> Unit) {
        if (mode != MODE_GATE) return
        if (check()) {
            gateStep(a)
            return
        }
        if (a.isFinishing || a.isDestroyed) {
            cancel()
            return
        }
        if (tries >= max) {
            onFail()
            return
        }
        FlitApp.main.postDelayed({ waitFor(a, check, tries + 1, max, onFail) }, 250L)
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun askAllFiles(a: Activity, onNotNow: (() -> Unit)?) {
        Dialogs.show(
            a, a.getString(R.string.perm_storage_title), a.getString(R.string.perm_storage_msg_all),
            a.getString(R.string.btn_grant), {
                if (mode == MODE_STARTUP) waiting = W_STORAGE
                var i = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.packageName)
                )
                if (i.resolveActivity(a.packageManager) == null) {
                    i = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
                launch(a, i)
            },
            a.getString(R.string.btn_not_now), { onNotNow?.invoke() }
        )
    }

    private fun deniedForever(a: Activity) {
        Dialogs.show(
            a, a.getString(R.string.perm_required_title), a.getString(R.string.perm_required_msg),
            a.getString(R.string.btn_open_settings), { openAppSettings(a) },
            a.getString(R.string.btn_not_now), null
        )
    }

    private fun launch(a: Activity, i: Intent): Boolean {
        return try {
            a.startActivity(i)
            true
        } catch (e: ActivityNotFoundException) {
            waiting = W_NONE
            false
        }
    }
}
