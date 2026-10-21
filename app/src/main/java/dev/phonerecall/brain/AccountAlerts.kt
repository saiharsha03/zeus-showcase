package dev.phonerecall.brain

import android.content.Context
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Account security alerts ("You allowed Zeus access to some of your Google Account data") read in plain words.
 * Zeus keeps a short log of its own sign-ins and consents; an alert that names the app Zeus just connected,
 * within half an hour, is him and gets a quiet card. Anything else stays a loud must-see card.
 */

enum class AlertKind { APP_ACCESS, SIGN_IN_WITH, BLOCKED, PASSWORD, RECOVERY, TWO_STEP, ACCESS_KEY, NEW_DEVICE }

/** One alert, read from the notification: who sent it ("google"), what happened, and the app it names, if any. */
data class AccountAlert(val provider: String, val kind: AlertKind, val app: String? = null) {
    val providerName get() = PROVIDERS[provider] ?: "account"

    /** "Zeus got access to your Google Account", in his words. */
    fun what(): String = when (kind) {
        AlertKind.APP_ACCESS -> "${app ?: "An app"} got access to your $providerName account"
        AlertKind.SIGN_IN_WITH -> "You signed in to ${app ?: "an app"} with $providerName"
        AlertKind.BLOCKED -> "$providerName blocked a suspicious sign-in"
        AlertKind.PASSWORD -> "Your $providerName password was changed"
        AlertKind.RECOVERY -> "Your $providerName recovery info changed"
        AlertKind.TWO_STEP -> "Your $providerName 2-Step Verification changed"
        AlertKind.ACCESS_KEY -> "A new access key was added to your $providerName account"
        AlertKind.NEW_DEVICE -> "New sign-in to your $providerName account"
    }

    companion object {
        val PROVIDERS = mapOf("google" to "Google", "github" to "GitHub", "anthropic" to "Anthropic", "microsoft" to "Microsoft")
    }
}

/** The sign-ins and consents Zeus itself starts. [what] and [why] finish "Zeus got access to your … at 8:01 PM …". */
enum class Consent(val app: String, val provider: String, val what: String, val why: String) {
    GOOGLE_CALENDAR("Zeus", "google", "Google Calendar", "when you connected birthdays"),
    GOOGLE_HOME("Zeus", "google", "Google Home", "when you connected Google Home"),
    SPOTIFY("Zeus", "spotify", "Spotify", "when you connected Spotify"),
}

data class OwnConsent(val consent: Consent, val at: Long)

/** What the card says: quiet ([expected]) or loud, the Athena line, and where "Review connections" or "Check it" goes. */
data class AlertCard(val key: String, val title: String, val body: String, val expected: Boolean, val line: String)

object AccountAlerts {
    const val WINDOW_MS = 30 * 60_000L
    /** Consents can land a moment after Google's mail is posted. */
    private const val AFTER_MS = 5 * 60_000L
    const val QUIET_MS = 24 * 3_600_000L
    const val LOUD_MS = 7 * 24 * 3_600_000L
    const val QUIET_SOURCE = "ACCOUNT"

    /** An app's name: up to four capitalized words ("Zeus", "Google Home"), matched case-sensitively. */
    private const val APP = """([A-Z0-9][\w.&'+-]*(?: [A-Z0-9][\w.&'+-]*){0,3})"""
    private val ACCESS = listOf(
        Regex("""(?i:you allowed )$APP(?i: access to)"""),
        Regex("""(?i:third-party (?:oauth |github )?application )\(([^)\n]{1,40})\)"""),
        Regex("""$APP(?i: (?:now )?has access to your (?:google|github|microsoft) account)"""),
    )
    private val SIGN_IN_WITH = listOf(
        Regex("""(?i:you shared some (?:google account )?data with )$APP"""),
        Regex("""(?i:signed in to )$APP(?i: (?:using|with) (?:your )?google)"""),
    )
    private val RULES = listOf(
        AlertKind.BLOCKED to Regex("""(?i)(suspicious|unusual) (sign-?in|activity|login)|blocked (a|the)? ?sign-?in|sign-?in attempt (was )?(prevented|blocked)|someone (just )?(used|knows|has) your password"""),
        AlertKind.PASSWORD to Regex("""(?i)password\b[^.\n]{0,40}?\b(was|has been) (changed|reset)|password (changed|reset)|changed your password"""),
        AlertKind.RECOVERY to Regex("""(?i)recovery (email|phone|number|info)"""),
        AlertKind.TWO_STEP to Regex("""(?i)2-step verification|two-step|two-factor|\b2fa\b"""),
        AlertKind.ACCESS_KEY to Regex("""(?i)personal access token|(ssh|deploy|gpg) key (was |has been )?added|new passkey"""),
        AlertKind.NEW_DEVICE to Regex("""(?i)new sign-?in|new login|signed in (on|from) a new|new device"""),
    )

    fun provider(text: String): String? {
        val t = text.lowercase()
        return when {
            "github" in t -> "github"
            "google" in t -> "google"
            "anthropic" in t || "claude.ai" in t -> "anthropic"
            "microsoft" in t || "outlook" in t -> "microsoft"
            else -> null
        }
    }

    /** Null when the words are no account alert it knows; the caller keeps the plain "Security alert" card then. */
    fun classify(title: String, text: String): AccountAlert? {
        val all = "$title\n$text"
        val provider = provider(all) ?: "other"
        fun app(rs: List<Regex>) = rs.firstNotNullOfOrNull { it.find(all)?.groupValues?.get(1)?.trim()?.trimEnd('.', ',')?.takeIf(String::isNotEmpty) }
        app(ACCESS)?.let { return AccountAlert(provider, AlertKind.APP_ACCESS, it) }
        app(SIGN_IN_WITH)?.let { return AccountAlert(provider, AlertKind.SIGN_IN_WITH, it) }
        return RULES.firstOrNull { it.second.containsMatchIn(all) }?.let { AccountAlert(provider, it.first) }
    }

    /** Cards a cleared notification may finish: never a loud security alert, which only he clears. */
    fun clearable(cards: List<String>): List<String> = cards.filterNot { it.startsWith("security:") }

    /** The newest consent of Zeus's own that this alert is about: same app and provider, within the window. */
    fun match(a: AccountAlert, at: Long, consents: List<OwnConsent>): OwnConsent? {
        if (a.kind != AlertKind.APP_ACCESS && a.kind != AlertKind.SIGN_IN_WITH) return null
        val app = a.app ?: return null
        return consents.filter { it.consent.provider == a.provider && it.consent.app.equals(app, ignoreCase = true) && it.at in (at - WINDOW_MS)..(at + AFTER_MS) }
            .maxByOrNull { it.at }
    }

    /** [known]: apps he connects himself (KnownApps); their sign-in and access alerts are quiet too. */
    fun card(a: AccountAlert, at: Long, consents: List<OwnConsent>, zone: ZoneId, known: List<String> = emptyList()): AlertCard {
        val key = "${a.provider}:${a.kind.name.lowercase()}:${a.app?.let(KnownApps::key) ?: ""}"
        val m = match(a, at, consents)
        if (m != null) {
            val time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).format(Instant.ofEpochMilli(m.at).atZone(zone))
            return AlertCard(
                // Its own key: an expected card never replaces a loud one.
                "security-ok:$key", "Security alert: this is you",
                "This is you: ${m.consent.app} got access to your ${m.consent.what} at $time ${m.consent.why}. Nothing to do.",
                expected = true, line = "${a.providerName} security alert: ${m.consent.app} got ${m.consent.what} access (expected).",
            )
        }
        val what = a.what()
        if (a.app != null && (a.kind == AlertKind.APP_ACCESS || a.kind == AlertKind.SIGN_IN_WITH) && known.any { KnownApps.key(it) == KnownApps.key(a.app) }) {
            return AlertCard(
                "security-ok:$key", "Security alert: a known app", "$what. ${a.app} is one of your known apps. Nothing to do.",
                expected = true, line = "${a.providerName} security alert: ${what.replaceFirstChar { it.lowercase() }} (known app).",
            )
        }
        return AlertCard("security:$key", "Security alert", "$what. Not from Zeus — check this.", expected = false,
            line = "${a.providerName} security alert: ${what.replaceFirstChar { it.lowercase() }} (not from Zeus).")
    }

    /** Where the card's button goes, by provider: his connected apps when it was him, the security page when it wasn't. */
    fun link(provider: String, expected: Boolean): String? = when (provider) {
        "google" -> if (expected) "https://myaccount.google.com/connections" else "https://myaccount.google.com/security"
        "github" -> "https://github.com/settings/security-log"
        else -> null
    }

    /** A sense card's buttons and link, from its key ("security:google:new_device:"). */
    fun actions(key: String, source: String): Pair<List<String>, String?> {
        val expected = source == QUIET_SOURCE
        if (!key.startsWith("security:") && !key.startsWith("security-ok:")) return listOf("Got it") to null
        val link = link(key.substringAfter(':').substringBefore(':'), expected) ?: return listOf("Got it") to null
        return listOf(if (expected) "Review connections" else "Check it", "Got it") to link
    }
}

/**
 * Apps he signs in to or connects himself ("Claude" to start). Edited in Themis; a "Got it" on a loud sign-in or
 * app-access alert adds that app. Plain prefs: app names only.
 */
object KnownApps {
    val DEFAULT = listOf("Claude")

    fun key(app: String) = app.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun sp(context: Context) = context.getSharedPreferences("account_alerts", Context.MODE_PRIVATE)

    fun get(context: Context): List<String> = sp(context).getString("known", null)?.lines()?.filter(String::isNotBlank) ?: DEFAULT

    fun set(context: Context, apps: List<String>) {
        val clean = apps.map { it.trim().take(40) }.filter(String::isNotEmpty).distinctBy(::key)
        sp(context).edit().putString("known", clean.joinToString("\n")).apply()
    }

    /** "Google Home" back from a card id ("gen:sense-security:google:app_access:google-home-1729..."), for a sign-in or access alert only. */
    fun appFromCard(id: String): String? {
        val k = id.substringAfter("sense-", "").takeIf { it.startsWith("security:") } ?: return null
        val parts = k.substringBeforeLast('-').split(':')
        if (parts.size != 4 || parts[2] !in setOf("sign_in_with", "app_access") || parts[3].isEmpty()) return null
        return parts[3].split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    /** He tapped Got it on [cardId]: the app it names is his from now on. */
    fun learn(context: Context, cardId: String) {
        val app = appFromCard(cardId) ?: return
        val now = get(context)
        if (now.none { key(it) == key(app) }) set(context, now + app)
    }
}

/** Zeus's own consents for the last 7 days, sealed in their own prefs. Written when a sign-in starts and when it finishes. */
class ConsentLog(context: Context, private val sealer: Sealer = KeystoreSealer("zeus.aegis.consents"), private val clock: () -> Long = System::currentTimeMillis) {
    private val sp = context.getSharedPreferences("consents", Context.MODE_PRIVATE)

    @Synchronized fun record(c: Consent) {
        val now = clock()
        val rows = recent() + OwnConsent(c, now)
        sp.edit().putString(KEY, sealer.seal(rows.joinToString("\n") { "${it.consent.name}|${it.at}" })).apply()
        dev.phonerecall.clio.Clio.info("aegis", "consent", "${c.what} sign-in")
    }

    @Synchronized fun recent(): List<OwnConsent> {
        val now = clock()
        val plain = sp.getString(KEY, null)?.let(sealer::open) ?: return emptyList()
        return plain.lines().mapNotNull { l ->
            val (name, at) = l.split('|').takeIf { it.size == 2 } ?: return@mapNotNull null
            val c = Consent.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
            at.toLongOrNull()?.takeIf { now - it < KEEP_MS }?.let { OwnConsent(c, it) }
        }
    }

    companion object {
        private const val KEY = "log"
        const val KEEP_MS = 7 * 86_400_000L
        @Volatile private var log: ConsentLog? = null
        fun get(context: Context): ConsentLog = log ?: synchronized(this) { log ?: ConsentLog(context.applicationContext).also { log = it } }

        /** Called where a flow starts or finishes, often on the main thread: the Keystore work runs off it, and never throws into it. */
        fun record(context: Context, c: Consent) { Thread { runCatching { get(context).record(c) } }.start() }
    }
}
