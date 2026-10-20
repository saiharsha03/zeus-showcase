package dev.phonerecall.settings

import android.content.Context
import dev.phonerecall.ui.Eggs
import dev.phonerecall.ui.Place
import dev.phonerecall.memory.Words
import dev.phonerecall.memory.cosine
import java.io.File

/** Themis's sections, in the order they show. */
enum class Group(val title: String) {
    ATHENA("Athena"),
    AGENTS("Agents & server"),
    PHONE("Phone & permissions"),
    PLACES("Places & travel"),
    MONEY("Money"),
    HEALTH("Health"),
    HOME("Home"),
    MUSIC("Music"),
    DISPLAY("Display & names"),
    DATA("Data & backup"),
    OTHER("Other"),
}

/**
 * One searchable thing: a Themis row, or (with [dest]) a place in the sidebar. [row] is the Themis row it lives in
 * (several entries can point at one row, like the rows inside Athena's mind). [place] adds its Greek and plain
 * names, motto and epithet to what is searched. Synonyms are the everyday words he might use instead.
 */
data class SettingEntry(
    val key: String, val group: Group, val label: String, val about: String, val synonyms: List<String> = emptyList(), val row: String = key,
    val place: Place? = null, val dest: String? = null,
) {
    /** Both names, whatever the plain-names toggle says: "Hypnos · Bedtime". */
    val name: String get() = place?.let { "${it.myth} · ${it.plain}" } ?: label

    /** What the embedder reads. */
    val text: String get() = listOfNotNull(
        name, label.takeIf { it != place?.myth && it != place?.plain }, about, place?.motto, place?.let { Eggs.EPITHETS[it] }, synonyms.joinToString(", ").ifEmpty { null },
    ).joinToString(". ")
}

/** Every Themis row, by section. The screen draws rows in this order; search reads labels, abouts and synonyms. */
object ThemisRegistry {
    private fun e(key: String, g: Group, label: String, about: String, vararg syn: String, row: String = key, place: Place? = null) = SettingEntry(key, g, label, about, syn.toList(), row, place)

    val entries: List<SettingEntry> = listOf(
        e("delphi-model", Group.ATHENA, "Delphi model", "Auto, Haiku, Sonnet or Opus for Delphi's answers", "model", "claude", "smarter", "faster", "ai", place = Place.ORACLE),
        e("mind", Group.ATHENA, "Athena's mind", "Runs on Managed Agents with her circle; last sync and wakeups", "agent", "sync", "managed agents", "brain"),
        e("heartbeat", Group.ATHENA, "Heartbeat", "Her hourly look at what changed", "hourly", "check in", "proactive", row = "mind"),
        e("horologion", Group.ATHENA, "Horologion", "Every job and its hour, every skill, hook and setting", "schedules", "cron", "jobs", "skills", "hooks", row = "mind", place = Place.HOROLOGION),
        e("oneiroi", Group.ATHENA, "Oneiroi", "What she knows, with ids you can fix by number", "memory care", "facts", "dreams", row = "mind", place = Place.DREAMS),
        e("remembered", Group.ATHENA, "What Athena remembers", "Her long-term memory files; forget any file", "memory", "forget", "files", row = "mind"),
        e("store-lists", Group.ATHENA, "Store lists on arrival", "Stores that bring up your list when you arrive", "shopping", "grocery", "geofence", "arrive", row = "mind"),
        e("assistant", Group.ATHENA, "Default assistant", "Make Zeus the side-key assistant", "side key", "digital assistant", "power button", row = "mind"),
        e("refresh-known", Group.ATHENA, "Refresh what you know", "Theogony asks Iris again and re-reads the phone", "theogony", "interview", "start over", "about me", place = Place.SEED),
        e("fates", Group.ATHENA, "Fates", "Every proposal: waiting, done and rejected", "proposals", "approvals", "history", place = Place.PROPOSALS),
        e("auto-approve", Group.ATHENA, "Auto-approve and attention", "Kinds that run without asking, and the daily card and notification budget", "automatic", "budget", "attention", "notifications per day"),
        e("always-ask", Group.ATHENA, "Always ask", "Calendar writes and plugs always ask; locks are never controlled", "confirm", "permission", "locks", "garage"),
        e("nano", Group.ATHENA, "Gemini Nano", "On-device sorting while Zeus is open", "on device", "local model", "offline ai"),
        e("usage", Group.AGENTS, "Metron", "Plan usage for Iris and Atlas, and Athena's spend", "usage", "limits", "plan", "quota", "spend", place = Place.USAGE),
        e("spend", Group.AGENTS, "Spend this month", "What Delphi, the mind and live voice cost so far", "cost", "money spent", "dollars", "bill", "tokens"),
        e("cap", Group.AGENTS, "Monthly cap", "The most Athena may spend in a month", "limit", "budget", "dollars", "max spend"),
        e("pantheon", Group.AGENTS, "Pantheon", "Iris, Atlas and Agora: who is running and what they are doing", "agents", "iris", "atlas", "agora", "workers", place = Place.AGENTS),
        e("lemnos", Group.AGENTS, "Lemnos", "The server: terminal, files, services and the desktop", "vps", "server", "hetzner", "terminal", "ssh", place = Place.VPS),
        e("setup", Group.PHONE, "Telete", "Everything Zeus needs, and a test for each", "setup", "checklist", "test", "self-test", "what's missing", "onboarding", place = Place.SETUP),
        e("battery", Group.PHONE, "Unrestricted background", "Battery set to Unrestricted, so syncs and Odyssey run with the screen off", "battery", "power", "doze", "optimization", "sleeping apps", "background"),
        e("senses", Group.PHONE, "Notification senses", "Reads notifications to make cards and catch birthdays", "notification access", "listener", "read notifications"),
        e("people", Group.PHONE, "Harmonia", "Who matters, and how fast you answer them; on the phone only", "people", "contacts", "family", "friends", "who matters", place = Place.PEOPLE),
        e("perm-calendar", Group.PHONE, "Calendar access", "Your next event on Home, read live", "calendar permission", "events"),
        e("perm-notify", Group.PHONE, "Notifications", "For the Now Bar and nudges", "alerts", "push", "notification permission"),
        e("perm-contacts", Group.PHONE, "Contacts", "First names, birthdays and relations only", "people", "address book", "phone book"),
        e("perm-calwrite", Group.PHONE, "Calendar writes", "Confirmed proposals add events to your Google calendar", "add events", "write calendar"),
        e("perm-mic", Group.PHONE, "Microphone", "For Speak in Parnassus", "voice", "audio", "record", "speak"),
        e("screen-time", Group.PHONE, "Screen time", "Usage access: yesterday's total, top apps and late nights", "usage access", "apps", "phone use", "digital wellbeing"),
        e("netgate", Group.PHONE, "NetGate", "Only the allowed hosts; every other host is blocked and logged", "network", "firewall", "privacy", "internet", "blocked"),
        e("outbound", Group.PHONE, "What left the phone", "Every request in the last 7 days, never the content", "privacy", "requests", "network log", "data sent"),
        e("odyssey", Group.PLACES, "Odyssey", "Learned places, commutes and the driving report", "places", "where I park", "parking", "car", "drives", "drive", "home", "office", "commute", "location", "tracking", "driving", place = Place.PLACES),
        e("trips", Group.PLACES, "Argo", "Trips: flights, stays and plans", "travel", "flight", "trip", "vacation", "hotel", place = Place.TRIPS),
        e("weather", Group.PLACES, "Aeolia", "Weather where you are, and its alerts", "weather", "forecast", "rain", "snow", "temperature", place = Place.WEATHER),
        e("money", Group.MONEY, "Moneta", "Spending from bank and card notifications, on the phone only", "money", "spending", "bank", "budget", "transactions", "card", place = Place.MONEY),
        e("vault", Group.DATA, "Calypso", "Sealed and Shielded, each behind your fingerprint", "vault", "keys", "api key", "passwords", "secrets", "fingerprint", place = Place.VAULT),
        e("health", Group.HEALTH, "Iaso", "Steps, sleep, heart rate and weight from Health Connect", "health", "steps", "sleep", "heart", "weight", "watch", "fitness", place = Place.HEALTH),
        e("bedtime", Group.HEALTH, "Hypnos nudges", "One quiet nudge when you are up past bedtime before an early start", "bedtime", "sleep", "late night", "alarm", "night", "quiet", place = Place.BEDTIME),
        e("rhythms", Group.OTHER, "Horae", "When you usually open apps and call home", "rhythms", "routine", "habits", "call home", "call log", place = Place.RHYTHMS),
        e("hestia", Group.HOME, "Hestia", "Lights and plugs through Google Home", "devices", "lights", "smart home", "plugs", "google home", place = Place.DEVICES),
        e("sample-cards", Group.HOME, "Show sample cards", "Fills the placeholder cards with examples", "demo", "placeholder", "fake cards"),
        e("now-bar", Group.HOME, "Show in Now Bar", "The top card on the lock screen and status bar", "lock screen", "status bar", "live notification"),
        e("edit-home", Group.HOME, "Edit Home", "Add, hide and restore cards", "cards", "layout", "rearrange", "hide card"),
        e("add-card", Group.HOME, "Add card", "Put another card on Home", "new card", "widget"),
        e("reset-learning", Group.HOME, "Reset learning", "Forget taps and dismissals; pins and order stay", "reset", "forget taps", "start fresh"),
        e("moments", Group.HOME, "Kairos moments", "The obvious helper for the moment, one card at a time", "moments", "helpers", "suggestions", "kairos", place = Place.MOMENTS),
        e("snap", Group.HOME, "Snap the box", "Timers from a box's cooking instructions", "cooking", "timer", "camera", "recipe"),
        e("music", Group.MUSIC, "Music", "Spotify by default, Apple Music as the fallback; music while driving", "spotify", "songs", "playlist", "apple music", "drive music"),
        e("theme", Group.DISPLAY, "Theme", "Dark or light", "dark mode", "light mode", "colors", "appearance"),
        e("plain-names", Group.DISPLAY, "Plain names", "Home, Calendar, Tasks instead of Olympus, Chronos, Praxis", "names", "greek names", "simple names", "labels"),
        e("second-clock", Group.DISPLAY, "Janus · second clock", "The second clock Athena reads next to yours", "world clock", "time zone", "other city", "two clocks"),
        e("backup", Group.DATA, "Mnemosyne", "Nightly backup locked with a recovery phrase only you hold", "backup", "restore", "recovery phrase", "storage box", place = Place.BACKUP),
        e("memory", Group.DATA, "Alexandria", "Search over your notes, chats and documents; index status", "memory", "search", "index", "embeddings", "documents", place = Place.MEMORY),
        e("logs", Group.DATA, "Clio", "Everything the phone did for the last 14 days", "logs", "debug", "export", "history", place = Place.LOGS),
        e("audit", Group.DATA, "Audit log", "Every proposal, decision, key use and blocked request", "audit", "history", "security log"),
        e("brief", Group.OTHER, "Eos", "The morning brief after your alarm, with Iris's part", "morning brief", "brief", "read aloud", "daily summary", place = Place.BRIEF),
        e("sports", Group.OTHER, "Olympia", "Teams you follow and their alerts", "sports", "teams", "scores", "football", "cricket", "f1", place = Place.SPORTS),
        e("cal-relevance", Group.OTHER, "Calendar relevance", "Which calendars show, and the rules that hide or keep events", "calendars", "hide events", "rules", "holidays"),
        e("about", Group.OTHER, "About", "The Zeus coin, version, build and network", "version", "build", "about zeus"),
    )

    /** Everyday words for each place in the sidebar (its names, motto and epithet are searched anyway). */
    private val PLACE_WORDS = mapOf(
        Place.HOME to listOf("cards", "today", "start"), Place.ORACLE to listOf("chat", "ask", "question", "assistant"),
        Place.CALENDAR to listOf("events", "schedule", "agenda", "birthdays"), Place.TASKS to listOf("todo", "to do", "list", "inbox"),
        Place.CLOSET to listOf("clothes", "outfit", "wear"), Place.PROPOSALS to listOf("approvals", "suggestions"),
        Place.RAMBLER to listOf("notes", "capture", "voice note", "jot"), Place.PEOPLE to listOf("contacts", "family", "friends"),
        Place.MEMORY to listOf("search", "documents", "notes"), Place.AGENTS to listOf("iris", "atlas", "agora", "workers"),
        Place.VPS to listOf("server", "terminal", "files", "hetzner"), Place.ALERTS to listOf("warnings", "watchers", "monitoring"),
        Place.SPORTS to listOf("scores", "teams", "matches"), Place.WEATHER to listOf("forecast", "rain", "snow"),
        Place.PLACES to listOf("car", "drives", "commute", "where I park", "parking", "location"), Place.HEALTH to listOf("steps", "sleep", "heart", "weight"),
        Place.DEVICES to listOf("lights", "plugs", "smart home"), Place.VAULT to listOf("keys", "passwords", "secrets"),
        Place.MONEY to listOf("bank", "spending", "budget", "card"), Place.TRIPS to listOf("travel", "flights", "hotel"),
        Place.IMPROVE to listOf("update", "build", "fix the app"), Place.SETTINGS to listOf("settings", "preferences", "options"),
        Place.SETUP to listOf("setup", "checklist", "self-test", "missing"),
    )

    /** The sidebar's places: every destination it can show (Telete included), searchable by both names. */
    val places: List<SettingEntry> = dev.phonerecall.Dest.shown(setupLeft = true).distinctBy { it.place }.map { d ->
        SettingEntry("place:${d.name}", Group.OTHER, d.place.myth, d.place.motto, PLACE_WORDS[d.place].orEmpty(), place = d.place, dest = d.name)
    }

    /** Places and settings together: the sidebar's search. */
    val all: List<SettingEntry> get() = places + entries

    /** Row keys in display order, each under its section. */
    val rows: List<Pair<Group, String>> = Group.entries.flatMap { g -> entries.filter { it.group == g }.map { it.row }.distinct().map { g to it } }
}

/**
 * Themis's search: words first (label, synonyms, description), and meaning when EmbeddingGemma has vectors
 * for the registry. Pure; the vectors and the query vector come from [ThemisIndex].
 */
object ThemisSearch {
    /** How well the words match, 0 when they don't. Label beats synonym beats description. */
    fun keyword(query: String, e: SettingEntry): Double {
        val q = Words.tokens(query)
        if (q.isEmpty()) return 0.0
        val label = Words.tokens(e.label) + e.place?.let { Words.tokens(it.myth + " " + it.plain) }.orEmpty()
        val syn = e.synonyms.flatMap { Words.tokens(it) }
        val about = Words.tokens(e.about) + e.place?.let { Words.tokens(it.motto + " " + Eggs.EPITHETS[it].orEmpty()) }.orEmpty()
        // A whole word counts fully; the start of a word ("hyp" for Hypnos) counts half, so "car" finds Odyssey before "memory care".
        fun hit(words: List<String>, t: String) = when {
            t in words -> 1.0
            t.length >= 3 && words.any { it.startsWith(t) } -> 0.5
            else -> 0.0
        }
        val per = q.map { t -> maxOf(3.0 * hit(label, t), 2.0 * hit(syn, t), 1.0 * hit(about, t)) }
        if (per.all { it == 0.0 }) return 0.0
        val phrase = query.trim().lowercase()
        val names = listOfNotNull(e.label, e.place?.myth, e.place?.plain).map { it.lowercase() }
        val bonus = (if (phrase.length >= 3 && names.any { Regex("\\b" + Regex.escape(phrase) + "\\b").containsMatchIn(it) }) 2.0 else 0.0) +
            (if (e.synonyms.any { it.lowercase() == phrase }) 1.5 else 0.0)
        // Every word should count: a query half matched ranks below one fully matched.
        return per.sum() / q.size * (per.count { it > 0 }.toDouble() / q.size) + bonus
    }

    const val MEANING_FLOOR = 0.45

    /** Ranked matches. Without [queryVector] (or vectors) it is words only. */
    fun rank(query: String, entries: List<SettingEntry>, vectors: Map<String, FloatArray> = emptyMap(), queryVector: FloatArray? = null, limit: Int = 12): List<SettingEntry> {
        if (query.isBlank()) return emptyList()
        return entries.mapNotNull { e ->
            val kw = keyword(query, e)
            val cos = if (queryVector == null) 0.0 else vectors[e.key]?.let { cosine(queryVector, it) } ?: 0.0
            val meaning = if (cos >= MEANING_FLOOR) 6.0 * (cos - MEANING_FLOOR + 0.1) else 0.0
            (kw + meaning).takeIf { it > 0 }?.let { e to it }
        }.sortedByDescending { it.second }.map { it.first }.distinctBy { it.dest ?: (it.row + "|" + it.label) }.take(limit)
    }

    /** Changes when any entry's text does, so the cached vectors are rebuilt. */
    fun hash(entries: List<SettingEntry>): String = Integer.toHexString(entries.joinToString("\n") { it.key + "|" + it.text }.hashCode())
}

/**
 * The registry's EmbeddingGemma vectors, made once and cached in files/themis-vectors.json (keyed by
 * [ThemisSearch.hash]). Nothing loads when the model file isn't on the phone; the search is words only then.
 */
object ThemisIndex {
    @Volatile var vectors: Map<String, FloatArray> = emptyMap(); private set
    private data class Cache(val hash: String = "", val v: Map<String, FloatArray> = emptyMap())

    private fun file(c: Context) = File(c.filesDir, "themis-vectors.json")

    /** At startup, off the main thread. */
    fun warm(c: Context) {
        if (vectors.isNotEmpty() || dev.phonerecall.memory.GemmaEmbedder.model(c) != dev.phonerecall.memory.EmbedModel.READY) return
        val all = ThemisRegistry.all
        val hash = ThemisSearch.hash(all)
        val cached = runCatching { dev.phonerecall.mind.stateJson.readValue(file(c), Cache::class.java) }.getOrNull()
        if (cached != null && cached.hash == hash && cached.v.size == all.size) { vectors = cached.v; return }
        val e = dev.phonerecall.memory.GemmaEmbedder.get(c) ?: return
        val made = all.mapNotNull { s -> e.embed(s.text)?.let { s.key to it } }.toMap()
        if (made.size != all.size) return
        vectors = made
        runCatching { dev.phonerecall.mind.stateJson.writeValue(file(c), Cache(hash, made)) }
        dev.phonerecall.clio.Clio.info("ui", "themis search indexed", "${made.size} settings")
    }

    /** The query's vector, or null when meaning search isn't ready. */
    fun embed(c: Context, query: String): FloatArray? =
        if (vectors.isEmpty()) null else dev.phonerecall.memory.GemmaEmbedder.get(c)?.embed(query)
}
