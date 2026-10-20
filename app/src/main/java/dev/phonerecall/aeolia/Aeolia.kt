package dev.phonerecall.aeolia

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.brain.QuietHours
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.Fact
import dev.phonerecall.home.Stat
import dev.phonerecall.ui.TileForm
import dev.phonerecall.ui.Tone
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Aeolia's wiring outside the page: a check every 30 minutes (WorkManager, any network) refreshes the
 * forecast, NWS alerts and air for the place on screen, then posts what [AlertRule] allows.
 */
object Aeolia {
    private const val WORK = "aeolia-check"
    private const val TAG = "Zeus-Aeolia"
    /** BetweenWeather's starting place. */
    const val DEFAULT_SELECTION = """{"lat":42.8864,"lon":-78.8784,"name":"Buffalo","country":"US"}"""

    fun start(c: Context) {
        val app = c.applicationContext
        thread(name = "aeolia-start") {
            WeatherStore.load(app)
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeatherWorker>(30, TimeUnit.MINUTES).setConstraints(net).build(),
            )
        }
    }

    /** The page's place: {"lat","lon","name","country"}. */
    fun selection(c: Context): JSONObject = runCatching { JSONObject(WeatherStore.prefs(c).getString("selection", null) ?: DEFAULT_SELECTION) }
        .getOrDefault(JSONObject(DEFAULT_SELECTION))

    /** Alert notifications: on unless he turned them off in the page's settings. */
    fun notificationsOn(c: Context) = WeatherStore.prefs(c).getBoolean("notifications", true)

    /** A new forecast for [lat],[lon] (raw JSON as fetched): stored for the page and folded into the state. */
    fun forecastArrived(c: Context, lat: Double, lon: Double, name: String, country: String, raw: String, now: Long = System.currentTimeMillis()) {
        WeatherStore.prefs(c).edit()
            .putString("selection", JSONObject().put("lat", lat).put("lon", lon).put("name", name).put("country", country).toString())
            .putString("forecast", raw).putLong("updated", now).apply()
        val fc = WeatherParse.forecast(raw) ?: return
        WeatherStore.update(c) { s ->
            val moved = s.place != name || !near(s.lat, lat) || !near(s.lon, lon)
            WeatherState.withForecast(
                if (moved) s.copy(place = name, lat = lat, lon = lon, country = country, alerts = emptyList(), alertsChecked = false, aqi = null, quip = null)
                else s.copy(place = name, country = country),
                fc, now,
            )
        }
    }

    /** NWS alerts for the place on screen; null raw means they could not be checked. Then the rule decides what to post. */
    fun alertsArrived(c: Context, lat: Double, lon: Double, raw: String?, now: Long = System.currentTimeMillis()) {
        val s = WeatherStore.load(c)
        if (!near(s.lat, lat) || !near(s.lon, lon)) return
        val next = WeatherStore.update(c) {
            if (raw == null) it.copy(alertsChecked = false)
            else it.copy(alerts = WeatherParse.alerts(raw, now), alertsChecked = true, alertsAt = now)
        }
        if (raw != null) AlertNotifier.consider(c, next, ZonedDateTime.now())
    }

    fun airArrived(c: Context, lat: Double, lon: Double, raw: String) {
        val s = WeatherStore.load(c)
        if (!near(s.lat, lat) || !near(s.lon, lon)) return
        WeatherParse.aqi(raw)?.let { aqi -> WeatherStore.update(c) { it.copy(aqi = aqi) } }
    }

    fun near(a: Double, b: Double) = a.isFinite() && b.isFinite() && kotlin.math.abs(a - b) < 0.001

    /** The background check: same requests the page makes, for the page's place. */
    fun check(c: Context) {
        val sel = selection(c)
        val lat = sel.optDouble("lat"); val lon = sel.optDouble("lon")
        if (!coords(lat, lon)) return
        val name = sel.optString("name", "Weather"); val country = sel.optString("country")
        forecastArrived(c, lat, lon, name, country, WeatherNet.text(WeatherNet.forecastUrl(lat, lon)))
        if (country != "IN") alertsArrived(c, lat, lon, runCatching { WeatherNet.text(WeatherNet.alertsUrl(lat, lon)) }.onFailure { Log.w(TAG, "alerts: ${it.message}") }.getOrNull())
        runCatching { airArrived(c, lat, lon, WeatherNet.text(WeatherNet.airUrl(lat, lon))) }
    }

    fun coords(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180
}

class WeatherWorker(c: Context, p: WorkerParameters) : CoroutineWorker(c, p) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result = try {
        Aeolia.check(applicationContext); Result.success()
    } catch (e: Exception) {
        Log.w("Zeus-Aeolia", "check failed: ${e.message}")
        if (runAttemptCount < 2) Result.retry() else Result.success()
    }
}

/**
 * Which NWS alerts become a phone notification. Extreme and Severe always (quiet hours too). Moderate
 * only outside quiet hours; one still active at 7 AM is posted then. Minor and Unknown never: they stay
 * on the card. Each alert once, and an Update of one already posted is not posted again.
 */
object AlertRule {
    fun notify(a: WeatherAlert, now: ZonedDateTime, posted: Set<String>, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (a.ends <= now.toInstant().toEpochMilli()) return false
        if (a.id in posted || a.references.any { it in posted }) return false
        return when (a.severity) {
            "Extreme", "Severe" -> true
            "Moderate" -> !QuietHours.quiet(now)
            else -> false
        }
    }

    /** Updates of posted alerts are marked posted too, so their next update is caught by id. */
    fun markPosted(alerts: List<WeatherAlert>, posted: Set<String>): Set<String> =
        posted + alerts.filter { a -> a.id in posted || a.references.any { it in posted } }.map { it.id }
}

object AlertNotifier {
    const val CHANNEL = "aeolia.alerts"
    private const val KEEP = 200

    fun channel(c: Context) {
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Aeolia: weather alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "Official NWS warnings for the place in Aeolia" },
        )
    }

    fun consider(c: Context, s: WeatherState, now: ZonedDateTime) {
        val sp = WeatherStore.prefs(c)
        var posted = runCatching { JSONArray(sp.getString("posted", "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
        posted = AlertRule.markPosted(s.alerts, posted)
        val enabled = Aeolia.notificationsOn(c) &&
            c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        for (a in s.alerts) {
            if (!AlertRule.notify(a, now, posted, enabled)) continue
            post(c, a, s.place)
            posted = posted + a.id
        }
        sp.edit().putString("posted", JSONArray(posted.toList().takeLast(KEEP)).toString()).apply()
    }

    private fun post(c: Context, a: WeatherAlert, place: String) {
        channel(c)
        val open = PendingIntent.getActivity(
            c, 4400, Intent(c, MainActivity::class.java).putExtra(MainActivity.EXTRA_DEST, "WEATHER").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = a.headline.ifBlank { "${a.event} for $place" }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(a.event.ifBlank { "Weather alert" })
            .setContentText(text.take(200))
            .setStyle(NotificationCompat.BigTextStyle().bigText(listOf(text, a.instruction).filter { it.isNotBlank() }.joinToString("\n\n").take(900)))
            .setCategory(NotificationCompat.CATEGORY_ALARM.takeIf { a.severity == "Extreme" } ?: NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { c.getSystemService(NotificationManager::class.java).notify("aeolia", a.id.hashCode(), n) }
    }
}

/** The Olympus cards: the weather here and now, and an NWS alert while one is active. */
object WeatherCards {
    private val OPEN = CardAction("Open Aeolia", ActionRole.OPEN_WEATHER)
    /** Older than this, the card says nothing rather than something stale. */
    const val STALE = 6 * 3_600_000L

    fun pair(c: Double) = "${WeatherState.f(c)}°F / ${c.roundToInt()}°C"

    fun weather(s: WeatherState, now: Long): CardContent? {
        if (!s.hasData || now - s.updatedAt > STALE) return null
        val t = s.tempC!!
        return CardContent(
            title = s.next ?: "${Sky.words(s.code)}, no change ahead",
            body = s.quip,
            stat = Stat("${WeatherState.f(t)}°", "${t.roundToInt()}°C", TileForm.CIRCLE),
            facts = listOfNotNull(
                Fact("Now", Sky.words(s.code) + (s.feelsC?.let { ", feels like ${WeatherState.f(it)}°F" } ?: "")),
                s.highC?.let { Fact("High", pair(it)) },
                s.lowC?.let { Fact("Low", pair(it)) },
                s.aqi?.takeIf { it > 100 }?.let { Fact("Air", "AQI $it", tone = Tone.ACTIVE) },
            ),
            status = s.place.ifBlank { null },
            actions = listOf(OPEN),
        )
    }

    /** The most severe active alert; Extreme and Severe lift it to the top of Olympus. */
    fun alert(s: WeatherState, now: Long): Pair<CardContent, Float>? {
        val active = s.activeAlerts(now)
        val a = active.firstOrNull() ?: return null
        if (a.rank < 2) return null
        val zone = java.time.ZoneId.systemDefault()
        val end = java.time.Instant.ofEpochMilli(a.ends).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate(), end.toLocalDate())
        val ends = when (days) { 0L -> ""; 1L -> "Tomorrow, "; else -> dev.phonerecall.home.Fmt.shortDate(end) + ", " } + dev.phonerecall.home.Fmt.time(end)
        return CardContent(
            title = a.event,
            body = a.headline.ifBlank { null },
            facts = listOfNotNull(
                Fact("Until", ends),
                Fact("Where", s.place),
                if (active.size > 1) Fact("Also", active.drop(1).joinToString(", ") { it.event }) else null,
            ),
            status = a.severity, tone = if (a.severe) Tone.ALERT else Tone.ACTIVE,
            actions = listOf(OPEN),
        ) to if (a.severe) 40f else 12f
    }
}
