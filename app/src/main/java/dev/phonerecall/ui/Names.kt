package dev.phonerecall.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The app is Zeus; Athena is its mind (the assistant's voice). Each place has a mythic name from Greek
 * or Roman myth and a one-line muted subtitle. The plain word is kept for accessibility, and for
 * the "Plain names" setting, which shows plain names and hides the subtitles.
 */
enum class Place(val plain: String, val myth: String, val motto: String) {
    HOME("Home", "Olympus", "Where the day gathers"),
    ORACLE("Ask Athena", "Delphi", "Ask the oracle"),
    CALENDAR("Calendar", "Chronos", "The wheel of time turns"),
    TASKS("Tasks", "Praxis", "Things done, one step at a time"),
    CLOSET("Closet", "Venus", "Dressed for the day"),
    AGENTS("Agents", "Pantheon", "Your gods at work"),
    VPS("VPS", "Lemnos", "The forge beneath"),
    WEATHER("Weather", "Aeolia", "Island of the winds"),
    DEVICES("Devices", "Hestia", "Keeper of the hearth"),
    SETTINGS("Settings", "Themis", "The order of things"),
    SPORTS("Sports", "Olympia", "Where the games are won"),

    // Later, where a placeholder exists.
    ALERTS("Alerts", "Cerberus", "Three heads, never asleep"),
    RAMBLER("Rambler", "Parnassus", "Speak, and it is kept"),
    MEMORY("Memory", "Alexandria", "Every page, kept"),
    EXPIRED("Expired", "Lethe", "The river of forgetting"),
    PROPOSALS("Proposals", "Fates", "Nothing moves without you"),
    IMPROVE("Improve the app", "Prometheus", "Fire, carefully stolen"),
    PLACES("Places", "Odyssey", "Every road, remembered"),
    HEALTH("Health", "Iaso", "She who heals"),
    MONEY("Money", "Moneta", "Where the coins are minted"),
    VAULT("Vault", "Calypso", "She keeps what's hidden"),
    PEOPLE("People", "Harmonia", "Who matters, and how much"),
    BRIEF("Morning brief", "Eos", "The day, before it starts"),
    BEDTIME("Bedtime", "Hypnos", "Sleep, on time"),
    RHYTHMS("Rhythms", "Horae", "The hours, in their order"),
    SEED("Getting to know you", "Theogony", "How it all began"),
    BACKUP("Backup", "Mnemosyne", "Mother of memory"),
    MOMENTS("Moments", "Kairos", "The right moment"),
    TRIPS("Trips", "Argo", "The ship that carries you"),
    DREAMS("Memory care", "Oneiroi", "Where the night sorts the day"),
    USAGE("Usage", "Metron", "The measure of all things"),
    LOGS("Logs", "Clio", "What happened, in order"),
    AGORA("Agent room", "Agora", "Where the agents meet"),
    HOROLOGION("Schedules", "Horologion", "Every job and its hour"),
    SETUP("Setup", "Telete", "Everything Zeus needs, and a test for each"),
}

const val APP_NAME = "Zeus"

/** True when Themis's "Plain names" is on. */
val LocalPlainNames = staticCompositionLocalOf { false }

@Composable fun Place.title(): String = if (LocalPlainNames.current) plain else myth

/** The muted subtitle, or null with plain names. The hidden epithets egg swaps in an alternate set. */
@Composable fun Place.subtitle(): String? = if (LocalPlainNames.current) null else if (Eggs.epithets) Eggs.EPITHETS[this] ?: motto else motto

/**
 * The external workers' display names (Pantheon). Change a name here and it changes everywhere.
 * Athena is the only Managed Agent; her protégés are in [Circle].
 */
object Workers {
    const val IRIS = "Iris"
    const val ATLAS = "Atlas"
    const val ECHO = "Echo"
}

/** Athena's circle (phase 2c): her advisor and her protégés, child threads inside her sessions. */
object Circle {
    const val METIS = "Metis"
    const val ODYSSEUS = "Odysseus"
    const val PERSEUS = "Perseus"
    const val ATALANTA = "Atalanta"
    const val DIOMEDES = "Diomedes"
    const val ARACHNE = "Arachne"
    const val ANNABETH = "Annabeth"
}
