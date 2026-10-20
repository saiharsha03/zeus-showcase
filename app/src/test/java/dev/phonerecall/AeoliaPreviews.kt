package dev.phonerecall

import dev.phonerecall.aeolia.WeatherAlert
import dev.phonerecall.aeolia.WeatherState

/** A fixed Buffalo morning for the Olympus shots: snow tapering by 2 PM, a winter storm warning until tomorrow. */
object AeoliaPreviews {
    fun state(): WeatherState {
        val now = System.currentTimeMillis()
        return WeatherState(
            place = "Buffalo", lat = 42.8864, lon = -78.8784, country = "US",
            tempC = -1.0, feelsC = -6.0, code = 73, isDay = true, highC = 1.0, lowC = -4.0, rainChance = 80,
            next = "Dry from 2 PM", nextAt = now + 4 * 3_600_000L, aqi = 31,
            quip = "Snow again. Your shovel and your patience are both on thin ice.",
            alerts = listOf(
                WeatherAlert(
                    "urn:oid:preview.1", "Winter Storm Warning", "Severe", "Expected",
                    headline = "Winter Storm Warning until 7 AM tomorrow. Heavy lake effect snow, 8 to 14 inches.",
                    area = "Northern Erie", ends = now + 22 * 3_600_000L,
                ),
            ),
            alertsChecked = true, updatedAt = now - 12 * 60_000L, alertsAt = now - 12 * 60_000L, loaded = true,
        )
    }
}
