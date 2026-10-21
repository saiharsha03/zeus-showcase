package dev.phonerecall.qol

import android.content.Context
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentsCore
import dev.phonerecall.odyssey.DriveService
import dev.phonerecall.odyssey.Geo
import kotlin.math.ceil

/**
 * "Home in N min": when someone asks where he is while he is driving, the first reply chip says when he'll be home,
 * from the drive's last fix and Home (straight line, a road factor, city pace, rounded up to 5 minutes). His tap sends.
 */
object HomeEta {
    private val ASKS = Regex("""(?i)\b(where (are|r) (you|u)|wru|when (will|r|are) (you|u) (be )?(home|back|here)|how far|eta\b|reached\??|leaving (yet|now)|on (the|your) way\?|coming home|kab (aa|aaoge|aa rahe)|ekkada)""")

    fun asks(text: String?) = text != null && ASKS.containsMatchIn(text)

    /** Minutes home: 1.3 times the straight line at about 40 km/h, rounded up to 5; null past 100 km. */
    fun minutes(meters: Double): Int? {
        if (meters > 100_000) return null
        val m = ceil(meters * 1.3 / 667.0).toInt().coerceAtLeast(1)
        return ((m + 4) / 5) * 5
    }

    fun words(min: Int) = "Home in $min min"

    /** The chip text when he is driving and the message asks; else null. */
    fun chip(c: Context, text: String?): String? {
        if (!asks(text) || MomentKind.HOME_ETA in MomentsCore.load(c).never) return null
        val d = dev.phonerecall.odyssey.Odyssey.state(c)
        if (!d.driving && !dev.phonerecall.music.MusicCore.get(c).driving) return null
        val f = DriveService.lastFix?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000L } ?: return null
        val home = d.home() ?: return null
        val meters = Geo.meters(f.lat, f.lng, home.lat, home.lng)
        if (meters < 300) return null
        return minutes(meters)?.let(::words)
    }
}
