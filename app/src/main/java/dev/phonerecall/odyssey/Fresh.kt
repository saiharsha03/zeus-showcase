package dev.phonerecall.odyssey

import dev.phonerecall.brain.Sealer
import java.io.File

/**
 * Drives through the fresh start (fresh/FreshStart.kt): kept only in the current drive-map format, else wiped. A
 * report stays when it decodes and is whole (an id, an end after its start); its sealed 90-day route stays when it
 * decodes, belongs to a kept report and isn't past its 90 days. The trip tag (Commute and the rest) rides on the report.
 * Everything else in Odyssey's document (places, visits, stays, guesses, trips, checks, notes, shapes) goes; the car log
 * and the switches stay. Routes never leave this package, so the migration lives here.
 */
object OdysseyFresh {
    /** The no-backup entries this handles: the document and the routes' folder. */
    const val DOC = "odyssey.sealed"
    const val ROUTES = "odyssey" + "-routes"

    fun valid(r: DriveReport): Boolean = r.id.isNotBlank() && r.end > r.start && r.meters >= 0

    fun keep(d: OdysseyData): OdysseyData = OdysseyData(settings = d.settings, drives = d.drives.filter(::valid).distinctBy { it.id }, car = d.car, nextId = d.nextId)

    fun route(r: DriveRoute?, reports: Set<String>, now: Long): Boolean = r != null && r.id in reports && r.points.isNotEmpty() && !Track.expired(r, now)

    /** In place, in no-backup storage. A document that doesn't open is deleted with its routes. Returns (drives, routes) kept. */
    fun migrate(noBackup: File, sealer: Sealer?, now: Long = System.currentTimeMillis()): Pair<Int, Int> {
        val doc = File(noBackup, DOC)
        val dir = File(noBackup, ROUTES)
        val data = if (doc.exists() && sealer != null) runCatching { OdysseyStore.decode(sealer, doc.readBytes()) }.getOrNull() else null
        if (sealer == null || data == null) { doc.delete(); dir.deleteRecursively(); return 0 to 0 }
        val kept = keep(data)
        val tmp = File(noBackup, "$DOC.tmp")
        tmp.writeBytes(OdysseyStore.encode(sealer, kept))
        if (!tmp.renameTo(doc)) { doc.delete(); tmp.renameTo(doc) }
        val ids = kept.drives.map { it.id }.toSet()
        val files = RouteFiles(dir, sealer)
        var routes = 0
        for (f in dir.listFiles().orEmpty()) {
            val r = runCatching { files.decode(f.readBytes()) }.getOrNull()
            if (route(r, ids, now)) routes++ else f.delete()
        }
        return kept.drives.size to routes
    }
}
