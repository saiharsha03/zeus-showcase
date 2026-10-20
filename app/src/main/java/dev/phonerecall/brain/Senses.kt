package dev.phonerecall.brain

/*
 * Phase 2b senses: what a notification is, what is never kept, and what may leave the phone.
 * Everything here is pure (no Android), so the rules are unit-tested exactly as they run.
 */

/** One notification as the listener hands it over. The text never leaves the phone unsealed. */
data class Incoming(
    /** StatusBarNotification.key: links a later removal back to this post. */
    val key: String,
    val pkg: String,
    /** The app's label, "WhatsApp". */
    val app: String,
    val title: String,
    val text: String,
    val at: Long,
    /** The sender inside a group conversation (MessagingStyle), when the app says. */
    val sender: String? = null,
    /** The group's name, when it is a group conversation. */
    val conversation: String? = null,
    val isGroup: Boolean = false,
)

enum class AppKind(val word: String) {
    CHAT("chat"), SMS("text message"), DIALER("phone"), MAIL("email"), WORK("work chat"),
    BANK("bank"), PASSWORDS("password manager"), AUTH("authenticator"), DATING("dating"), OTHER("app");

    /** Kinds whose notifications are other people's words: their bodies never go to the cloud. */
    val personal: Boolean get() = this == CHAT || this == SMS || this == DIALER || this == MAIL || this == WORK || this == DATING
}

object AppKinds {
    val CHAT = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.instagram.android",
        "com.facebook.orca", "com.discord", "com.snapchat.android", "com.google.android.apps.dynamite", "jp.naver.line.android",
    )
    val SMS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.android.messaging")
    val DIALER = setOf(
        "com.google.android.dialer", "com.samsung.android.dialer", "com.android.server.telecom", "com.samsung.android.incallui",
        "com.android.dialer", "com.android.phone",
    )
    /** Apps whose notifications are never a sense, and mail from them. */
    val IGNORED = setOf("com.einnovation.temu")
    private val IGNORED_SENDERS = Regex("""(?i)\btemu\b""")
    private val IGNORED_BODY = Regex("""(?i)^\s*temu\b""")
    /** A mail notification from a sender he ignores: the title is the sender, or the body says it's from them. */
    fun ignoredSender(title: String, body: String): Boolean = IGNORED_SENDERS.containsMatchIn(title) || IGNORED_BODY.containsMatchIn(body)
    val TELEGRAM = setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus", "org.thunderdog.challegram", "nekox.messenger", "tw.nekomimi.nekogram")
    val MAIL = setOf("com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider", "com.yahoo.mobile.client.android.mail", "ch.protonmail.android")
    val WORK = setOf("com.microsoft.teams", "com.Slack", "com.microsoft.skydrive")
    val PASSWORDS = setOf(
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.onepassword.android", "com.lastpass.lpandroid", "com.dashlane",
        "com.samsung.android.samsungpass", "proton.android.pass", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
        "io.enpass.app", "com.nordpass.android.app.password.manager", "com.keepersecurity.android", "com.google.android.gms.passwords",
    )
    val AUTH = setOf(
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.duosecurity.duomobile",
        "com.okta.android.auth", "com.lastpass.authenticator", "org.fedorahosted.freeotp", "com.beemdevelopment.aegis",
    )
    val BANK = setOf(
        // US, including Buffalo's own banks (KeyBank, M&T).
        "com.chase.sig.android", "com.wf.wellsfargomobile", "com.infonow.bofa", "com.citi.citimobile", "com.konylabs.capitalone",
        "com.americanexpress.android.acctsvcs.us", "com.discoverfinancial.mobile", "com.usaa.mobile.android.usaa", "com.ally.MobileBanking",
        "com.sofi.mobile", "com.key.android", "com.mtb.mbanking.sc.retail.prod", "com.pnc.ecommerce.mobile", "com.td", "com.usbank.mobilebanking",
        "com.paypal.android.p2pmobile", "com.venmo", "com.zellepay.zelle", "com.squareup.cash", "com.robinhood.android",
        "com.fidelity.android", "com.vanguard", "com.schwab.mobile", "com.etrade.mobilepro.activity", "com.coinbase.android",
        "com.creditkarma.mobile", "com.navyfederal.android", "com.marcus.android", "com.chime.android",
        // India.
        "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.snapwork.hdfc", "com.hdfcbank.payzapp",
        "com.axis.mobile", "com.msf.kbank.mobile", "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "com.phonepe.app",
        "com.bankofbaroda.mconnect", "com.unionbank.ecommerce.mobile.android", "com.idfcfirstbank.optimus",
    )
    val DATING = setOf("com.tinder", "co.hinge.app", "com.bumble.app", "com.okcupid.okcupid", "com.coffeemeetsbagel")

    fun of(pkg: String, app: String = ""): AppKind = when {
        pkg in PASSWORDS -> AppKind.PASSWORDS
        pkg in AUTH -> AppKind.AUTH
        pkg in BANK || Regex("(?i)\\b(bank|credit union)\\b").containsMatchIn(app) -> AppKind.BANK
        pkg in DATING -> AppKind.DATING
        pkg in CHAT -> AppKind.CHAT
        pkg in SMS -> AppKind.SMS
        pkg in DIALER -> AppKind.DIALER
        pkg in MAIL -> AppKind.MAIL
        pkg in WORK -> AppKind.WORK
        else -> AppKind.OTHER
    }
}

/** What the listener does with one notification. */
sealed class Intake {
    /** Never stored. The reason is a category word, never the content. */
    data class Drop(val reason: String) : Intake()
    /** A bank notification: only the fact "bank alert from X" is kept, never the body. */
    data class BankFact(val app: String) : Intake()
    data object Keep : Intake()
}

object Exclusions {
    const val OWN_PACKAGE = "dev.phonerecall"

    private val CODE_WORDS = Regex(
        "(?i)(\\botp\\b|one[- ]time (pass(word|code)?|code|pin)|verification code|verify code|security code|log ?in code|" +
            "sign[- ]?in code|pass ?code|auth(entication)? code|confirmation code|access code|\\b2fa\\b|two[- ]factor|2[- ]step|" +
            "is your( [\\w-]+){0,2} code|your code is|\\bpin is\\b|do not share (this|the) code|don't share (this|the) code)",
    )
    /** A 4 to 8 digit code, also split as 123-456 or 123 456, or Google's G-123456. */
    private val CODE = Regex("(?<![\\d])(\\d{4,8}|\\d{3}[- ]\\d{3}|[A-Z]-\\d{5,6})(?![\\d])")
    private val PASSWORD_RESET = Regex("(?i)(reset (your )?password|password reset|magic link|sign[- ]in link|log ?in link)")
    private val BANK_SMS = Regex(
        "(?i)(\\b(debited|credited|a/c|acct\\.?|avl\\.? bal|available balance|txn|upi|neft|imps|rtgs)\\b|account ending|card ending|" +
            "\\bspent \\$|\\bcharged \\$|transaction of)",
    )

    /** True for one-time codes and verification messages, in any app. */
    fun isOtp(text: String): Boolean = CODE_WORDS.containsMatchIn(text) && CODE.containsMatchIn(text) ||
        Regex("(?i)\\bG-\\d{6}\\b").containsMatchIn(text) ||
        Regex("(?i)\\botp\\b").containsMatchIn(text)

    /**
     * The order matters: his own app, Calypso-tagged apps and people, password managers and
     * authenticators, codes anywhere, then banks (fact only). Everything else is kept.
     */
    fun check(n: Incoming, calypsoApps: Set<String> = emptySet(), calypsoPeople: Set<String> = emptySet()): Intake {
        if (n.pkg == OWN_PACKAGE) return Intake.Drop("own app")
        if (n.pkg in calypsoApps) return Intake.Drop("Calypso")
        val kind = AppKinds.of(n.pkg, n.app)
        if (kind == AppKind.PASSWORDS) return Intake.Drop("password manager")
        if (kind == AppKind.AUTH) return Intake.Drop("authenticator")
        val all = n.title + "\n" + n.text
        if (isOtp(all)) return Intake.Drop("code")
        if (PASSWORD_RESET.containsMatchIn(all)) return Intake.Drop("sign-in link")
        val who = Senders.of(n, kind)?.name
        if (who != null && People.keyOf(who) in calypsoPeople) return Intake.Drop("Calypso")
        if (n.conversation != null && People.keyOf(n.conversation) in calypsoPeople) return Intake.Drop("Calypso")
        if (kind == AppKind.BANK) return Intake.BankFact(n.app.ifBlank { "your bank" })
        if (kind == AppKind.SMS && BANK_SMS.containsMatchIn(all)) return Intake.BankFact("a bank text")
        return Intake.Keep
    }
}

/** Topics on Iris's off-limits list. Their words never go to the cloud. */
enum class Topic { MONEY, IMMIGRATION, HEALTH, DATING }

object Topics {
    private val MONEY = Regex(
        "(?i)(\\b(pay|paid|payment|bill|bills|invoice|rent|refund|refunds|bank|loan|emi|tax|taxes|irs|venmo|zelle|paypal|salary|" +
            "paycheck|deposit|balance|statement|subscription fee|insurance premium|credit card|debit card|wire|transfer money)\\b|\\$\\s?\\d|₹|\\brs\\.?\\s?\\d)",
    )
    private val IMMIGRATION = Regex(
        "(?i)\\b(visa|uscis|petition|residence permit|passport|immigration|consulate|embassy|" +
            "biometrics|work permit)\\b",
    )
    private val HEALTH = Regex(
        "(?i)\\b(doctor|dr\\.|dentist|dental|clinic|hospital|pharmacy|prescription|rx|meds|medicine|medication|therapy|therapist|" +
            "blood test|lab results|urgent care|vaccine|physio|diagnosis|symptoms|mri|x-ray)\\b",
    )
    private val DATING = Regex("(?i)\\b(tinder|hinge|bumble|okcupid|first date)\\b")

    fun of(text: String): Set<Topic> = buildSet {
        if (MONEY.containsMatchIn(text)) add(Topic.MONEY)
        if (IMMIGRATION.containsMatchIn(text)) add(Topic.IMMIGRATION)
        if (HEALTH.containsMatchIn(text)) add(Topic.HEALTH)
        if (DATING.containsMatchIn(text)) add(Topic.DATING)
    }
}

/** Small facts read off a notification's words, so the cloud can judge it without seeing them. */
data class Cues(
    val question: Boolean = false,
    val request: Boolean = false,
    /** "eod", "tonight", "tomorrow" or a clock time like "15:00". */
    val deadline: String? = null,
    val urgent: Boolean = false,
    val link: Boolean = false,
    val delivery: Boolean = false,
    val security: Boolean = false,
    val promo: Boolean = false,
) {
    fun words(): List<String> = listOfNotNull(
        "question".takeIf { question }, "request".takeIf { request }, deadline?.let { "deadline $it" }, "urgent".takeIf { urgent },
        "link".takeIf { link }, "delivery".takeIf { delivery }, "security".takeIf { security }, "promotion".takeIf { promo },
    )
}

object CueReader {
    private val REQUEST = Regex("(?i)\\b(can you|could you|would you|can u|could u|please|pls|plz|need you to|send (me|the|over|it)|make sure|don'?t forget|remember to|let me know)\\b")
    private val EOD = Regex("(?i)\\b(eod|e\\.o\\.d|end of (the )?day|cob|close of business)\\b")
    private val TONIGHT = Regex("(?i)\\btonight\\b")
    private val TOMORROW = Regex("(?i)\\b(tomorrow|tmrw|tmr)\\b")
    private val AT = Regex("(?i)\\bby (\\d{1,2})(?::(\\d{2}))?\\s?(am|pm)\\b")
    private val URGENT = Regex("(?i)\\b(asap|urgent|emergency|right now|immediately|call me)\\b")
    private val LINK = Regex("(?i)https?://|www\\.")
    private val DELIVERY = Regex("(?i)(\\bdelivered\\b|out for delivery|arriving (today|tomorrow)|\\bshipped\\b|your package|your order|refund (has been )?(issued|processed)|has been refunded)")
    private val SECURITY = Regex("(?i)(new sign-?in|new login|security alert|suspicious|unusual activity|login attempt|password (was )?changed|new device)")
    private val PROMO = Regex("(?i)(\\d+% off|\\bsale\\b|\\bdeals?\\b|\\boffer\\b|coupon|promo|limited time|don'?t miss|free shipping|cashback|\\bsubscribe\\b|flash sale)")

    fun read(text: String): Cues {
        val at = AT.find(text)?.let { m ->
            var h = m.groupValues[1].toInt() % 12
            if (m.groupValues[3].equals("pm", true)) h += 12
            "%02d:%s".format(h, m.groupValues[2].ifEmpty { "00" })
        }
        return Cues(
            question = text.contains('?'),
            request = REQUEST.containsMatchIn(text),
            deadline = when {
                EOD.containsMatchIn(text) -> "eod"
                at != null -> at
                TONIGHT.containsMatchIn(text) -> "tonight"
                TOMORROW.containsMatchIn(text) -> "tomorrow"
                else -> null
            },
            urgent = URGENT.containsMatchIn(text),
            link = LINK.containsMatchIn(text),
            delivery = DELIVERY.containsMatchIn(text),
            security = SECURITY.containsMatchIn(text),
            promo = PROMO.containsMatchIn(text),
        )
    }
}

/** Who a notification is from, and whether it is a missed call. */
data class Who(val name: String, val missedCall: Boolean = false, val calls: Int = 1)

object Senders {
    private val COUNT = Regex("\\s*\\((\\d+) (new )?messages?\\)\\s*$", RegexOption.IGNORE_CASE)
    private val MISSED = Regex("(?i)missed (voice |video )?call")
    private val MISSED_N = Regex("(?i)(\\d+) missed calls?")

    fun clean(name: String): String = name.replace(COUNT, "").trim().trim(':').trim()

    fun of(n: Incoming, kind: AppKind = AppKinds.of(n.pkg, n.app)): Who? {
        val title = clean(n.title)
        return when (kind) {
            AppKind.DIALER -> {
                val missed = MISSED.containsMatchIn(n.title) || MISSED.containsMatchIn(n.text)
                val name = if (MISSED.containsMatchIn(n.title)) n.text.lineSequence().firstOrNull()?.trim().orEmpty() else title
                val calls = MISSED_N.find(n.title + " " + n.text)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                name.takeIf { it.isNotBlank() && !MISSED.containsMatchIn(it) }?.let { Who(clean(it), missed, calls) }
            }
            AppKind.CHAT, AppKind.SMS, AppKind.WORK, AppKind.MAIL, AppKind.DATING -> {
                val sender = n.sender?.let(::clean)?.takeIf { it.isNotBlank() }
                    // WhatsApp groups title some posts "Family: Alex".
                    ?: if (n.isGroup && title.contains(':')) title.substringAfter(':').trim() else title
                sender.takeIf { it.isNotBlank() }?.let { Who(it) }
            }
            else -> null
        }
    }
}

/** The two lines a kept notification turns into. */
object Derive {
    /** What he sees later (sealed in storage): app, who, and the first words. Local only. */
    fun summary(n: Incoming, who: Who?): String {
        val head = listOfNotNull(n.app.ifBlank { null }, n.conversation, who?.name).distinct().joinToString(", ")
        val words = n.text.replace(Regex("\\s+"), " ").trim().take(140)
        return if (who?.missedCall == true) "$head: missed call" else if (words.isEmpty()) head else "$head: $words"
    }

    private val DIGITS = Regex("\\d")

    /**
     * The only form a notification ever takes on its way to Haiku: labels, never names, and no words
     * at all from people's messages or off-limits topics. App notifications may carry 100 characters
     * with digits masked.
     */
    fun cloudLine(index: Int, n: Incoming, kind: AppKind, label: String?, tier: Tier?, cues: Cues, topics: Set<Topic>, count: Int = 1): String {
        val parts = ArrayList<String>()
        parts += "#$index"
        parts += kind.word + if (n.isGroup) " group" else ""
        if (label != null) parts += "from $label"
        if (tier != null) parts += "tier ${tier.word}"
        if (count > 1) parts += "$count messages"
        if (topics.isNotEmpty()) parts += "topic " + topics.joinToString(", ") { it.name.lowercase() } + " (words withheld)"
        cues.words().takeIf { it.isNotEmpty() }?.let { parts += "cues: " + it.joinToString(", ") }
        if (!kind.personal && topics.isEmpty()) {
            parts += "app ${n.app}"
            val words = (n.title + ": " + n.text).replace(Regex("\\s+"), " ").trim().take(100).replace(DIGITS, "#")
            if (words.isNotBlank()) parts += "says \"$words\""
        }
        return parts.joinToString(" | ")
    }
}
