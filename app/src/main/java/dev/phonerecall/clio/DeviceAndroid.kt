package dev.phonerecall.clio

import android.Manifest
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import dev.phonerecall.brief.BriefCore
import dev.phonerecall.brief.ScreenTime
import dev.phonerecall.brief.UsageEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The device area's Android side. Runtime receivers and the default-network callback while the process lives
 * (started once from ZeusApp; no wakeups of their own), [tick] on the mind's 30-minute sync for the battery
 * reading and yesterday's screen time, and [CarLinkReceiver] from the manifest. Values go to [DeviceLog].
 */
object DeviceWatch {
    @Volatile private var started = false

    private fun prefs(c: Context) = c.getSharedPreferences("clio-device", Context.MODE_PRIVATE)
    private fun zone() = ZoneId.systemDefault()
    private fun info(event: String, detail: String = "") = Clio.info(DeviceLog.AREA, event, detail)

    fun start(c: Context) {
        if (started) return
        started = true
        val app = c.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching { on(app, intent, isInitialStickyBroadcast) }.onFailure { Clio.error(DeviceLog.AREA, it) }
            }
        }
        val f = IntentFilter().apply {
            listOf(
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT, Intent.ACTION_BATTERY_CHANGED,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, Intent.ACTION_AIRPLANE_MODE_CHANGED, AudioManager.RINGER_MODE_CHANGED_ACTION,
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
            ).forEach(::addAction)
        }
        runCatching { app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) }.onFailure { Clio.error(DeviceLog.AREA, it) }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                    net(app, DeviceLog.network(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)))
                override fun onLost(network: Network) = net(app, DeviceLog.network(wifi = false, cellular = false))
            })
        }.onFailure { Clio.error(DeviceLog.AREA, it) }
    }

    private fun net(c: Context, name: String) {
        val p = prefs(c)
        if (p.getString("net", null) == name) return
        p.edit().putString("net", name).apply()
        info(name)
    }

    /** Logged only when the value differs from the last one seen (the system repeats these). */
    private fun changed(c: Context, key: String, value: String): Boolean {
        val p = prefs(c)
        if (p.getString(key, null) == value) return false
        p.edit().putString(key, value).apply()
        return true
    }

    private fun on(c: Context, i: Intent, sticky: Boolean) {
        when (i.action) {
            Intent.ACTION_SCREEN_ON -> info(DeviceLog.SCREEN_ON)
            Intent.ACTION_SCREEN_OFF -> info(DeviceLog.SCREEN_OFF)
            Intent.ACTION_USER_PRESENT -> info(DeviceLog.UNLOCKED)
            Intent.ACTION_BATTERY_CHANGED -> battery(c, i, sticky)
            PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                val on = c.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                if (changed(c, "saver", "$on")) info(if (on) DeviceLog.SAVER_ON else DeviceLog.SAVER_OFF, pct(c)?.let { "$it%" }.orEmpty())
            }
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> DeviceLog.airplane(i.getBooleanExtra("state", false)).let { if (changed(c, "air", it)) info(it) }
            AudioManager.RINGER_MODE_CHANGED_ACTION -> DeviceLog.ringer(i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, 2)).let { if (changed(c, "ringer", it)) info(it) }
            NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED ->
                DeviceLog.dnd(c.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ?: 0).let { if (changed(c, "dnd", it)) info(it) }
        }
    }

    private fun pct(c: Context): Int? = c.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    private fun level(i: Intent): Int? {
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (l < 0 || s <= 0) null else l * 100 / s
    }

    /** Plug and unplug, full charge, overnight sessions and heat, from BATTERY_CHANGED (it repeats; only changes count). */
    private fun battery(c: Context, i: Intent, sticky: Boolean) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        val pct = level(i) ?: return
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val was = p.getInt("plugged", -1)
        val e = p.edit()
        if (plugged != 0 && was <= 0) {
            info(DeviceLog.PLUGGED, DeviceLog.plugged(pct, DeviceLog.plug(plugged), late = sticky && was == 0))
            e.putLong("cstart", now).putInt("cpct", pct).putBoolean("full", false)
        } else if (plugged == 0 && was > 0) {
            val start = p.getLong("cstart", 0L).takeIf { it > 0 }
            val startPct = p.getInt("cpct", -1).takeIf { it >= 0 }
            info(DeviceLog.UNPLUGGED, DeviceLog.unplugged(start, startPct, now, pct))
            if (start != null && DeviceLog.overnight(start, now, zone())) info(DeviceLog.OVERNIGHT, DeviceLog.overnightDetail(start, now, startPct, pct, zone()))
            e.remove("cstart").remove("cpct")
        }
        if (plugged != 0 && pct >= 100 && !p.getBoolean("full", false)) {
            p.getLong("cstart", 0L).takeIf { it > 0 }.let { s -> info(DeviceLog.FULL, s?.let { "after ${DeviceLog.duration(now - it)}" }.orEmpty()) }
            e.putBoolean("full", true)
        }
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val (hot, spike) = DeviceLog.heat(temp, p.getBoolean("hot", false))
        if (spike) Clio.warn(DeviceLog.AREA, DeviceLog.HOT, "${temp / 10}°C at $pct%")
        e.putInt("plugged", plugged).putBoolean("hot", hot).apply()
    }

    /**
     * From the mind's 30-minute sync worker (no wakeup of its own): the battery reading when the last one is 25+
     * minutes old, and once a day after 4 AM yesterday's screen lines (with Usage access).
     */
    fun tick(c: Context) {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (now - p.getLong("snap", 0L) >= DeviceLog.SNAPSHOT_MS) {
            c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
                val pct = level(i) ?: return@let
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                val saver = c.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                info(DeviceLog.BATTERY, DeviceLog.battery(pct, plugged.takeIf { it != 0 }?.let(DeviceLog::plug), i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0), saver))
                p.edit().putLong("snap", now).apply()
            }
        }
        val zone = zone()
        val today = LocalDate.now(zone)
        if (p.getString("screen", null) != today.toString() && LocalTime.now(zone) >= LocalTime.of(4, 0) && dev.phonerecall.odyssey.Odyssey.canUsage(c)) {
            val events = usage(c, today, zone) ?: return
            for ((event, detail) in DeviceLog.screenLines(events, today, zone, c.packageName) { BriefCore.prettyLabel(c, it) }) Clio.log(DeviceLog.AREA, Level.INFO, event, detail, max = 800)
            p.edit().putString("screen", today.toString()).apply()
        }
    }

    private fun usage(c: Context, today: LocalDate, zone: ZoneId): List<UsageEvent>? = runCatching {
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return null
        val (from, to) = ScreenTime.window(today, zone)
        val kinds = setOf(ScreenTime.RESUMED, ScreenTime.PAUSED, ScreenTime.STOPPED, ScreenTime.KEYGUARD_HIDDEN, DeviceLog.SCREEN_INTERACTIVE, DeviceLog.SCREEN_NON_INTERACTIVE)
        val ev = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        val out = ArrayList<UsageEvent>()
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType in kinds) out += UsageEvent(e.packageName.orEmpty(), e.eventType, e.timeStamp) }
        out
    }.onFailure { Clio.error(DeviceLog.AREA, it) }.getOrNull()

    fun canCar(c: Context) = c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

/** Bluetooth links (exempt from the implicit-broadcast limits, so the manifest gets them): car audio or a hands-free kit only, no device names. */
class CarLinkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DeviceWatch.canCar(context)) return
        // Any Bluetooth audio going away: media muted at work stays muted on the speaker (qol/WorkMute).
        if (intent.action == BluetoothDevice.ACTION_ACL_DISCONNECTED) dev.phonerecall.qol.WorkMuteCore.linkGone(context.applicationContext)
        @Suppress("DEPRECATION") val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
        val cls = runCatching { d.bluetoothClass?.deviceClass }.getOrNull() ?: return
        if (!DeviceLog.isCar(cls)) return
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> { Clio.info(DeviceLog.AREA, DeviceLog.CAR_ON); dev.phonerecall.qol.QolCore.carLink(context, true); dev.phonerecall.hestia.HestiaRoutines.eventAsync(context, dev.phonerecall.hestia.Moment.CAR_ON) }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> { Clio.info(DeviceLog.AREA, DeviceLog.CAR_OFF); dev.phonerecall.qol.QolCore.carLink(context, false); dev.phonerecall.hestia.HestiaRoutines.eventAsync(context, dev.phonerecall.hestia.Moment.CAR_OFF) }
        }
    }
}
