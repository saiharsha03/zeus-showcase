package dev.phonerecall.mind

import android.content.Context
import dev.phonerecall.aeolia.WeatherStore
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.Different
import dev.phonerecall.brain.TwoClocks
import dev.phonerecall.odyssey.OdysseyBrain
import java.time.ZonedDateTime

/** Gathers the digest's inputs from the phone's own stores (no network). */
object DifferentSource {
    fun lines(app: Context, core: BrainCore, now: ZonedDateTime): List<String> {
        val reads = BackgroundReads(app, core)
        val from = now.toInstant().toEpochMilli()
        val w = WeatherStore.load(app)
        return Different.lines(Different.Inputs(
            now = now, clocks = TwoClocks(now.zone), alarm = Different.alarm,
            events = runCatching { reads.relevantEvents(from, from + 14 * 86_400_000L) }.getOrDefault(emptyList()),
            weatherNext = w.next?.takeIf { w.hasData },
            weatherAlerts = w.activeAlerts(from).filter { it.rank >= 2 }.map { it.event }.distinct().take(3),
            odyssey = runCatching { OdysseyBrain.factsNow(app, from) }.getOrDefault(emptyList()),
        ))
    }
}
