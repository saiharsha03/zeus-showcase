package dev.phonerecall.odyssey

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.phonerecall.R

/**
 * The drive report's sampler: runs only between IN_VEHICLE enter and exit (Play services' transition is
 * one of the cases Android lets start a location service from the background). Accelerometer and
 * gyroscope at ~50 Hz, GPS once a second, screen and unlock broadcasts; all fed to [DriveAnalyzer] in
 * memory. At the end only the report is stored, sealed, on the phone. A silent, minimal notification.
 */
class DriveService : Service(), SensorEventListener {
    companion object {
        private const val START = "dev.phonerecall.ODYSSEY_DRIVE_START"
        private const val STOP = "dev.phonerecall.ODYSSEY_DRIVE_STOP"
        private const val CHANNEL = "odyssey_drive"
        private const val MAX_MS = 4 * 3_600_000L
        /** Parked this long with no exit transition: the drive is over. */
        private const val PARKED_MS = 8 * 60_000L

        /** The drive's last GPS point, so the stay that follows needs no fix of its own. */
        @Volatile var lastFix: Fix? = null

        fun start(c: Context) {
            if (!Odyssey.canFine(c)) { dev.phonerecall.clio.Clio.warn("odyssey", "drive not started", "precise location is off"); return }
            // Android can refuse a location service started from the background; say so instead of losing the drive quietly.
            runCatching { c.startForegroundService(Intent(c, DriveService::class.java).setAction(START)) }
                .onFailure { dev.phonerecall.clio.Clio.warn("odyssey", "drive not started", it.javaClass.simpleName) }
        }

        fun stop(c: Context) { runCatching { c.startService(Intent(c, DriveService::class.java).setAction(STOP)) } }
    }

    private var analyzer: DriveAnalyzer? = null
    /** The path, thinned as it comes; null when he turned Keep routes off. */
    private var track: TrackRecorder? = null
    private var started = 0L
    private var lastMoving = 0L
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler

    private val locations = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) { for (l in r.locations) onLocation(l) }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val t = System.currentTimeMillis()
            handler.post {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> analyzer?.screenOn(t)
                    Intent.ACTION_SCREEN_OFF -> analyzer?.screenOff(t)
                    Intent.ACTION_USER_PRESENT -> analyzer?.unlock(t)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("odyssey-drive").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finish(); return START_NOT_STICKY }
        startForeground(1_900, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        if (analyzer == null) begin()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun begin() {
        started = System.currentTimeMillis()
        lastMoving = started
        analyzer = DriveAnalyzer("d$started", started)
        track = if (OdysseyStore.load(this).settings.keepRoutes) TrackRecorder() else null
        dev.phonerecall.clio.Clio.info("odyssey", "drive started")
        val sm = getSystemService(SensorManager::class.java)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, 20_000, handler) }
        sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, 20_000, handler) }
        LocationServices.getFusedLocationProviderClient(this).requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).setMinUpdateIntervalMillis(1_000L).build(), locations, thread.looper,
        )
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT) })
        if (getSystemService(PowerManager::class.java).isInteractive) analyzer?.screenOn(started)
        handler.postDelayed(::watchdog, 60_000L)
    }

    private fun watchdog() {
        val now = System.currentTimeMillis()
        if (analyzer == null) return
        if (now - started > MAX_MS || now - lastMoving > PARKED_MS) { finish(); return }
        handler.postDelayed(::watchdog, 60_000L)
    }

    private fun onLocation(l: Location) {
        val a = analyzer ?: return
        val t = l.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val v = if (l.hasSpeed()) l.speed.toDouble() else 0.0
        if (v > 2.0) lastMoving = System.currentTimeMillis()
        a.gps(t, v, l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 30f)
        track?.add(t, l.latitude, l.longitude, v, if (l.hasAccuracy()) l.accuracy else Track.MAX_ACCURACY_M)
        lastFix = Fix(System.currentTimeMillis(), l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 30f)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = analyzer ?: return
        val t = System.currentTimeMillis()
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> a.accel(t, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
            Sensor.TYPE_GYROSCOPE -> a.gyro(t, e.values[0].toDouble(), e.values[1].toDouble(), e.values[2].toDouble())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun finish() {
        val a = analyzer
        val tr = track
        analyzer = null
        // Started by a geofence exit (no IN_VEHICLE from activity recognition): end the drive for the stays too.
        if (a != null) Thread { runCatching { if (OdysseyStore.load(applicationContext).driving) Odyssey.transitions(applicationContext, listOf(Act.VEHICLE_EXIT to System.currentTimeMillis())) } }.start()
        track = null
        runCatching { getSystemService(SensorManager::class.java).unregisterListener(this) }
        runCatching { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(locations) }
        runCatching { unregisterReceiver(screen) }
        if (a != null) {
            val end = System.currentTimeMillis()
            val ctx = applicationContext
            // Short hops (moving the car in the lot) are not drives.
            Thread {
                val r = a.finish(end, apps(ctx, a, started, end))
                dev.phonerecall.clio.Clio.info("odyssey", "drive ended", "score ${r.score} · ${r.minutes} min" + if (r.meters >= 500 && r.minutes >= 2) "" else " (too short, not kept)")
                if (r.meters >= 500 && r.minutes >= 2) {
                    val ends = a.ends
                    val sky = dev.phonerecall.aeolia.WeatherStore.load(ctx).takeIf { it.hasData && kotlin.math.abs(it.updatedAt - r.start) < 3 * 3_600_000L }
                    val named = r.copy(
                        events = r.events.map { e -> if (e.lat == 0.0 && e.lng == 0.0) e else e.copy(street = Odyssey.geocode(ctx, e.lat, e.lng).second) },
                        from = ends?.first?.let { Odyssey.address(ctx, it.lat, it.lng) }, to = ends?.second?.let { Odyssey.address(ctx, it.lat, it.lng) },
                        weather = sky?.let { "${dev.phonerecall.aeolia.WeatherState.f(it.tempC!!)}°F, ${dev.phonerecall.aeolia.Sky.words(it.code)}" }, weatherCode = sky?.code ?: -1,
                    )
                    OdysseyStore.update(ctx) { d -> d.copy(drives = (d.drives + named).filter { end - it.end < 90 * 86_400_000L }) }
                    tr?.finish()?.takeIf { it.size >= 2 }?.let { RouteStore.save(ctx, DriveRoute(r.id, r.end, it)) }
                }
            }.start()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Foreground minutes per app while moving, from UsageStats, only if he allowed Usage access. */
    private fun apps(c: Context, a: DriveAnalyzer, from: Long, to: Long): Map<String, Double> {
        if (!Odyssey.canUsage(c)) return emptyMap()
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val out = HashMap<String, Double>()
        val open = HashMap<String, Long>()
        val ev = usm.queryEvents(from, to)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> open[e.packageName] = e.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED -> open.remove(e.packageName)?.let { s -> out.merge(e.packageName, a.movingOverlap(s, e.timeStamp) / 60_000.0, Double::plus) }
            }
        }
        for ((p, s) in open) out.merge(p, a.movingOverlap(s, to) / 60_000.0, Double::plus)
        return out.filter { it.value >= 0.1 && it.key != c.packageName && !it.key.contains("launcher") && !it.key.contains("systemui") }
    }

    override fun onDestroy() {
        if (analyzer != null) finish()
        thread.quitSafely()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Drive report", NotificationManager.IMPORTANCE_MIN).apply { description = "Shown only while a drive is being noted. Silent." })
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_owl)
            .setContentTitle("Odyssey").setContentText("Noting this drive. Stays on the phone.")
            .setOngoing(true).setSilent(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
    }
}

