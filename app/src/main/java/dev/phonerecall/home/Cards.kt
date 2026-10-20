package dev.phonerecall.home

import dev.phonerecall.ui.Workers

import androidx.compose.runtime.Immutable
import dev.phonerecall.ui.Tint
import dev.phonerecall.ui.TileForm
import dev.phonerecall.ui.Tone

/**
 * The card catalogue. Each kind has a default weight (its base score) and a tint. A new kind slots in
 * by adding a line here plus a branch in CardEngine.content(); kinds with defaultOn = false appear only
 * after the owner adds them from Add card.
 */
enum class CardKind(val label: String, val title: String, val about: String, val base: Float, val tint: Tint, val defaultOn: Boolean = true) {
    NOW_NEXT("Now", "Now and next", "The time and your next event", 40f, Tint.CALENDAR),
    EVENT("Coming up", "Coming up", "Races, matches, birthdays, travel and key meetings, as they get close", 26f, Tint.CALENDAR),
    OOO("Off", "Out of office", "Days off from your work calendar", 24f, Tint.OOO),
    MATCH("Match day", "Match day", "Your teams live, lineups when they are out, and the last result", 27f, Tint.SPORT),
    BRIEF("Brief", "Brief", "Morning brief, evening recap", 30f, Tint.NEUTRAL),
    COMMUTE("Commute", "Commute", "Office mornings", 30f, Tint.CALENDAR),
    TASKS("Tasks", "Tasks due", "Due today and two-minute jobs, from Tasks", 25f, Tint.TASKS),
    WARDROBE("Wardrobe", "What to wear", "Today's outfit from your closet", 22f, Tint.NEUTRAL),
    WEATHER("Weather", "Weather", "Today, here", 20f, Tint.RAIN),
    WEATHER_ALERT("Weather alert", "Weather alert", "Official NWS warnings for your place, while they last", 30f, Tint.ALERT),
    AGENTS("Agents", "Agents", "${Workers.IRIS} and ${Workers.ATLAS}, latest", 12f, Tint.AGENTS),
    MUSIC("Music", "Music for now", "What you usually play around now", 10f, Tint.MUSIC),
    SHOPPING("Shopping", "Winter shopping list", "What the closet is missing for winter", 9f, Tint.NEUTRAL),
    VPS("Server", "Server", "Your VPS at a glance", 8f, Tint.SERVER),
    SECURITY("Security", "Security", "One line, from ${Workers.IRIS}", 6f, Tint.NEUTRAL),
    /** Odyssey: this week's driving score and the commute insight. */
    ROADS("Roads", "Roads this week", "Driving score and the commute insight, from Odyssey", 14f, Tint.SERVER),
    /** Odyssey: "Looks like Costco. Show Groceries when you arrive?" */
    PLACE_OFFER("A place", "Place proposals", "Stores Odyssey learned, offered once with their list", 29f, Tint.BRAIN),
    /** Hestia: lights left on while he's away, or still on late at night. */
    HEARTH("Home", "Lights left on", "Lights still on while you're away or late at night, from Hestia", 18f, Tint.NEUTRAL),
    /** Evenings: tomorrow's alarm, or the offer to set the one he usually has. */
    ALARM("Alarm", "Tomorrow's alarm", "After 8 PM: your alarm for tomorrow, or an offer to set the one you usually have", 16f, Tint.NEUTRAL),
    /** Odyssey + radar: a lake-effect band crossing his drive, October to April. */
    SNOW_BAND("Snow band", "Snow on your route", "Lake-effect bands crossing your drive, October to April", 32f, Tint.RAIN),
    /** Odyssey + availability: still home on an office morning. */
    WFH("Office day", "Office-day check", "Asks once when you're still home on an office morning", 31f, Tint.CALENDAR),
    /** Odyssey + Iris: a fill-up waiting on the odometer. Never on a widget or the lock screen. */
    FUEL("Fill-up", "Fill-ups", "At the pump: Iris finds the receipt, you add the odometer", 30f, Tint.NEUTRAL),
    /** Odyssey: the lease (details still missing, then the mileage pace). Never on a widget or the lock screen. */
    LEASE("Lease", "Car lease", "Miles against your lease allowance, monthly or when you run ahead", 16f, Tint.NEUTRAL),
    /** Moments: the one helper for right now (dinner from the freezer, a meter, leaving home, a low battery). */
    MOMENT("Kairos", "Moments", "The obvious helper for the moment: oven timers, a parking meter, lights before you leave", 30f, Tint.BRAIN),
    /** Argo: the countdown, the travel day, the trip itself, then the recap. */
    TRIP("Trip", "Trips", "A trip coming up, the travel day and the trip itself, from Argo", 24f, Tint.CALENDAR),
    /** Argo: a booking in Moneta asking "a trip coming up?". Never adds a trip by itself. */
    TRIP_OFFER("A trip?", "Possible trips", "A travel booking that might be a trip, offered once", 16f, Tint.BRAIN),
    /** Praxis: the GTD weekly review, Sunday afternoons until it is done. */
    REVIEW("Review", "Weekly review", "Sunday afternoons: empty the Inbox, stuck projects, Waiting fors and the week ahead", 27f, Tint.TASKS),
    GENERATED("", "", "", 28f, Tint.BRAIN),
}

/** Who made a generated card. Bank and security cards can never be hidden. */
enum class CardSource(val label: String, val neverHide: Boolean = false) {
    ATHENA("Athena"), IRIS("From ${Workers.IRIS}"), ATLAS("From ${Workers.ATLAS}"), CALENDAR("Calendar"),
    BANK("Bank alert", neverHide = true), SECURITY("Security alert", neverHide = true),
    /** A plan window or Athena's cap passed a threshold (Metron), once each. */
    METRON("Metron"),
}

/**
 * The format the brain will create cards in. Body may hold labelled values ("Alarm 5:15 · Leave 6:00"):
 * they render as LABEL value rows, never as a dotted meta line. At most three short actions.
 * `travel` marks a card the Now Bar may carry when it is on top.
 */
@Immutable
data class GeneratedCard(
    val title: String,
    val body: String,
    val actions: List<String>,
    val expiresAt: Long,
    val source: CardSource,
    val id: String = title.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
    val dueToday: Boolean = false,
    val travel: Boolean = false,
    /** A card Athena made after he confirmed it (not a sample), so its actions are live. */
    val real: Boolean = false,
    /** Whether it may wait for the morning brief when the day's attention budget is spent. */
    val urgency: dev.phonerecall.brain.Urgency = when {
        source.neverHide -> dev.phonerecall.brain.Urgency.SAFETY
        dueToday -> dev.phonerecall.brain.Urgency.DEADLINE_TODAY
        travel -> dev.phonerecall.brain.Urgency.TIME_CRITICAL
        else -> dev.phonerecall.brain.Urgency.NICE
    },
)

@Immutable data class Fact(val label: String, val value: String, val status: String? = null, val tone: Tone = Tone.QUIET)

/** A task row on the Tasks card. `id` is the task store id. */
@Immutable data class TaskItem(val id: Long, val title: String, val due: String, val dueToday: Boolean, val sample: Boolean)

/** A local picture: a render is fitted onto its own background colour; a photo is cropped. */
@Immutable data class CardPhoto(val path: String, val bg: Int?, val fit: Boolean)

/** A checkable line (the shopping list). */
@Immutable data class CheckItem(val id: String, val label: String, val checked: Boolean)

/** What an action does when tapped. */
enum class ActionRole { OPEN, ACK, DISMISS, ALLOW_CALENDAR, WEAR, ANOTHER, OPEN_CLOSET, OPEN_TASKS, OPEN_OLYMPIA, OPEN_WEATHER, OPEN_ODYSSEY, MAPS, ODYSSEY_YES, ODYSSEY_NO, OPEN_HESTIA, OPEN_ALARMS, ALARM_SET, ALARM_LINK, ALARM_MUSIC, ALARM_ASK, ALARM_OTHER, ALARM_DONE, ALARM_SPORT, ROAD_DONE, WFH_YES, WFH_NO, FUEL_ODOMETER, FUEL_SKIP, CAR_SETUP, MOMENT_GO, MOMENT_SNAP, MOMENT_LATER, MOMENT_NEVER, OPEN_ARGO, ARGO, OPEN_REVIEW, BIRTHDAY_REACH, BIRTHDAY_DONE }

/** `payload` carries what the action is about, e.g. the top for "Wearing this". */
@Immutable data class CardAction(val label: String, val role: ActionRole, val payload: String? = null)

/**
 * What the Now Bar shows for a time-sensitive top card. `countdownTo` drives the chronometer; when it is
 * set, the status-bar chip shows the live countdown instead of `chip`.
 */
@Immutable
/** [key]: the item it is about (an event's instance key), so dismissing it on any card clears it everywhere. */
data class LiveInfo(val chip: String, val title: String, val text: String, val countdownTo: Long? = null, val sample: Boolean = false, val key: String? = null)

/** A small number tile inside a card, after BetweenWeather's metric tiles: "2" over "due". */
@Immutable data class Stat(val value: String, val label: String, val form: TileForm)

/** The calendar event behind an event card, for "Hide events like this". */
@Immutable data class EventRef(val calendarId: Long, val title: String)

/** Everything a card shows. One renderer draws every kind from this, sized by importance. */
@Immutable
data class CardContent(
    val title: String,
    val lead: String? = null,             // a big value: the time, a temperature
    val body: String? = null,
    val facts: List<Fact> = emptyList(),
    val tasks: List<TaskItem> = emptyList(),
    val checks: List<CheckItem> = emptyList(),
    /** Local pictures (closet renders or photos). Loaded off the main thread, downsampled. */
    val photos: List<CardPhoto> = emptyList(),
    val status: String? = null,
    val tone: Tone = Tone.QUIET,
    val actions: List<CardAction> = emptyList(),
    val sample: Boolean = false,
    val placeholder: Boolean = false,     // "arrives in phase N": always small, always low
    val live: LiveInfo? = null,
    val stat: Stat? = null,
    val event: EventRef? = null,
)

enum class CardSize { HERO, LARGE, NORMAL, COMPACT }

/** A card ready to draw: content plus the engine's verdict. */
@Immutable
data class ScoredCard(
    val id: String,
    val kind: CardKind,
    val learnKey: String,
    val kicker: String,
    val content: CardContent,
    val score: Float,
    val size: CardSize,
    val tint: Tint,
    val pinned: Boolean,
    val moved: Boolean,
    val neverHide: Boolean,
    val neverHideReason: String?,
    /** This card's content identity (kind plus the item it is about); swiping dismisses only this. */
    val instanceKey: String = id,
    /** When the item stops being relevant, epoch ms; null when unknown (a dismissal then lasts 24 h). */
    val relevantUntil: Long? = null,
)

/** A row in Edit Home. */
@Immutable data class EditRow(val id: String, val title: String, val about: String, val pinned: Boolean, val moved: Boolean, val neverHide: Boolean)

/** A row in the Add card picker. */
@Immutable data class CatalogueRow(val id: String, val title: String, val about: String, val onHome: Boolean)

@Immutable data class HiddenRow(val id: String, val title: String)

@Immutable
data class Board(
    val cards: List<ScoredCard> = emptyList(),
    val onHome: List<EditRow> = emptyList(),
    val hidden: List<HiddenRow> = emptyList(),
    val catalogue: List<CatalogueRow> = emptyList(),
    val title: String = "",
    val calendarAllowed: Boolean = false,
    /** Epoch ms of the next moment the top card may change: an event edge, an expiry, the hour. */
    val nextChange: Long = 0L,
)
