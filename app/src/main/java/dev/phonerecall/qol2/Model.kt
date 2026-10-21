package dev.phonerecall.qol2

import dev.phonerecall.home.CardContent
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Small touches (second batch): one helper card each, from signals Zeus already has. Each has its own
 * Themis switch (Small touches). The rules are pure (TouchRules); Touches gathers the signals and runs the taps.
 */
enum class Touch(val label: String, val about: String) {
    TRIP_PHOTOS("Send trip photos", "After a trip or a long event: pick the photos and share them with family"),
    FIX_CATEGORY("Fix a category", "An uncategorized charge, with three likely categories"),
    RESTOCK("Restock", "Store-list items you buy on a steady pace, due again"),
    CLEAR_LIST("Clear the list", "Leaving a store after paying there: clear its list, with Undo"),
    RECEIPT_DRIVE("Receipts to Drive", "After a grocery charge: save the receipt photo to your Drive folder"),
    TIP("Tip", "After a restaurant charge: 15, 18 and 20 percent"),
    CANCEL_PROOF("Proof you cancelled", "Keeps the cancellation notice; flags a charge that comes after it"),
    GARAGE("Garage ticket", "Parked in a garage: since when, and the payment app"),
    CAR_RENEWAL("Registration and inspection", "30 days before, with the NY DMV page"),
    CAR_WASH("Car wash day", "A dry, mild day with a free slot, at most weekly"),
    LEAVE_NOTE("When I leave", "A note that fires when you leave a place"),
    LAYOVER("Layover", "Landed at a connection: the gate and when it boards"),
    TICKET("Ticket at the venue", "The ticket link or Wallet at the event's start"),
    HOT_DAY("Hot day", "Over 80°F when you leave: water and sunscreen"),
    WALK_WINDOW("Walk before the rain", "A free gap now, with rain coming later"),
    MATCH_MODE("Match mode", "Five minutes before a followed match: Zeus's alerts go priority-only until full time"),
    QUICK_TIMER("Quick timers", "5, 10, 30 minutes and your saved ones, counting down in the Now Bar"),
    BEDTIME_MODE("Bedtime mode", "An early alarm and it's late: Do not disturb, with Undo"),
    PLUG_IN("Plug in at home", "Home under 30%: plug in, or turn on the charger plug"),
    WORK_FOCUS("Office focus", "At the office: Zeus holds non-urgent alerts until you leave; lunch nudge"),
    RESUME_MUSIC("Resume on the Fold", "Opened the Fold after a drive with music: resume it"),
    BORROWED("Borrowed items", "Who has your things, and when they're due back"),
    READ_LATER("Read later", "Links you shared to Zeus, a few at a time when you usually read"),
    DAY_FOLDER("Day folder", "Docs and links you pinned for a date, that morning"),
    PACKET("Appointment packet", "An appointment and the files you attached to it"),
    HEALTH_SYNC("Health sync check", "Samsung Health hasn't sent steps for hours"),
    LIGHT_DAY("Light day", "After a short night: a gentle note, nothing changes"),
    AUDIO_DROP("Audio reconnect", "Headphones or the car dropped while music played"),
}

/** One card from a touch. Each option on it is "<TOUCH>|<key>|<option>". */
data class TouchCard(
    val touch: Touch,
    val key: String,
    val content: CardContent,
    val fit: Float,
    /** What an option does when tapped (see Touches.run). */
    val does: Map<String, TouchDo> = emptyMap(),
)

/** What a tap does. Everything here is reversible or opens another app; nothing messages or pays. */
sealed interface TouchDo {
    /** Opens a web address or "app:<package>" (first installed wins when several are given, comma-separated). */
    data class Open(val target: String) : TouchDo
    /** Opens a Zeus screen: "moneta", "olympia", "touches". */
    data class Go(val where: String) : TouchDo
    /** Marks the card handled. */
    data object Ack : TouchDo
    /** Snoozes this touch for today. */
    data object Later : TouchDo
    /** Turns the touch off (Themis turns it back on). */
    data object Never : TouchDo
    data class Categorize(val ref: String, val category: String) : TouchDo
    data class AddToList(val list: String, val item: String) : TouchDo
    data class ClearList(val list: String) : TouchDo
    data class Copy(val text: String) : TouchDo
    data class Timer(val minutes: Int, val label: String) : TouchDo
    data class StopTimer(val id: String) : TouchDo
    data class Extend(val id: String, val minutes: Int) : TouchDo
    data object Bedtime : TouchDo
    data object NightLight : TouchDo
    data object FocusOff : TouchDo
    data class Plug(val deviceId: String, val name: String) : TouchDo
    data object Resume : TouchDo
    data class Returned(val id: String) : TouchDo
    data class ReadLink(val id: String) : TouchDo
    data class OpenPin(val uri: String) : TouchDo
    /** Opens the photo picker for [from, to), then the share sheet. */
    data class PickPhotos(val from: Long, val to: Long) : TouchDo
    /** Pick the receipt photo and hand it to Drive's upload sheet as [name] (Drive keeps the folder he picked last). */
    data class SaveReceipt(val name: String) : TouchDo
    data class DropNote(val id: String) : TouchDo
}

// ---- what he keeps in Small touches (sealed on the phone) ----

data class Borrowed(val id: String = "", val who: String = "", val what: String = "", val due: String? = null, val since: Long = 0, val returned: Boolean = false)

data class SavedLink(val id: String = "", val url: String = "", val title: String = "", val savedAt: Long = 0, val openedAt: Long? = null)

/** A doc or link pinned for a day (`date` ISO) or an event (`event` = CalItem.key). `uri` is a web address or a content uri. */
data class Pin(val id: String = "", val label: String = "", val uri: String = "", val date: String? = null, val event: String? = null)

/** "Remind me when I leave": the stay it was made at (its start), or a known place by name. */
data class LeaveNote(val id: String = "", val text: String = "", val stayStart: Long = 0, val place: String = "", val at: Long = 0)

/** A cancellation notice he got: the service, when, and its first line, kept as proof. */
data class Proof(val service: String = "", val at: Long = 0, val line: String = "", val app: String = "")

data class SavedTimer(val label: String = "", val minutes: Int = 0)

data class RunningTimer(val id: String = "", val label: String = "", val endsAt: Long = 0)

/** Zeus's own alerts priority-only until [until] (a match, the office). Never the system's Do not disturb unless he opted in. */
data class Focus(val why: String = "", val until: Long = 0, val key: String = "")

data class TouchPrefs(
    val off: Set<Touch> = emptySet(),
    val snoozed: Map<Touch, Long> = emptyMap(),
    /** The key of the card he last took or waved off, so the same one never comes back. */
    val handled: Map<Touch, String> = emptyMap(),
    /** When each touch last showed (epoch ms), for the at-most-weekly ones. */
    val shownAt: Map<Touch, Long> = emptyMap(),
    val borrowed: List<Borrowed> = emptyList(),
    val links: List<SavedLink> = emptyList(),
    /** Hours of the week (0..167) he opened a read-later link, the last 40. */
    val readHours: List<Int> = emptyList(),
    val pins: List<Pin> = emptyList(),
    val leave: List<LeaveNote> = emptyList(),
    val proofs: List<Proof> = emptyList(),
    val timers: List<SavedTimer> = listOf(SavedTimer("Laundry", 45)),
    val running: List<RunningTimer> = emptyList(),
    val focus: Focus? = null,
    /** He turned the office focus off for this day (epoch day). */
    val focusOffDay: Long = 0,
    /** Match mode may also turn on the phone's Do not disturb (his opt-in, off by default). */
    val matchDnd: Boolean = false,
    /** Registration and inspection due dates (ISO), typed in Small touches. */
    val registration: String? = null,
    val inspection: String? = null,
    /** The Hestia plug his phone charger is on. */
    val charger: String? = null,
    /** The last drive's end, and whether music was playing then. */
    val driveEndedAt: Long = 0,
    val musicAtDriveEnd: Boolean = false,
) {
    fun later(t: Touch, key: String, day: Long) = copy(snoozed = snoozed + (t to day), handled = handled + (t to key))
    fun took(t: Touch, key: String) = copy(handled = handled + (t to key))
}

/** A charge as the touches see it. Amounts stay on the phone. */
data class Charge(val ref: String, val merchant: String, val category: String, val at: Long, val cents: Long, val place: String? = null)

/** A finished visit to a store with a list. */
data class StoreVisit(val name: String, val list: String, val start: Long, val end: Long, val open: Int)

data class StoreStaple(val list: String, val item: String, val everyDays: Int, val last: Long)

data class FlightLeg(val from: String, val to: String, val departAt: Long, val arriveAt: Long, val number: String, val gate: String)

data class Ended(val key: String, val title: String, val from: Long, val to: Long)

data class Followed(val id: String, val title: String, val start: Long, val live: Boolean, val done: Boolean)

data class DayEvent(val key: String, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val links: List<String> = emptyList())

data class Plug(val id: String, val name: String, val on: Boolean)

/** The forecast hour (Aeolia's, without Compose). */
data class Hour(val at: Long, val tempC: Double?, val code: Int, val precipMm: Double)

/** Everything the rules read, already derived on the phone. */
data class TouchContext(
    val now: ZonedDateTime,
    /** On a call, driving or a game in front: only the timers and the notes that must fire. */
    val quiet: Boolean = false,
    val driving: Boolean = false,
    val atHome: Boolean? = null,
    val atOffice: Boolean? = null,
    val officeDay: Boolean = false,
    /** The open stay: since, its name, and whether it looks like a parking garage. */
    val staySince: Long? = null,
    val stayName: String? = null,
    val garage: Boolean = false,
    /** His usual departure today, when still ahead. */
    val departure: ZonedDateTime? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val hours: List<Hour> = emptyList(),
    val events: List<DayEvent> = emptyList(),
    val charges: List<Charge> = emptyList(),
    val visits: List<StoreVisit> = emptyList(),
    val staples: List<StoreStaple> = emptyList(),
    val legs: List<FlightLeg> = emptyList(),
    val ended: List<Ended> = emptyList(),
    val matches: List<Followed> = emptyList(),
    val nextAlarm: ZonedDateTime? = null,
    val plugs: List<Plug> = emptyList(),
    val musicNow: Boolean = false,
    val playlist: String = "owner_1",
    val innerScreen: Boolean = false,
    val shortNight: Boolean = false,
    val stepsSyncedAt: Long? = null,
    /** Minute of day he usually goes out for lunch on office days, when he does. */
    val lunchAt: Int? = null,
    val carWash: String? = null,
    /** Renewal dates from Moneta's subscriptions (Iris's), by "registration" / "inspection". */
    val renewals: Map<String, LocalDate> = emptyMap(),
    /** The last audio drop while music played, epoch ms. */
    val audioDropAt: Long? = null,
)
