package dev.phonerecall.fresh

import dev.phonerecall.odyssey.OdysseyFresh

/** What the fresh start does with one store: keep it whole, wipe it, or keep some of it ([FreshPlan.keepKey], [dev.phonerecall.odyssey.OdysseyFresh]). */
enum class Fate { KEEP, WIPE, SOME }

/**
 * The fresh start (2.1): the first launch of this version clears every Zeus store except his keys
 * (Calypso), the pairing (mTLS cert, proxy key, device registration, push) and his drives in the current format.
 * No safety snapshot: a true clean slate. Every store Zeus writes is listed here as KEEP, WIPE or SOME; FreshPlanTest
 * scans the source and fails when a new one isn't, so nothing new slips past the wipe. A name not listed at all is a
 * library's (Firebase, WorkManager) and is left alone.
 */
object FreshPlan {
    const val VERSION = "fresh.v1"

    /** SharedPreferences files, by name. */
    val PREFS: Map<String, Fate> = mapOf(
        // Kept: his keys, the pairing, Calypso's shelves, the backup's setup (setup offers a new phrase), and the meters the caps read.
        "calypso" to Fate.KEEP,
        "vault" to Fate.KEEP,
        "lemnos" to Fate.SOME,
        "mnemosyne" to Fate.KEEP,
        "prometheus-cloud" to Fate.KEEP,
        "metron" to Fate.KEEP,
        "brain" to Fate.SOME,
        "mind" to Fate.SOME,
        // Wiped: everything he or Zeus learned.
        "aeolia" to Fate.WIPE, "agency" to Fate.WIPE, "alarm_watch" to Fate.WIPE, "asks" to Fate.WIPE, "athena" to Fate.WIPE,
        "athena_dismissed" to Fate.WIPE, "attention" to Fate.WIPE, "availability_overrides" to Fate.WIPE, "birthday_day" to Fate.WIPE,
        "brief" to Fate.WIPE, "clio" to Fate.WIPE, "clio-device" to Fate.WIPE, "eggs" to Fate.WIPE, "genesis" to Fate.WIPE,
        "hermes" to Fate.WIPE, "hestia" to Fate.WIPE, "horologion" to Fate.WIPE, "iaso" to Fate.WIPE, "iris_ask" to Fate.WIPE,
        "models" to Fate.WIPE, "music" to Fate.WIPE, "olympia" to Fate.WIPE, "oneiroi" to Fate.WIPE, "places" to Fate.WIPE,
        "prometheus.draft" to Fate.WIPE, "relevance" to Fate.WIPE, "seal" to Fate.WIPE, "shakedown" to Fate.WIPE,
        "shortcuts" to Fate.WIPE, "telete" to Fate.WIPE, "venus" to Fate.WIPE, "voice" to Fate.WIPE, "widgets" to Fate.WIPE,
        "account_alerts" to Fate.WIPE, "consents" to Fate.WIPE, "qol.workmute" to Fate.WIPE, "hestia_routines" to Fate.WIPE, "agency_learn" to Fate.WIPE, "qol.carmusic" to Fate.WIPE, "wish_style" to Fate.WIPE,
    )

    /** Databases, by file name. vault.db is Calypso. */
    val DBS: Map<String, Fate> = mapOf(
        "vault.db" to Fate.KEEP,
        "brain.db" to Fate.WIPE, "senses.db" to Fate.WIPE, "learning.db" to Fate.WIPE, "tasks.db" to Fate.WIPE,
        "alexandria.db" to Fate.WIPE, "moneta.db" to Fate.WIPE, "prometheus.db" to Fate.WIPE,
    )

    /** Top-level entries of filesDir. zeus-pair is the pairing's state. */
    val FILES: Map<String, Fate> = mapOf(
        "zeus-pair" to Fate.KEEP,
        "parnassus" to Fate.WIPE, "closet" to Fate.WIPE, "clio" to Fate.WIPE, "alexandria" to Fate.WIPE, "oneiroi" to Fate.WIPE,
        "themis-vectors.json" to Fate.WIPE,
    )

    /** Top-level entries of noBackupFilesDir. seal holds the envelope data keys (keys: kept); Odyssey keeps its drives. */
    val NOBACKUP: Map<String, Fate> = mapOf(
        "seal" to Fate.KEEP,
        OdysseyFresh.DOC to Fate.SOME, OdysseyFresh.ROUTES to Fate.SOME,
        "argo.sealed" to Fate.WIPE, "moments.sealed" to Fate.WIPE, "rhythms.sealed" to Fate.WIPE, "qol.sealed" to Fate.WIPE, "touches.sealed" to Fate.WIPE,
        "mnemosyne-stage" to Fate.WIPE, "mnemosyne-out" to Fate.WIPE, "mnemosyne-in" to Fate.WIPE,
    )

    /** Top-level entries of the external files dir: the on-device model is big and replaceable, not his data. */
    val EXTERNAL: Map<String, Fate> = mapOf(
        dev.phonerecall.memory.GemmaEmbedder.FILE to Fate.KEEP,
        "closet" to Fate.WIPE,
    )

    /** Libraries' own files (push registration among them): never touched. */
    private val LIBRARY = listOf("com.", "androidx.", "Firebase", "WebView", "google", "_", "PersistedInstallation", "generatefid")
    fun library(name: String) = LIBRARY.any { name.startsWith(it) }

    /** Agora's local cursors: reset so Athena starts at the room's newest post (the server keeps its history). */
    private val LEMNOS_DROP = setOf("agora.after", "agora.seen", "inbox.seq")

    /** Inside a SOME prefs file: true for a key that stays. */
    fun keepKey(file: String, key: String): Boolean = when (file) {
        "lemnos" -> key !in LEMNOS_DROP
        // The month's spend and the cap, so the $ cap holds across the wipe. Auto-approve and the model go.
        "brain" -> key.startsWith("spend.") || key == "cap" || key == "cap.2c"
        // Her stored agents, environment and memory store (found again by name anyway), and Metis and Annabeth's monthly spend.
        "mind" -> key == "id.env" || key == "id.store" || key.startsWith("id.agent.") || key.startsWith("hash.agent.") || key.startsWith("circle.")
        else -> true
    }

    /** What to do with a store found on the phone; null: not Zeus's (a library's), left alone. */
    fun fate(map: Map<String, Fate>, name: String): Fate? = map[name]

    /** The one Clio line. */
    fun line(cleared: Int): String = "fresh start: cleared $cleared stores, kept keys/pairing/drives"
}

