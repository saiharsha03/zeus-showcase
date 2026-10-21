package dev.phonerecall.olympia

import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * Where to watch. Who carries a fixture comes first from ESPN's own `broadcasts` on the schedules and
 * scoreboards Olympia already reads; [Broadcasters.table] covers the rest (the IPL, the F1 calendar from
 * Jolpica, Premier League games not assigned yet). Only apps on the phone count, the one he uses most
 * first. Opening tries the exact event only where the app is known to claim that page (ESPN's game
 * pages, checked against the app at the time), else the app's own launcher. No URL is ever guessed.
 */

/** A streaming service and its Android apps (Play Store ids, checked 2026-10-20). Each is in the manifest's `<queries>`. */
enum class Service(val label: String, val packages: List<String>) {
    PARAMOUNT("Paramount+", listOf("com.cbs.app")),
    /** ESPN's own app, or the ESPN tile in Disney+. */
    ESPN("ESPN", listOf("com.espn.score_center", "com.disney.disneyplus")),
    PEACOCK("Peacock", listOf("com.peacocktv.peacockandroid")),
    NETFLIX("Netflix", listOf("com.netflix.mediaclient")),
    PRIME("Prime Video", listOf("com.amazon.avod.thirdpartyclient")),
    APPLE_TV("Apple TV", listOf("com.apple.atve.androidtv.appletv")),
    YOUTUBE_TV("YouTube TV", listOf("com.google.android.apps.youtube.unplugged")),
    FOX("Fox One", listOf("com.fox.foxone", "com.foxsports.android")),
    WILLOW("Willow", listOf("tv.willow")),
    HOTSTAR("JioHotstar", listOf("in.startv.hotstar")),
    ;

    companion object {
        val packages: Set<String> get() = entries.flatMap { it.packages }.toSet()
    }
}

object Broadcasters {
    /** ESPN's network names to the app that streams them. Cable-only networks go to YouTube TV. */
    private val NAMES: Map<String, Service> = buildMap {
        for (n in listOf("paramount+", "cbs", "cbs sports golazo")) put(n, Service.PARAMOUNT)
        for (n in listOf("espn", "espn+", "espn2", "espnu", "espn deportes", "espnews", "abc", "disney+")) put(n, Service.ESPN)
        for (n in listOf("nbc", "peacock", "telemundo", "tele", "universo")) put(n, Service.PEACOCK)
        for (n in listOf("fox", "fs1", "fs2", "fox deportes", "fox one", "fox sports")) put(n, Service.FOX)
        for (n in listOf("prime video", "amazon prime video")) put(n, Service.PRIME)
        put("netflix", Service.NETFLIX)
        for (n in listOf("apple tv", "apple tv+", "mls season pass")) put(n, Service.APPLE_TV)
        for (n in listOf("youtube tv", "youtube", "nfl sunday ticket", "nfl net", "nfl network", "usa net", "usa network", "cbssn", "tnt", "tbs", "trutv")) put(n, Service.YOUTUBE_TV)
        put("willow", Service.WILLOW)
        for (n in listOf("jiohotstar", "hotstar", "star sports")) put(n, Service.HOTSTAR)
    }

    fun of(name: String): Service? = NAMES[name.trim().lowercase()]

    /**
     * Fallback only, for fixtures ESPN lists no broadcaster for. US rights as of 2026-10-20; the IPL
     * also in India. F1 is Apple TV from 2026 (ESPN's F1 board lists Apple TV for every session).
     * The NFL has no row: its network is per game and ESPN always carries it.
     */
    fun table(league: String, india: Boolean): List<Service> = when (league) {
        "uefa.champions" -> listOf(Service.PARAMOUNT)
        "esp.1", "esp.copa_del_rey", "esp.super_cup", "eng.fa" -> listOf(Service.ESPN)
        "eng.league_cup" -> listOf(Service.PARAMOUNT)
        "eng.1" -> listOf(Service.PEACOCK, Service.YOUTUBE_TV)
        "usa.1", "concacaf.leagues.cup" -> listOf(Service.APPLE_TV)
        "f1" -> listOf(Service.APPLE_TV)
        Teams.IPL -> if (india) listOf(Service.HOTSTAR, Service.WILLOW) else listOf(Service.WILLOW, Service.HOTSTAR)
        else -> emptyList()
    }

    /** Who carries it: the feed's names when it has any it knows, else the table. Cable (YouTube TV) goes last. */
    fun services(m: Match, india: Boolean = false): List<Service> =
        m.broadcasts.mapNotNull(::of).distinct().sortedBy { it == Service.YOUTUBE_TV }.ifEmpty { table(m.league, india) }
}

/** The app to open for a match: `exact` is the event's own page in that app, when there is one. */
data class WatchChoice(val service: Service, val pkg: String, val exact: String? = null) {
    val line: String get() = "Watch on ${if (pkg == "com.disney.disneyplus") "Disney+" else service.label}"
}

/** One way to open the stream, tried in order: the event page in the app, then the app itself. */
sealed interface WatchStep {
    data class Page(val url: String, val pkg: String) : WatchStep
    data class Launch(val pkg: String) : WatchStep
}

object Watch {
    /** The Home card and the Now Bar chip come up this long before the start. */
    const val BEFORE = 30 * MatchDay.MIN

    /**
     * Of the services carrying it, the installed app he spends most time in (the last month); on a
     * tie, the order the broadcaster listed them. Null when none is on the phone.
     */
    fun choose(m: Match, installed: (String) -> Boolean, usage: Map<String, Long>, india: Boolean = false): WatchChoice? =
        Broadcasters.services(m, india).flatMap { s -> s.packages.filter(installed).map { s to it } }
            .withIndex().maxWithOrNull(compareBy<IndexedValue<Pair<Service, String>>> { usage[it.value.second] ?: 0L }.thenByDescending { it.index })
            ?.value?.let { (s, pkg) -> WatchChoice(s, pkg, page(m, pkg)) }

    /**
     * The event's own page, only where it is known to be right: ESPN's game page for ESPN's own event
     * id, opened in the ESPN app (it carries the Watch button for ESPN+ and ABC games). F1 rows carry
     * Jolpica ids and cricket lives on Cricinfo, so neither gets one.
     */
    fun page(m: Match, pkg: String): String? {
        if (pkg != "com.espn.score_center" || !m.id.all { it.isDigit() }) return null
        return when (m.sport) {
            Sport.SOCCER -> "https://www.espn.com/soccer/match/_/gameId/${m.id}"
            Sport.NFL -> "https://www.espn.com/nfl/game/_/gameId/${m.id}"
            else -> null
        }
    }

    fun steps(c: WatchChoice): List<WatchStep> = listOfNotNull(c.exact?.let { WatchStep.Page(it, c.pkg) }, WatchStep.Launch(c.pkg))

    /** The next time a Watch card comes up, for Olympia's one alarm. */
    fun nextWake(matches: List<Match>, now: Long): Long? =
        matches.filter { it.phase == Phase.PRE }.map { it.start - BEFORE }.filter { it > now }.minOrNull()

    /** Before the start, inside the window and not yet started. */
    fun due(m: Match, now: Long): Boolean = m.phase == Phase.PRE && now >= m.start - BEFORE && now < m.start

    /** The line on the card and the chip: "UCL · 3:00 PM · Watch on Paramount+". */
    fun line(m: Match, now: ZonedDateTime, choice: WatchChoice?): String =
        listOfNotNull(m.leagueName.ifEmpty { null }, dev.phonerecall.home.Fmt.time(m.start, now), choice?.line).joinToString(" · ")
}

/**
 * His quiet rules for match prompts. On a call or driving nothing buzzes; in a game nothing new comes
 * up over it (no Now Bar chip; the Home card waits). Zeus never opens an app by itself either way.
 */
data class WatchQuiet(val inCall: Boolean = false, val driving: Boolean = false, val gaming: Boolean = false) {
    val buzz: Boolean get() = !inCall && !driving
    val promote: Boolean get() = !gaming
}

/**
 * The phone side: which apps are installed, which he uses most, and the intent that opens one. Usage
 * is read once every few hours; it needs Usage access, and without it the broadcaster's order decides.
 */
object WatchApps {
    @Volatile private var usage: Map<String, Long> = emptyMap()
    @Volatile private var usageAt = 0L

    fun installed(c: Context): (String) -> Boolean = { pkg -> c.packageManager.getLaunchIntentForPackage(pkg) != null }

    private fun usage(c: Context): Map<String, Long> {
        val now = System.currentTimeMillis()
        if (now - usageAt < 6 * MatchDay.HOUR) return usage
        usage = runCatching {
            c.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(now - 30 * 86_400_000L, now)
                .filterKeys { it in Service.packages }.mapValues { it.value.totalTimeInForeground }
        }.getOrDefault(emptyMap())
        usageAt = now
        return usage
    }

    private fun india(): Boolean = ZoneId.systemDefault().id == "Asia/Kolkata"

    fun chooser(c: Context): (Match) -> WatchChoice? {
        val app = c.applicationContext
        val has = installed(app)
        val use = usage(app)
        val india = india()
        return { m -> Watch.choose(m, has, use, india) }
    }

    fun choose(c: Context, m: Match): WatchChoice? = chooser(c)(m)

    /** The first step the phone can take: the page if the app claims it, else the app's launcher. */
    fun intent(c: Context, choice: WatchChoice): Intent? {
        val pm = c.packageManager
        for (s in Watch.steps(choice)) {
            val i = when (s) {
                is WatchStep.Page -> Intent(Intent.ACTION_VIEW, Uri.parse(s.url)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(s.pkg)
                    .takeIf { pm.resolveActivity(it, 0) != null }
                is WatchStep.Launch -> pm.getLaunchIntentForPackage(s.pkg)
            }
            if (i != null) return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return null
    }

    private fun match(c: Context, id: String): Match? {
        val s = OlympiaStore.load(c)
        return (s.followed + s.big).firstOrNull { it.id == id }
    }

    /** From a tap on a card: opens the app, or returns false when nothing can (Olympia opens instead). */
    fun open(c: Context, matchId: String): Boolean {
        val m = match(c, matchId) ?: return false
        val i = choose(c, m)?.let { intent(c, it) } ?: return false
        return runCatching { c.startActivity(i) }.isSuccess
    }

    /** The notification's Watch button: straight into the app, no trampoline. */
    fun pending(c: Context, m: Match?): PendingIntent? {
        m ?: return null
        val i = choose(c, m)?.let { intent(c, it) } ?: return null
        return PendingIntent.getActivity(c, 62_500 + (m.id.hashCode() and 0xff), i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun pending(c: Context, matchId: String?): PendingIntent? = matchId?.let { pending(c, match(c, it)) }

    fun quiet(c: Context): WatchQuiet {
        val mode = runCatching { c.getSystemService(AudioManager::class.java).mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return WatchQuiet(
            inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION,
            driving = runCatching { dev.phonerecall.music.MusicCore.get(c).driving }.getOrDefault(false),
            gaming = dev.phonerecall.alarms.ForegroundApp.usage(c).current()?.let { it in dev.phonerecall.alarms.ForegroundApp.GAMES } == true,
        )
    }
}

/**
 * Big matches he doesn't follow: finals, the great derbies, NFL playoffs and the Super Bowl. Only in a
 * sport he follows something in, and only when that sport's switch is on in Themis.
 */
object BigMatch {
    /** ESPN team ids, both orders. Barça and Real Madrid are followed by default; the pair still counts if not. */
    private val DERBIES = listOf(
        "83" to "86", "86" to "1068", "83" to "1068", // El Clásico, the Madrid derby, Barça v Atlético
        "359" to "367", "360" to "382", "364" to "360", "364" to "368", "359" to "363", // NLD, Manchester, Liverpool v United, Merseyside, Arsenal v Chelsea
        "103" to "110", "132" to "124", // Derby della Madonnina, Der Klassiker
    ).flatMap { (a, b) -> listOf(a to b, b to a) }.toSet()

    /** The leagues looked at for big matches, by sport. */
    val LEAGUES: Map<Sport, List<String>> = mapOf(
        Sport.SOCCER to listOf("uefa.champions", "esp.1", "eng.1", "esp.copa_del_rey", "eng.fa"),
        Sport.NFL to listOf("nfl"),
    )

    fun isBig(m: Match): Boolean = when (m.sport) {
        Sport.SOCCER -> m.stage == "final" || (m.home != null && m.away != null && (m.home.id to m.away.id) in DERBIES)
        Sport.NFL -> m.stage == "post-season"
        else -> false
    }

    /** The sports he follows a team in, with their big-match switch on. */
    fun sports(st: OlympiaSettings): Set<Sport> = st.followed.mapNotNull { Teams.byKey(it)?.sport }.toSet().intersect(st.big)

    /** From a league scoreboard: big, not started, and not one he follows anyway (that gets its own card). */
    fun pick(matches: List<Match>, st: OlympiaSettings): List<Match> = matches.filter { m ->
        m.phase == Phase.PRE && m.sport in sports(st) && !st.follows(m) && isBig(m) &&
            (Competition.of(m.league)?.let { it in st.competitions } ?: true)
    }

    /** Tonight: later today on his clock, within 12 hours. */
    fun tonight(big: List<Match>, now: ZonedDateTime): Match? {
        val ms = now.toInstant().toEpochMilli()
        return big.filter { it.phase == Phase.PRE && it.start > ms && it.start - ms <= 12 * MatchDay.HOUR && Instant.ofEpochMilli(it.start).atZone(now.zone).toLocalDate() == now.toLocalDate() }
            .minByOrNull { it.start }
    }
}
