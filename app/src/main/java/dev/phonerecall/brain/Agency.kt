package dev.phonerecall.brain

import android.app.PendingIntent
import android.app.RemoteInput
import java.util.concurrent.ConcurrentHashMap

/*
 * One-tap agency: the buttons a notification's own app offers ("Call back", "Reply", "Mark as read"), and the
 * ones the rules derive where the app gives none ("Directions", "Join", "Track", "Copy code"). They live in
 * memory only, keyed by the notification: never stored, never sealed, never sent. Labels are the only part
 * that leaves this file (to the card and to what Athena reads). Nothing here fires on its own; his tap does.
 */

/** A derived action: what the phone opens when he taps it. Sending and paying still happen in the app he lands in. */
sealed class Smart {
    /** The dial screen (or a direct call, when he turned that on in Themis and Android allows it). */
    data class Dial(val number: String) : Smart()
    /** The message composer, addressed; he presses send. */
    data class Text(val number: String) : Smart()
    data class Directions(val place: String) : Smart()
    /** A link in the browser, or in [pkg] when that app is installed (a meeting, a check-in, a tracking page). */
    data class Open(val url: String, val pkg: String? = null) : Smart()
    /** His bank's app. */
    data class Launch(val pkg: String) : Smart()
    /** A one-time code to the clipboard, cleared after a minute. */
    data class Copy(val code: String) : Smart()
    /** The photo picker, then the chosen photos to [pkg] (the chat's app); he picks the chat and sends. */
    data class SharePhotos(val pkg: String) : Smart()
    /** His location as a maps link, shared to [pkg]; he picks the chat and sends. */
    data class ShareLocation(val pkg: String) : Smart()
    /** What is playing now, shared to [pkg]. */
    data class ShareSong(val pkg: String) : Smart()
    /** No reply on the notification: the text to the clipboard and [pkg] opened; he pastes it in the chat. */
    data class CopyOpen(val text: String, val pkg: String) : Smart()
}

/** One button. [intent] and [input] are the app's own, as it posted them; they are only ever fired from his tap. */
class LiveAction(
    val label: String,
    val intent: PendingIntent? = null,
    val input: RemoteInput? = null,
    val smart: Smart? = null,
    /** The app's own suggested replies (RemoteInput choices, the system's smart replies). */
    val choices: List<String> = emptyList(),
    /** What the message asked for, when this button answers it; with [who], what his taps teach. */
    val ask: Ask? = null,
    val who: String? = null,
) {
    fun withChoices(c: List<String>) = LiveAction(label, intent, input, smart, c, ask, who)

    /** Sends through the app's RemoteInput: one-tap chips, or Edit for the field with Send. */
    val reply: Boolean get() = input != null

    /** Takes a text: the app's reply, or the composer for a missed call's Text. */
    val says: Boolean get() = reply || smart is Smart.Text
}

/** The reply chips on a card: Athena's draft first (its full text on the chip), then two short ones. One tap sends that exact text. */
object Replies {
    val GENERIC = listOf("On my way", "Call you in 10")
    val MISSED_CALL = listOf("Can't talk, call you back soon", "Call you in 10")
    const val SHORT = 40

    fun chips(draft: String?, choices: List<String>, missedCall: Boolean): List<String> {
        val d = draft?.trim()?.takeIf { it.isNotEmpty() }
        val short = choices.map { it.trim() }.filter { it.isNotEmpty() && it.length <= SHORT }.ifEmpty { if (missedCall) MISSED_CALL else GENERIC }
        return (listOfNotNull(d) + short.filter { !it.equals(d, ignoreCase = true) }.distinctBy { it.lowercase() }.take(2))
    }

    /** What the draft's chip reads: the whole text, so he sees exactly what goes. */
    fun label(text: String, draft: Boolean): String = if (draft) "Send \u201c$text\u201d" else text
}

/** An app action as the listener read it, without the live parts, so picking is a pure rule. */
data class ActionSpec(val label: String, val live: Boolean, val freeForm: Boolean = false, val contextual: Boolean = false)

object AppActions {
    const val MAX = 3

    /** Which of an app's actions become buttons: live ones with a label, not the system's suggestion chips, at most three. */
    fun pick(specs: List<ActionSpec>): List<Int> {
        val seen = HashSet<String>()
        return specs.indices.filter { i ->
            val s = specs[i]
            s.live && !s.contextual && s.label.isNotBlank() && seen.add(s.label.trim().lowercase())
        }.take(MAX)
    }
}

/** The rules for actions an app doesn't offer. Pure: the listener hands in what it read, the phone decides what's installed. */
object SmartActions {
    private val PHONE = Regex("""(?<![\d+])(\+?\d[\d ().-]{6,16}\d)(?!\d)""")
    private val ADDRESS = Regex(
        """\b\d{1,5}\s+(?:[A-Z0-9][\w.'-]*\s+){1,4}(?:St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Dr|Drive|Ln|Lane|Way|Ct|Court|Pl|Place|Pkwy|Parkway|Hwy|Highway|Ter|Terrace|Cir|Circle|Sq|Square)\b\.?(?:,?\s+(?:Apt|Suite|Ste|Unit)\.?\s*#?\w+)?(?:,\s*[A-Z][a-zA-Z]+(?:\s[A-Z][a-zA-Z]+)?)?(?:,?\s+[A-Z]{2})?(?:\s+\d{5})?""",
    )
    private val MEETING = Regex(
        """(?i)\b(?:https?://)?(?:[\w-]+\.)?(?:zoom\.us/(?:j|my|w)/|meet\.google\.com/|teams\.microsoft\.com/l/meetup-join/|teams\.live\.com/meet/|[\w-]+\.webex\.com/(?:meet|join|[\w-]+/j\.php))[^\s<>"')]+""",
    )
    private val FLIGHT = Regex("""\b(DL|UA|AA|B6|WN|AS|NK|F9|AI|6E|UK|EK|QR|LH|BA|AC|EY|SQ)\s?(\d{1,4})\b""")
    private val FLIGHT_WORDS = Regex("""(?i)\b(flight|check[- ]?in|boarding|gate|departs?|departure|itinerary|trip)\b""")
    private val BOARDING = Regex("""(?i)\bboarding pass""")
    private val UPS = Regex("""\b1Z[0-9A-Z]{16}\b""")
    private val USPS = Regex("""\b9[2-5]\d{18,20}\b""")
    private val AMAZON = Regex("""\bTBA\d{12}\b""")
    private val FEDEX = Regex("""\b(\d{12}|\d{15})\b""")
    private val FEDEX_WORD = Regex("""(?i)\bfedex\b""")
    private val BILL = Regex("""(?i)\b(bill|statement|payment (is )?due|amount due|minimum payment|autopay|due date|pay your)\b""")
    private val CODE = Regex("""(?<![\d])(\d{4,8}|\d{3}[- ]\d{3})(?![\d])""")
    private val G_CODE = Regex("""\bG-(\d{6})\b""")

    /** Airline code to its app and its web check-in. */
    private val AIRLINES = mapOf(
        "DL" to ("com.delta.mobile.android" to "https://www.delta.com/mytrips/"),
        "UA" to ("com.united.mobile.android" to "https://www.united.com/en/us/checkin"),
        "AA" to ("com.aa.android" to "https://www.aa.com/check-in"),
        "B6" to ("com.jetblue.JetBlueAndroid" to "https://www.jetblue.com/check-in"),
        "WN" to ("com.southwestairlines.mobile" to "https://www.southwest.com/air/check-in/"),
        "AS" to ("com.alaskaairlines.android" to "https://www.alaskaair.com/checkin"),
        "NK" to ("com.spirit.customerapp" to "https://www.spirit.com/check-in"),
        "F9" to ("com.flyfrontier.android" to "https://www.flyfrontier.com/travel/my-trips/check-in/"),
        "AI" to ("com.airindia.mobile" to "https://www.airindia.com/in/en/manage/web-checkin.html"),
        "6E" to ("in.goindigo.android" to "https://www.goindigo.in/web-check-in.html"),
        "EK" to ("com.emirates.ek.android" to "https://www.emirates.com/us/english/manage-booking/online-check-in/"),
        "QR" to ("com.m.qr" to "https://www.qatarairways.com/en/manage-booking/olci.html"),
        "LH" to ("com.lufthansa.android.lufthansa" to "https://www.lufthansa.com/us/en/online-check-in"),
        "BA" to ("com.ba.mobile" to "https://www.britishairways.com/travel/olcilandingpageauthreq/public/en_us"),
        "AC" to ("com.aircanada" to "https://www.aircanada.com/us/en/aco/home/app.html#/checkin"),
        "EY" to ("com.etihad.app" to "https://www.etihad.com/en-us/manage/check-in"),
        "SQ" to ("com.sia.mobile" to "https://www.singaporeair.com/en_UK/us/travel-info/check-in/"),
    )

    /** A bank named in a bill, to its app (the phone shows the button only when the app is installed). */
    private val BANKS = linkedMapOf(
        "chase" to "com.chase.sig.android", "capital one" to "com.konylabs.capitalone", "american express" to "com.americanexpress.android.acctsvcs.us",
        "amex" to "com.americanexpress.android.acctsvcs.us", "discover" to "com.discoverfinancial.mobile", "citi" to "com.citi.citimobile",
        "wells fargo" to "com.wf.wellsfargomobile", "bank of america" to "com.infonow.bofa", "keybank" to "com.key.android",
        "m&t" to "com.mtb.mbanking.sc.retail.prod", "hdfc" to "com.snapwork.hdfc", "icici" to "com.csam.icici.bank.imobile", "sbi" to "com.sbi.lotusintouch",
    )

    private val BANK_NAMES = mapOf(
        "com.chase.sig.android" to "Chase", "com.konylabs.capitalone" to "Capital One", "com.americanexpress.android.acctsvcs.us" to "Amex",
        "com.discoverfinancial.mobile" to "Discover", "com.citi.citimobile" to "Citi", "com.wf.wellsfargomobile" to "Wells Fargo",
        "com.infonow.bofa" to "BofA", "com.key.android" to "KeyBank", "com.mtb.mbanking.sc.retail.prod" to "M&T", "com.snapwork.hdfc" to "HDFC",
        "com.csam.icici.bank.imobile" to "iMobile", "com.sbi.lotusintouch" to "YONO",
    )

    /** The first phone number in the words, digits only (a leading + kept). */
    fun number(text: String): String? = PHONE.findAll(text).map { it.value }.firstOrNull { it.count(Char::isDigit) in 7..15 }
        ?.let { v -> (if (v.trim().startsWith("+")) "+" else "") + v.filter(Char::isDigit) }

    /** The code in a one-time-code message, or null. Only called for what Exclusions already called a code. */
    fun code(text: String): String? = G_CODE.find(text)?.groupValues?.get(1) ?: CODE.find(text)?.value?.filter(Char::isDigit)

    fun track(text: String): String? {
        UPS.find(text)?.let { return "https://www.ups.com/track?tracknum=${it.value}" }
        AMAZON.find(text)?.let { return "https://track.amazon.com/tracking/${it.value}" }
        USPS.find(text)?.let { return "https://tools.usps.com/go/TrackConfirmAction?tLabels=${it.value}" }
        if (FEDEX_WORD.containsMatchIn(text)) FEDEX.find(text)?.let { return "https://www.fedex.com/fedextrack/?trknbr=${it.value}" }
        return null
    }

    /**
     * The derived buttons for one kept notification, in order, skipping any the app already offers ([appLabels]).
     * [number] is the caller's number when the listener found one (the notification's person, or Contacts).
     * [installed] says whether a package is on the phone.
     */
    fun of(n: Incoming, kind: AppKind, who: Who?, number: String?, appLabels: List<String>, installed: (String) -> Boolean = { true }): List<Pair<String, Smart>> {
        val words = n.title + "\n" + n.text
        val out = ArrayList<Pair<String, Smart>>()
        if (kind == AppKind.DIALER && who?.missedCall == true) {
            val num = number ?: number(words)
            if (num != null) { out += "Call back" to Smart.Dial(num); out += "Text" to Smart.Text(num) }
        }
        MEETING.find(words)?.let { m -> out += "Join" to Smart.Open(m.value.let { if (it.startsWith("http", true)) it else "https://$it" }) }
        FLIGHT.find(words)?.takeIf { FLIGHT_WORDS.containsMatchIn(words) }?.let { f ->
            AIRLINES[f.groupValues[1]]?.let { (pkg, web) ->
                out += if (!BOARDING.containsMatchIn(words)) "Check in" to Smart.Open(web, pkg)
                else "Boarding pass" to if (installed(pkg)) Smart.Launch(pkg) else Smart.Open(web)
            }
        }
        track(words)?.let { out += "Track" to Smart.Open(it) }
        if (n.pkg == Splitwise.PKG) {
            out += "Open Splitwise" to Smart.Launch(n.pkg)
            if (Splitwise.read(words).kind.let { it == Splitwise.Kind.OWE || it == Splitwise.Kind.OWED }) out += "Settle up" to Smart.Launch(n.pkg)
        }
        if (BILL.containsMatchIn(words)) {
            val pkg = if (kind == AppKind.BANK) n.pkg else BANKS.entries.firstOrNull { Regex("""(?i)\b${Regex.escape(it.key)}\b""").containsMatchIn(words) }?.value
            if (pkg != null && installed(pkg)) {
                val name = if (kind == AppKind.BANK) n.app else BANK_NAMES[pkg] ?: n.app
                out += "Open $name" to Smart.Launch(pkg)
            }
        }
        ADDRESS.find(words)?.let { out += "Directions" to Smart.Directions(it.value.trim().trimEnd(',', '.')) }
        return out.filter { (label, _) -> !offered(label, appLabels) }.distinctBy { it.first }
    }

    private val SAME = mapOf("call back" to listOf("call", "call back", "return call"), "text" to listOf("message", "reply", "text", "sms"), "track" to listOf("track"))

    /** The app already has this button ("Message" covers "Text"). */
    fun offered(label: String, appLabels: List<String>): Boolean {
        val l = label.lowercase()
        val words = SAME[l] ?: listOf(l)
        return appLabels.any { a -> val al = a.trim().lowercase(); words.any { al == it || al.startsWith("$it ") } }
    }
}

/**
 * The live actions, in memory, by notification key. Cards point at a notification; when the app removes it,
 * its buttons go with it. Athena sees a card's labels and an opaque ref ("c3"), never a key or an intent.
 */
object LiveActions {
    /** A one-time code's card: exists only while the notification does, at most ten minutes. */
    data class CodeCard(val note: String, val app: String, val at: Long, val until: Long)

    private class Entry(val app: String, val actions: List<LiveAction>, val at: Long)

    private val byNote = ConcurrentHashMap<String, Entry>()
    private val byCard = ConcurrentHashMap<String, String>()
    private val refs = ConcurrentHashMap<String, String>()
    private val refOf = ConcurrentHashMap<String, String>()
    private val borrowed = ConcurrentHashMap<Long, String>()
    private val drafts = ConcurrentHashMap<String, String>()
    private val codes = ConcurrentHashMap<String, CodeCard>()
    private val callers = ConcurrentHashMap<String, String>()
    @Volatile private var nextRef = 1

    fun put(note: String, app: String, actions: List<LiveAction>, at: Long) {
        if (actions.isEmpty()) byNote.remove(note) else byNote[note] = Entry(app, actions.take(AppActions.MAX), at)
    }

    /** A code notification: its one button, and its card. */
    fun code(note: String, app: String, code: String, at: Long) {
        byNote[note] = Entry(app, listOf(LiveAction("Copy code", smart = Smart.Copy(code))), at)
        codes[note] = CodeCard(note, app, at, at + 10 * 60_000L)
    }

    /** What a removal touched: the sense cards it stood behind, and whether a card on screen lost buttons. */
    data class Gone(val cards: List<String>, val redraw: Boolean)

    /** The notification is gone (tapped, swiped, cleared by its app): its buttons go, and the cards it stood behind are named. */
    fun removed(note: String): Gone {
        val had = byNote.remove(note) != null
        val code = codes.remove(note) != null
        callers.remove(note)
        val cards = byCard.entries.filter { it.value == note }.map { it.key }
        cards.forEach { byCard.remove(it, note) }
        return Gone(cards, code || (had && cards.isNotEmpty()))
    }

    /** A missed call's number, kept in memory so the card can tell when he called back. */
    fun caller(note: String, number: String) { callers[note] = number }

    fun callerOf(card: String): String? = byCard[card]?.let { callers[it] }

    /** Athena's cards wearing [card]'s buttons: they finish with it. */
    fun borrowers(card: String): List<Long> = borrowed.entries.filter { it.value == card }.map { it.key }

    /** The sense cards whose notification is [note] (a reply shown in it finishes them). */
    fun cardsOf(note: String): List<String> = byCard.entries.filter { it.value == note }.map { it.key }

    /** A sense card now shows this notification's buttons. */
    fun link(card: String, note: String) { byCard[card] = note }

    fun noteOf(card: String): String? = byCard[card]?.takeIf { byNote.containsKey(it) }

    fun forNote(note: String): List<LiveAction> = byNote[note]?.actions.orEmpty()

    fun forCard(card: String): List<LiveAction> = noteOf(card)?.let(::forNote).orEmpty()

    fun app(note: String): String? = byNote[note]?.app

    fun get(note: String, index: Int): LiveAction? = forNote(note).getOrNull(index)

    /** What Athena may call a card: stable while the app runs, meaningless anywhere else. */
    fun ref(card: String): String = refOf.getOrPut(card) { synchronized(this) { "c${nextRef++}" }.also { refs[it] = card } }

    /** Athena's own card wears the buttons of the card [ref] names, with her draft for a reply. False for an unknown ref. */
    fun borrow(athenaCard: Long, ref: String, draft: String?): Boolean {
        val card = refs[ref.trim()] ?: return false
        borrowed[athenaCard] = card
        draft?.trim()?.takeIf { it.isNotEmpty() }?.let { drafts[card] = it.take(500) }
        return true
    }

    /** The sense card an Athena card borrowed from, or null. */
    fun borrowedFrom(athenaCard: Long): String? = borrowed[athenaCard]

    fun draft(card: String): String? = drafts[card]

    /** The phone's own wording for a card (a birthday wish); Athena's draft replaces it. */
    fun setDraft(card: String, text: String) { drafts[card] = text }

    fun draftForNote(note: String): String? = byCard.entries.firstOrNull { it.value == note }?.key?.let { drafts[it] }

    fun codeCards(now: Long): List<CodeCard> {
        codes.values.removeAll { it.until <= now }
        return codes.values.sortedByDescending { it.at }
    }

    /** For tests: forget everything. */
    fun clear() { callers.clear(); byNote.clear(); byCard.clear(); refs.clear(); refOf.clear(); borrowed.clear(); drafts.clear(); codes.clear(); nextRef = 1 }
}

/** When a card finishes by itself (the same Dismissals as a swipe, so it can come back). Pure rules. */
object Finish {
    /** Removal reasons that mean he dealt with it: opened, swiped or cleared it, or the app cleared it (read, replied, called back). */
    fun byRemoval(reason: Int): Boolean = reason in setOf(
        android.service.notification.NotificationListenerService.REASON_CLICK, android.service.notification.NotificationListenerService.REASON_CANCEL,
        android.service.notification.NotificationListenerService.REASON_CANCEL_ALL, android.service.notification.NotificationListenerService.REASON_APP_CANCEL,
        android.service.notification.NotificationListenerService.REASON_APP_CANCEL_ALL,
    )

    /** A tap finished the card unless it only opened the reply field (Send finishes it) or the action was already gone. */
    fun byTap(o: Agency.Outcome): Boolean = o == Agency.Outcome.Done

    /** One call log row, as the phone read it. */
    data class Call(val number: String, val type: Int, val at: Long, val seconds: Long)

    const val OUTGOING = 2
    const val INCOMING = 1

    private fun same(a: String, b: String): Boolean {
        val x = a.filter(Char::isDigit).takeLast(10)
        return x.length >= 7 && x == b.filter(Char::isDigit).takeLast(10)
    }

    /** He called [number] back after [since] (any app that logs calls), or picked up when it rang again. */
    fun calledBack(number: String, since: Long, log: List<Call>): Boolean = log.any {
        it.at > since && same(it.number, number) && (it.type == OUTGOING || (it.type == INCOMING && it.seconds > 0))
    }
}

/** The ids a sense card has on Home: [id] is its GeneratedCard id, [instance] what Dismissals keys (CardEngine.idOf). */
object SenseCards {
    fun id(c: SenseCard) = "sense-${c.key}-${c.at}"
    fun instance(c: SenseCard) = "gen:" + id(c)
    fun athenaInstance(id: Long) = "gen:athena-$id"
}
