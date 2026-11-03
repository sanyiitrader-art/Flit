package app.flit

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper

/**
 * Wi-Fi Direct (Wi-Fi P2P) wrapper. Everything runs on the main thread.
 *
 *  - Receiver: [advertise] publishes a DNS-SD service "_flit._tcp" whose TXT record carries the
 *    profile name, device id and avatar revision.
 *  - Sender: [discover] looks for that service for a fixed window and reports the devices found.
 *    [connect] invites one Receiver with Group Owner intent 15 (Receivers use 0), so the Sender
 *    becomes the Group Owner and can serve several Receivers in one group.
 *  - Group events go to [Link]: the P2P client dials the Group Owner over TCP; the Group Owner
 *    starts its server. The group's network name and addresses are never assumed.
 *
 * Callers pair [acquire] / [release]. Permissions are checked by [Perms] beforehand; a missing
 * permission here is reported as ERR_PERMISSION instead of crashing.
 */
@SuppressLint("MissingPermission")
object P2p {

    const val ERR_UNSUPPORTED = 1
    const val ERR_PERMISSION = 2
    const val ERR_FAILED = 3

    private const val SERVICE = "_flit._tcp"
    private const val READVERTISE_MS = 75_000L

    class Found(val address: String, val id: String, val name: String, val rev: Int)

    interface Discovery {
        fun onFound(list: List<Found>)
        fun onDone(list: List<Found>)
        fun onError(code: Int)
    }

    private val h = FlitApp.main
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private var users = 0
    private var registered = false
    private var adv = false
    private var groupUp = false

    @Volatile
    var enabled = true
        private set

    private var disc: Discovery? = null
    private val found = LinkedHashMap<String, Found>()
    private val miss = HashMap<String, Int>()
    private val timers = ArrayList<Runnable>()

    private val noop = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason: Int) {}
    }

    val supported: Boolean
        get() = FlitApp.app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    // ---- Lifecycle -----------------------------------------------------------------------

    /** Returns false if Wi-Fi Direct cannot be used on this device. */
    fun acquire(): Boolean {
        if (!supported) return false
        users++
        if (users == 1) {
            mgr = FlitApp.app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            newChannel()
            val f = IntentFilter()
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    FlitApp.app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    FlitApp.app.registerReceiver(rx, f)
                }
                registered = true
            } catch (e: Exception) {
            }
        }
        return mgr != null && ch != null
    }

    fun release() {
        if (users <= 0) return
        users--
        if (users > 0) return
        stopDiscovery()
        adv = false
        clearTimers()
        if (registered) {
            try {
                FlitApp.app.unregisterReceiver(rx)
            } catch (e: Exception) {
            }
            registered = false
        }
        try {
            ch?.close()
        } catch (e: Exception) {
        }
        ch = null
        mgr = null
        groupUp = false
    }

    private fun newChannel() {
        val m = mgr ?: return
        ch = m.initialize(FlitApp.app, Looper.getMainLooper()) {
            // Channel lost (the Wi-Fi stack restarted): open a new one shortly.
            ch = null
            h.postDelayed({
                if (users > 0) {
                    newChannel()
                    if (adv) startAdvertising()
                }
            }, 1000L)
        }
    }

    private fun clearTimers() {
        for (t in timers) h.removeCallbacks(t)
        timers.clear()
    }

    private fun later(ms: Long, r: Runnable) {
        timers.add(r)
        h.postDelayed(r, ms)
    }

    // ---- Broadcasts ----------------------------------------------------------------------

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    val was = enabled
                    enabled = on
                    if (on && !was && adv) startAdvertising()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnection()
            }
        }
    }

    private fun onConnection() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.requestConnectionInfo(c) { info ->
                if (info != null && info.groupFormed) {
                    groupUp = true
                    Link.onGroup(info)
                } else if (groupUp) {
                    groupUp = false
                    Link.onGroupLost()
                    // Some devices drop their local service while in a group: publish it again.
                    if (adv) later(1500L, Runnable { if (adv) startAdvertising() })
                }
            }
        } catch (e: SecurityException) {
        }
    }

    private fun onPeers() {
        val m = mgr ?: return
        val c = ch ?: return
        if (disc == null) return
        try {
            m.requestPeers(c) { list ->
                val l = disc
                if (l != null && list != null) {
                    val addrs = HashSet<String>()
                    for (d in list.deviceList) addrs.add(d.deviceAddress)
                    var changed = false
                    val it = found.keys.iterator()
                    while (it.hasNext()) {
                        val a = it.next()
                        if (addrs.contains(a)) {
                            miss.remove(a)
                        } else {
                            // Gone from the peer list twice in a row: it stopped advertising.
                            val k = (miss[a] ?: 0) + 1
                            if (k >= 2) {
                                it.remove()
                                miss.remove(a)
                                changed = true
                            } else {
                                miss[a] = k
                            }
                        }
                    }
                    if (changed) l.onFound(found.values.toList())
                }
            }
        } catch (e: SecurityException) {
        }
    }

    // ---- Receiver: advertise ---------------------------------------------------------------

    /** Starts or stops publishing this device as a Receiver. Returns false if it could not start. */
    fun advertise(on: Boolean): Boolean {
        if (on) {
            if (adv) return true
            if (!acquire()) return false
            adv = true
            cleanStaleGroup()
            startAdvertising()
            scheduleReadvertise()
            return true
        }
        if (!adv) return true
        adv = false
        clearTimers()
        try {
            val m = mgr
            val c = ch
            if (m != null && c != null) {
                m.clearLocalServices(c, noop)
                m.stopPeerDiscovery(c, noop)
            }
        } catch (e: SecurityException) {
        }
        release()
        return true
    }

    /** The name or avatar changed: publish the new TXT record. */
    fun refreshAdvertising() {
        if (adv) startAdvertising()
    }

    private fun cleanStaleGroup() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.requestGroupInfo(c) { g ->
                if (g != null && !Link.hasPeers()) m.removeGroup(c, noop)
            }
        } catch (e: SecurityException) {
        }
    }

    private fun startAdvertising() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.clearLocalServices(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = register()
                override fun onFailure(reason: Int) = register()
            })
        } catch (e: SecurityException) {
        }
    }

    private fun register() {
        val m = mgr ?: return
        val c = ch ?: return
        if (!adv) return
        val txt = HashMap<String, String>()
        txt["v"] = Proto.VERSION.toString()
        txt["i"] = Prefs.deviceId
        txt["n"] = Prefs.name.take(24)
        txt["r"] = Prefs.avatarRev.toString()
        val info = WifiP2pDnsSdServiceInfo.newInstance("flit" + Prefs.deviceId.take(8), SERVICE, txt)
        try {
            m.addLocalService(c, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = visible()
                override fun onFailure(reason: Int) = visible()
            })
        } catch (e: SecurityException) {
        }
    }

    /** Peer discovery makes this device visible to others' service discovery. */
    private fun visible() {
        val m = mgr ?: return
        val c = ch ?: return
        if (!adv) return
        try {
            m.discoverPeers(c, noop)
        } catch (e: SecurityException) {
        }
    }

    /** Android stops P2P discovery on its own after a while: re-trigger it. */
    private fun scheduleReadvertise() {
        later(READVERTISE_MS, Runnable {
            if (adv) {
                if (!groupUp) visible()
                scheduleReadvertise()
            }
        })
    }

    // ---- Sender: discover ----------------------------------------------------------------------

    /** Looks for Receivers for [ms] milliseconds. Requires a prior [acquire]. */
    fun discover(ms: Long, l: Discovery) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            l.onError(ERR_UNSUPPORTED)
            return
        }
        stopDiscovery()
        disc = l
        found.clear()
        miss.clear()
        try {
            m.setDnsSdResponseListeners(
                c,
                WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
                WifiP2pManager.DnsSdTxtRecordListener { full, map, dev -> onTxt(full, map, dev) }
            )
            m.clearServiceRequests(c, noop)
            m.addServiceRequest(c, WifiP2pDnsSdServiceRequest.newInstance(), noop)
        } catch (e: SecurityException) {
            disc = null
            l.onError(ERR_PERMISSION)
            return
        }
        kick(3)
        later(3000L, Runnable { services() })
        later(6000L, Runnable { services() })
        later(ms, Runnable { finishDiscovery() })
    }

    private fun kick(tries: Int) {
        val m = mgr ?: return
        val c = ch ?: return
        if (disc == null) return
        try {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    later(300L, Runnable { services() })
                }

                override fun onFailure(reason: Int) {
                    when {
                        reason == WifiP2pManager.P2P_UNSUPPORTED -> fail(ERR_UNSUPPORTED)
                        tries > 1 -> later(800L, Runnable { kick(tries - 1) })
                        else -> services()
                    }
                }
            })
        } catch (e: SecurityException) {
            fail(ERR_PERMISSION)
        }
    }

    private fun services() {
        val m = mgr ?: return
        val c = ch ?: return
        if (disc == null) return
        try {
            m.discoverServices(c, noop)
        } catch (e: SecurityException) {
            fail(ERR_PERMISSION)
        }
    }

    private fun fail(code: Int) {
        val l = disc
        stopDiscovery()
        l?.onError(code)
    }

    private fun onTxt(full: String?, map: Map<String, String>?, dev: WifiP2pDevice?) {
        val l = disc ?: return
        if (map == null || dev == null || full == null || !full.contains("_flit")) return
        val id = map["i"] ?: return
        if (id == Prefs.deviceId) return
        val name = map["n"]?.takeIf { it.isNotBlank() } ?: dev.deviceName ?: "?"
        found[dev.deviceAddress] = Found(dev.deviceAddress, id, name, map["r"]?.toIntOrNull() ?: 0)
        miss.remove(dev.deviceAddress)
        l.onFound(found.values.toList())
    }

    private fun finishDiscovery() {
        val l = disc ?: return
        val list = found.values.toList()
        stopDiscovery()
        l.onDone(list)
    }

    /** Stops searching (the list found so far is discarded). Safe to call at any time. */
    fun stopDiscovery() {
        if (disc == null && timers.isEmpty()) return
        disc = null
        if (!adv) clearTimers()
        val m = mgr
        val c = ch
        if (m != null && c != null) {
            try {
                m.stopPeerDiscovery(c, noop)
                m.clearServiceRequests(c, noop)
            } catch (e: SecurityException) {
            }
        }
    }

    // ---- Sender: connect / leave ----------------------------------------------------------------

    private fun config(addr: String): WifiP2pConfig {
        var c: WifiP2pConfig? = null
        if (Build.VERSION.SDK_INT >= 29 && Tuning.band5) {
            try {
                c = WifiP2pConfig.Builder()
                    .setDeviceAddress(MacAddress.fromString(addr))
                    .setGroupOwnerBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                    .build()
            } catch (e: Exception) {
                c = null
            }
        }
        if (c == null) {
            c = WifiP2pConfig()
            c.deviceAddress = addr
            c.wps.setup = WpsInfo.PBC
        }
        c.groupOwnerIntent = 15 // the Sender should be the Group Owner (can serve several Receivers)
        return c
    }

    /**
     * Invites the device [address]. [done] reports whether the invitation was started (not whether
     * the Receiver accepted: that arrives as a group event, then a TCP connection). BUSY and ERROR
     * are retried.
     */
    fun connect(address: String, tries: Int = 6, done: (Boolean) -> Unit) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            done(false)
            return
        }
        try {
            m.connect(c, config(address), object : WifiP2pManager.ActionListener {
                override fun onSuccess() = done(true)
                override fun onFailure(reason: Int) {
                    if (reason != WifiP2pManager.P2P_UNSUPPORTED && tries > 1) {
                        later(1500L, Runnable { connect(address, tries - 1, done) })
                    } else {
                        done(false)
                    }
                }
            })
        } catch (e: SecurityException) {
            done(false)
        }
    }

    /** Cancels a pending invitation and dissolves the group. */
    fun leave() {
        val m = mgr
        val c = ch
        if (m != null && c != null) {
            try {
                m.cancelConnect(c, noop)
                m.removeGroup(c, noop)
            } catch (e: SecurityException) {
            }
        }
        if (groupUp) {
            groupUp = false
            Link.onGroupLost()
        }
    }
}