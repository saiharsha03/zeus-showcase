package dev.phonerecall.moneta

/*
 * Bank and card notifications into transactions. Only what Moneta needs is kept: the amount, a cleaned
 * merchant name, the bank, the last four digits of the card and the time. Never a full card or account
 * number, never a balance, never the notification text itself.
 */

enum class TxnKind(val word: String, val spend: Boolean) {
    SPEND("Spent", true), REFUND("Refund", false), PAYMENT("Card payment", false), TRANSFER("Transfer", false),
    INCOME("Deposit", false), DECLINED("Declined", false),
}

enum class Source(val word: String) { NOTIFICATION("Bank alert"), MANUAL("You"), IRIS("Iris") }

/** Which kind of phone alert a row came from. Higher rank names the merchant and card better, so it is the row kept. */
enum class Via(val word: String, val rank: Int) { APP("bank app", 3), WALLET("wallet", 2), SMS("bank text", 1) }

data class Txn(
    val id: Long = 0,
    val at: Long,
    val cents: Long,
    val kind: TxnKind,
    val merchant: String,
    val category: Category,
    val bank: String? = null,
    /** At most four digits. */
    val last4: String? = null,
    val source: Source = Source.NOTIFICATION,
    val note: String? = null,
    /** A stable outside id: "iris:812" for Iris's rows, or the key Iris gave an op. Null for the phone's own rows. */
    val key: String? = null,
    val currency: String = "USD",
    /** Folded into another transaction (a duplicate): kept for undo, left out of every total. */
    val mergedInto: String? = null,
    /** Changed by an op (Iris's or his), so a later ledger sync doesn't overwrite it. */
    val edited: Boolean = false,
    /** The phone alert it came from (null for Iris's and his own rows). */
    val via: Via? = null,
    /** Authorized, not yet posted: a later row with the final amount replaces it. */
    val pending: Boolean = false,
) {
    /** How ops and the change log name this row. */
    val ref: String get() = key ?: "local:$id"
    /** Counted in totals and charts: not a folded duplicate, not a decline, and in dollars. */
    val counted: Boolean get() = mergedInto == null && currency == "USD" && kind != TxnKind.DECLINED
}

object BankApps {
    /** The bank apps Moneta reads, by package, and the bank name Moneta shows. */
    val NAMES = mapOf(
        "com.chase.sig.android" to "Chase",
        "com.infonow.bofa" to "Bank of America",
        "com.americanexpress.android.acctsvcs.us" to "Amex",
        "com.konylabs.capitalone" to "Capital One",
        "com.capitalone.mobile" to "Capital One",
        "com.capitalone.android" to "Capital One",
        "com.key.android" to "KeyBank",
        "com.mtb.mbanking.sc.retail.prod" to "M&T",
        "com.discoverfinancial.mobile" to "Discover",
        "com.discover.mobile" to "Discover",
        "com.citi.citimobile" to "Citi",
        "com.citibank.mobile.us" to "Citi",
        "com.wf.wellsfargomobile" to "Wells Fargo",
    )
    /** Tap-to-pay wallets. They name the card's bank in the text, if at all. */
    val WALLETS = mapOf(
        "com.google.android.apps.walletnfcrel" to "Google Wallet",
        "com.google.android.apps.wallet" to "Google Wallet",
        "com.google.android.apps.nbu.paisa.user" to "Google Pay",
        "com.samsung.android.spay" to "Samsung Wallet",
    )
    val SMS = setOf("com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms", "com.verizon.messaging.vzmsgs", "com.textra")

    fun bank(pkg: String, app: String): String? = NAMES[pkg] ?: app.takeIf { Regex("(?i)\\b(bank|credit union|card|financial)\\b").containsMatchIn(it) }

    fun via(pkg: String): Via = when (pkg) { in WALLETS -> Via.WALLET; in SMS -> Via.SMS; else -> Via.APP }
}

object BankParser {
    private val AMOUNT = Regex("(?:\\$|USD\\s?|\\(\\\$USD\\)\\s?)\\s?(\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.(\\d{2}))?")
    private val NOT_AMOUNT = Regex("(?i)(balance|limit|available|minimum|min\\.? payment|statement balance|credit line)[^$]{0,24}$")
    private val LAST4 = Regex("(?i)(?:ending(?:\\s+in|\\s+with)?|ends\\s+in|last\\s+4(?:\\s+digits)?|acct\\.?|account|card)\\s*(?:#|no\\.?|number|:)?\\s*[x*•.\\-]*\\s*(\\d{4,6})\\b|[x*•]{2,}\\s?(\\d{4})\\b")
    /** Any run of 7+ digits (with spaces or dashes) is treated as a card or account number and cut. */
    private val LONG_NUMBER = Regex("\\d(?:[ -]?\\d){6,}")
    private val STOP = "(?=\\s+(?:was|were|has|have|is|on|for|using|with|ending|via|and|from|to|at|in|of)\\b|\\s+\\d{1,2}/\\d{1,2}|[.;,!(]|\\s*$)"
    private val MERCHANT = listOf("at", "with", "from", "to", "merchant:?").map { Regex("(?i)\\b$it\\s+(?!your\\b|the\\b|you\\b|a\\b|an\\b)([A-Za-z0-9][A-Za-z0-9 &'*#./_-]{1,48}?)$STOP") }
    private val TIME = Regex("(?i)^\\d{1,2}(:\\d{2})?\\s?(am|pm|et|est|edt)?\\b|^(mon|tues?|wed|thu|thurs|fri|sat|sun|monday|tuesday|wednesday|thursday|friday|saturday|sunday|jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec|" +
        "january|february|march|april|june|july|august|september|october|november|december)\\.?\\s+\\d{1,2}\\b")
    private val OTP = Regex("(?i)\\b(code|otp|passcode|verification|verify|one[- ]time|sign[- ]?in|log[- ]?in|password)\\b")
    private val PROMO = Regex("(?i)\\b(offer|earn \\d|cash ?back offer|apply now|pre-?approved|limited time|reward(s)? points|upgrade|deal)\\b")

    private fun kind(t: String): TxnKind? = when {
        Regex("(?i)\\bdeclin|\\bdenied\\b").containsMatchIn(t) -> TxnKind.DECLINED
        Regex("(?i)\\brefund|\\breturn(ed)? credit|\\breversal|\\bcredit(ed)? (of|to your)|\\bstatement credit").containsMatchIn(t) -> TxnKind.REFUND
        Regex("(?i)\\b(direct deposit|deposit(ed)?|payroll|you received|received \\$)").containsMatchIn(t) -> TxnKind.INCOME
        Regex("(?i)\\b(payment (of|was|has been|is) .*?(received|posted|scheduled|processed)|thank you for your payment|autopay payment|payment received|payment posted)").containsMatchIn(t) -> TxnKind.PAYMENT
        Regex("(?i)\\b(zelle|venmo|transfer(red)?|sent to|wire)\\b").containsMatchIn(t) -> TxnKind.TRANSFER
        Regex("(?i)\\b(purchase|transaction|charge[ds]?|spent|was used|used for|authori[sz]ed|withdrawal|debit(ed)?|approved|paid)\\b").containsMatchIn(t) -> TxnKind.SPEND
        else -> null
    }

    private fun amount(t: String): Long? = AMOUNT.findAll(t).firstOrNull { !NOT_AMOUNT.containsMatchIn(t.substring(0, it.range.first)) }?.let { m ->
        val dollars = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        dollars * 100 + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0)
    }

    fun last4(t: String): String? = LAST4.find(t)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).takeLast(4) }

    private fun merchant(t: String): String? {
        val clean = LONG_NUMBER.replace(AMOUNT.replace(t, " "), " ")
        // The earliest "at X", "with X", "from X" or "to X" wins; times and dates are never merchants.
        return MERCHANT.flatMap { r -> r.findAll(clean).toList() }.sortedBy { it.range.first }.map { it.groupValues[1] }
            .filterNot { TIME.containsMatchIn(it) }
            .map { Merchants.clean(it) }
            .firstOrNull { m -> m.length >= 2 && !Regex("(?i)^(card|account|your|you|ending|checking|savings)\\b").containsMatchIn(m) }
    }

    /**
     * A transaction from one notification, or null when it isn't one (codes, balances, offers, statements).
     * Only bank apps and bank texts are read at all.
     */
    fun parse(pkg: String, app: String, title: String, text: String, at: Long): Txn? {
        val via = BankApps.via(pkg)
        val bank = when (via) {
            Via.APP -> BankApps.bank(pkg, app)
            Via.WALLET -> smsBank("$title $text")?.takeIf { it != "Bank text" } ?: BankApps.WALLETS[pkg]
            Via.SMS -> smsBank("$title $text")
        } ?: return null
        val all = "$title. $text".replace(Regex("\\s+"), " ").trim()
        if (OTP.containsMatchIn(all) || PROMO.containsMatchIn(all)) return null
        // A wallet's tap alert is just "Starbucks / $5.25 with Visa ••1234": a purchase on that card.
        val kind = kind(all) ?: if (via == Via.WALLET) TxnKind.SPEND else return null
        val cents = amount(all) ?: return null
        if (cents <= 0 || cents > 10_000_000_00L) return null
        val merchant = if (via == Via.WALLET) title.takeIf { it.isNotBlank() && !WALLET_TITLE.containsMatchIn(it) }?.let { Merchants.clean(it) } else merchant(all)
        val name = merchant ?: if (kind == TxnKind.PAYMENT) "Card payment" else if (kind == TxnKind.INCOME) "Deposit" else if (via == Via.WALLET) NO_MERCHANT else return null
        return Txn(
            at = at, cents = cents, kind = kind, merchant = name, category = if (kind.spend || kind == TxnKind.REFUND) Categories.of(name) else Category.TRANSFERS,
            bank = bank, last4 = last4(all), via = via, pending = PENDING.containsMatchIn(all),
        )
    }

    /** The merchant of an alert that didn't name one. */
    const val NO_MERCHANT = "Card purchase"
    private val WALLET_TITLE = Regex("(?i)google|wallet|samsung|\\bpay\\b|purchase|payment")
    private val PENDING = Regex("(?i)\\b(pending|authori[sz]ed|authori[sz]ation|hold)\\b")

    private fun smsBank(t: String): String? = BankApps.NAMES.values.firstOrNull { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(t) }
        ?: if (Regex("(?i)\\b(card|acct|account) (ending|ending in|xx)").containsMatchIn(t)) "Bank text" else null
}

object Merchants {
    private val ALIASES = listOf(
        "WHOLEFDS|WHOLE FOODS" to "Whole Foods", "COSTCO" to "Costco", "WEGMANS" to "Wegmans", "TOPS MKT|TOPS MARKETS|TOPS FRIENDLY" to "Tops",
        "ALDI" to "Aldi", "TRADER JOE" to "Trader Joe's", "AMZN|AMAZON" to "Amazon", "TARGET" to "Target", "WAL-?MART|WM SUPERCENTER" to "Walmart",
        "STARBUCKS|SBUX" to "Starbucks", "TIM HORTONS" to "Tim Hortons", "DUNKIN" to "Dunkin'", "UBER\\s*EATS" to "Uber Eats", "UBER" to "Uber",
        "LYFT" to "Lyft", "DOORDASH|DD \\*" to "DoorDash", "NETFLIX" to "Netflix", "SPOTIFY" to "Spotify", "APPLE\\.COM|APPLE COM BILL" to "Apple",
        "GOOGLE \\*|GOOGLE\\b" to "Google", "SHELL" to "Shell", "EXXON|MOBIL" to "Exxon Mobil", "SUNOCO" to "Sunoco", "SPEEDWAY" to "Speedway",
        "NATIONAL GRID|NATL GRID" to "National Grid", "NYSEG" to "NYSEG", "DELTA AIR" to "Delta", "UNITED AIR" to "United", "JETBLUE" to "JetBlue",
        "ANTHROPIC|CLAUDE\\.AI" to "Anthropic", "OPENAI|CHATGPT" to "OpenAI", "HULU" to "Hulu", "DISNEY ?PLUS|DISNEY\\+" to "Disney+",
        "XBOX|MICROSOFT" to "Microsoft", "PLAYSTATION|SONY ?INTERACTIVE" to "PlayStation", "STEAM" to "Steam", "WASH" to "Car wash",
    ).map { (re, name) -> Regex("(?i)\\b(?:$re)") to name }

    fun clean(raw: String): String {
        var s = raw.trim()
            .replace(Regex("(?i)^(SQ|TST|SP|PY|PP|PAYPAL|POS|DBT|CHK|ACH)\\s?\\*\\s?"), "")
            .replace(Regex("#\\s?\\d+"), "")
            .replace(Regex("\\b\\d{3,}\\b"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim(' ', '*', '-', '.', '/')
        ALIASES.firstOrNull { it.first.containsMatchIn(s) }?.let { return it.second }
        if (s.length > 28) s = s.take(28).trimEnd()
        // Upper-case bank codes read better in title case.
        return if (s == s.uppercase()) s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else s
    }
}

enum class Category(val word: String) {
    GROCERIES("Groceries"), DINING("Eating out"), FUEL("Fuel"), TRANSPORT("Rides and transit"), CAR("Car"), SHOPPING("Shopping"),
    SUBSCRIPTIONS("Subscriptions"), UTILITIES("Utilities"), HOUSING("Rent and home"), TRAVEL("Travel"), FUN("Fun"), HEALTH("Health"),
    TRANSFERS("Transfers"), OTHER("Other");

    companion object { fun of(word: String?): Category = entries.firstOrNull { it.name.equals(word, true) || it.word.equals(word, true) } ?: OTHER }
}

object Categories {
    private val RULES = listOf(
        Category.GROCERIES to "whole foods|costco|wegmans|tops|aldi|trader joe|grocer|market|instacart",
        Category.DINING to "dining|food|starbucks|tim hortons|dunkin|uber eats|doordash|grubhub|restaurant|cafe|pizza|grill|kitchen|chipotle|panera|mcdonald|taco|burger",
        Category.FUEL to "shell|exxon|mobil|sunoco|speedway|fuel|gas\\b|citgo|kwik fill|bp\\b",
        Category.TRANSPORT to "\\buber\\b|lyft|nfta|metro|parking|toll|e-?zpass|amtrak",
        Category.CAR to "car wash|wash|auto|tire|jiffy|valvoline|geico|progressive|dmv|lease",
        Category.SUBSCRIPTIONS to "streaming|subscription|netflix|spotify|hulu|disney|apple|google|youtube|anthropic|openai|microsoft|playstation|xbox|prime|icloud|audible|steam",
        Category.UTILITIES to "national grid|nyseg|spectrum|verizon|t-mobile|at&t|electric|water|internet",
        Category.HOUSING to "rent|apartment|property|home depot|lowe|ikea",
        Category.TRAVEL to "delta|united|jetblue|american air|southwest|airbnb|hotel|marriott|hilton|expedia",
        Category.SHOPPING to "amazon|target|walmart|best buy|macy|nike|uniqlo|kohl|tj ?maxx|marshalls",
        Category.FUN to "amc|regal|ticketmaster|keybank center|bowling|cinema",
        Category.HEALTH to "pharmacy|cvs|walgreens|rite aid|clinic|dental|hospital",
    ).map { (c, re) -> c to Regex("(?i)($re)") }

    fun of(merchant: String): Category = RULES.firstOrNull { it.second.containsMatchIn(merchant) }?.first ?: Category.OTHER

    /** A category named by Iris or by him: Moneta's own names first, then the keyword rules ("Dining" is Eating out). */
    fun named(word: String): Category = Category.entries.firstOrNull { it.name.equals(word, true) || it.word.equals(word, true) } ?: of(word)
}
