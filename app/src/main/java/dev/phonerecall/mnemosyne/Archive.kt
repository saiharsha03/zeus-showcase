package dev.phonerecall.mnemosyne

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.phonerecall.brain.Sealer
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Where Zeus keeps things on this phone. */
class Layout(val context: Context) {
    val prefsDir = File(context.dataDir, "shared_prefs")
    val dbDir: File get() = context.getDatabasePath("x").parentFile!!
    val stage = File(context.noBackupFilesDir, "mnemosyne-stage")
    fun root(r: String): File = when (r) { "files" -> context.filesDir; "nobackup" -> context.noBackupFilesDir; else -> throw IllegalArgumentException(r) }
    fun prefs(name: String): SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun prefsNames(): List<String> = prefsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.map { it.name.removeSuffix(".xml") }.orEmpty().sorted()
    fun dbNames(): List<String> = dbDir.listFiles()?.filter { f -> f.isFile && listOf("-journal", "-wal", "-shm").none { f.name.endsWith(it) } }?.map { it.name }.orEmpty().sorted()
}

/** The Aegis-level Keystore keys, by alias: on the old phone to open values, on the new one to seal them again. */
interface SealerBank {
    /** The aliases a value might be sealed under (trial-opened; AES-GCM never opens under the wrong key). */
    fun aliases(): List<String>
    fun sealer(alias: String): Sealer
}

/**
 * What goes into a backup and what never does. Every SharedPreferences file and every database is included unless
 * listed here, so a new store is backed up without anyone remembering to add it; files are an allowlist (models and
 * caches are big and replaceable).
 */
object Scope {
    /** calypso: the Anthropic key, Hetzner token and Spotify tokens. lemnos: pairing, device keys' state, push. mnemosyne: its own Keystore wraps. */
    val PREFS_SKIP = setOf("calypso", "lemnos", "mnemosyne")
    private val PREFS_SKIP_PREFIX = listOf("com.", "androidx.", "Firebase", "WebView", "google", "_")
    /** Per-phone values inside an otherwise included file. vault: whether this phone's Calypso key was published. */
    val PREFS_SKIP_KEYS = mapOf("vault" to setOf("pem.sent"))
    private val DB_SKIP_PREFIX = listOf("androidx.", "com.", "google", "mnemosyne")
    /** (root, path): a directory (every file under it) or a single file. */
    val FILES = listOf("files" to "parnassus", "files" to "closet", "files" to "alexandria/docs", "nobackup" to "odyssey.sealed")
    const val FILE_MAX = 64L shl 20
    val EXCLUDED = listOf(
        "Spotify sign-in (tokens)", "Anthropic key and Hetzner token", "Lemnos device keys and pairing", "Push registration",
        "Alexandria's on-device model", "Health Connect and Google Home permissions (Android keeps those)",
    )

    fun prefs(name: String) = name !in PREFS_SKIP && PREFS_SKIP_PREFIX.none { name.startsWith(it) }
    fun db(name: String) = DB_SKIP_PREFIX.none { name.startsWith(it) }
}

/** One part of the archive, as the index lists it. */
data class PartStat(
    val entry: String, val kind: String, val name: String, val items: Int = 0, val sealed: Int = 0, val unopened: Int = 0,
    val sha256: String = "", val bytes: Long = 0, val alias: String? = null, val error: String? = null,
)

/** Calypso's share of a backup: the escrow keypair (private half under the phrase) and each escrowed item key. */
class CalypsoEscrow(val pair: Escrow.Pair, val items: Map<String, ByteArray>)

data class ExportReport(val parts: List<PartStat>, val calypsoIn: Int, val calypsoLeftOut: Int)

private val mapper = ObjectMapper()
private val B64 = Regex("^[A-Za-z0-9+/]+={0,2}$")
private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
private fun unb64(s: String) = Base64.getDecoder().decode(s)
private fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""
private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

/** Counts and hashes what passes through, without closing what's underneath (the zip). */
private class Tap(out: OutputStream) : FilterOutputStream(out) {
    val md: MessageDigest = MessageDigest.getInstance("SHA-256")
    var n = 0L
    override fun write(b: Int) { out.write(b); md.update(b.toByte()); n++ }
    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); md.update(b, off, len); n += len }
    override fun close() = flush()
}

/**
 * Builds the archive: a zip of logical exports, every sealed value opened with its own Keystore key on this phone, so
 * the new phone can seal it again under its own. Entries: manifest.json (first), calypso/escrow.json (when included),
 * prefs/<file>.json, db/<file>.json, files/<root>/<path>, index.json (last: each part's sha256 and counts).
 * The whole zip is then encrypted by [Zbk]; nothing in here is ever written to disk in the clear.
 */
class Exporter(private val layout: Layout, private val bank: SealerBank, private val calypso: CalypsoEscrow?) {
    private val factory = JsonFactory().apply { disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET) }
    private val memo = HashMap<String, String>()

    /** The alias that opens [v], or null when it isn't sealed under any of ours. Tries the last alias that worked here first. */
    private fun opener(where: String, v: String): kotlin.Pair<String, String>? {
        if (v.length < 40 || !B64.matches(v)) return null
        val order = listOfNotNull(memo[where]) + bank.aliases().filter { it != memo[where] }
        for (a in order) bank.sealer(a).open(v)?.let { memo[where] = a; return a to it }
        return null
    }

    private fun openerBytes(where: String, v: ByteArray): kotlin.Pair<String, ByteArray>? {
        if (v.size < 28) return null
        val order = listOfNotNull(memo[where]) + bank.aliases().filter { it != memo[where] }
        for (a in order) bank.sealer(a).openBytes(v)?.let { memo[where] = a; return a to it }
        return null
    }

    fun export(out: OutputStream, manifest: Map<String, Any?>): ExportReport {
        val zip = ZipOutputStream(out).apply { setLevel(6) }
        val parts = ArrayList<PartStat>()
        fun entry(name: String, body: (Tap) -> PartStat): PartStat {
            zip.putNextEntry(ZipEntry(name))
            val t = Tap(zip)
            val p = body(t)
            t.flush(); zip.closeEntry()
            return p.copy(sha256 = hex(t.md.digest()), bytes = t.n).also { parts += it }
        }
        val m = LinkedHashMap(manifest)
        m["format"] = "zeus-mnemosyne"; m["v"] = 1; m["calypso"] = calypso != null; m["excluded"] = Scope.EXCLUDED
        entry("manifest.json") { t -> t.write(mapper.writeValueAsBytes(m)); PartStat("manifest.json", "manifest", "manifest") }

        var calIn = 0
        var calOut = 0
        if (calypso != null) entry("calypso/escrow.json") { t ->
            t.write(mapper.writeValueAsBytes(mapOf("public" to b64(calypso.pair.publicDer), "private" to b64(calypso.pair.sealedPrivate),
                "items" to calypso.items.mapValues { b64(it.value) })))
            PartStat("calypso/escrow.json", "escrow", "calypso", calypso.items.size)
        }

        for (name in layout.prefsNames().filter { Scope.prefs(it) }) entry("prefs/$name.json") { t ->
            var sealed = 0
            val skip = Scope.PREFS_SKIP_KEYS[name].orEmpty()
            val rows = layout.prefs(name).all.filterKeys { it !in skip }.toSortedMap().map { (k, v) ->
                when (v) {
                    is String -> opener("p:$name:$k", v)?.let { (a, plain) -> sealed++; mapOf("k" to k, "t" to "s", "v" to plain, "a" to a) } ?: mapOf("k" to k, "t" to "s", "v" to v)
                    is Int -> mapOf("k" to k, "t" to "i", "v" to v)
                    is Long -> mapOf("k" to k, "t" to "l", "v" to v)
                    is Float -> mapOf("k" to k, "t" to "f", "v" to v.toDouble())
                    is Boolean -> mapOf("k" to k, "t" to "b", "v" to v)
                    is Set<*> -> mapOf("k" to k, "t" to "set", "v" to v.map { it.toString() }.sorted())
                    else -> null
                }
            }.filterNotNull()
            t.write(mapper.writeValueAsBytes(mapOf("name" to name, "entries" to rows)))
            PartStat("prefs/$name.json", "prefs", name, rows.size, sealed)
        }

        for (name in layout.dbNames().filter { Scope.db(it) }) {
            var c = intArrayOf(0, 0, 0, 0)
            val p = entry("db/$name.json") { t ->
                val err = runCatching { c = dumpDb(File(layout.dbDir, name), name, t) }.exceptionOrNull()
                PartStat("db/$name.json", "db", name, c[0], c[1], c[2], error = err?.let { it.javaClass.simpleName + ": " + it.message?.take(160) })
            }
            calIn += c[3]
            if (name == "vault.db") calOut += countCalypso(File(layout.dbDir, name)) - c[3]
            if (p.error != null) android.util.Log.w("Mnemosyne", "db $name: ${p.error}")
        }

        for ((root, path) in Scope.FILES) {
            val base = File(layout.root(root), path)
            val files = if (base.isDirectory) base.walkTopDown().filter { it.isFile }.toList().sortedBy { it.path } else listOf(base).filter { it.isFile }
            for (f in files) {
                if (f.length() > Scope.FILE_MAX) continue
                val rel = f.relativeTo(layout.root(root)).invariantSeparatorsPath
                var alias: String? = null
                entry("files/$root/$rel") { t ->
                    val bytes = f.readBytes()
                    val opened = openerBytes("f:$root:${f.parentFile?.path}", bytes)
                    alias = opened?.first
                    t.write(opened?.second ?: bytes)
                    PartStat("files/$root/$rel", "file", rel, 1, if (opened != null) 1 else 0, alias = alias)
                }
            }
        }

        val report = ExportReport(parts.toList(), calIn, maxOf(0, calOut))
        zip.putNextEntry(ZipEntry("index.json"))
        zip.write(mapper.writeValueAsBytes(mapOf("parts" to report.parts, "calypso" to mapOf("in" to calIn, "left_out" to report.calypsoLeftOut))))
        zip.closeEntry()
        zip.finish()
        return report
    }

    private fun countCalypso(f: File): Int = runCatching {
        open(f).use { db -> db.rawQuery("SELECT count(*) FROM calypso", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 } }
    }.getOrDefault(0)

    private fun open(f: File): SQLiteDatabase = try {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
    } catch (_: Exception) {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
    }

    /** Returns [rows, sealed, unopened, calypso rows written]. */
    private fun dumpDb(file: File, name: String, out: OutputStream): IntArray {
        val counts = intArrayOf(0, 0, 0, 0)
        open(file).use { db ->
            val schema = db.rawQuery(
                "SELECT type, name, tbl_name, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata'", null,
            ).use { c -> buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) } }
            val virtual = schema.filter { it[0] == "table" && it[3].trimStart().startsWith("CREATE VIRTUAL", ignoreCase = true) }.map { it[1] }
            fun shadow(t: String) = virtual.any { v -> t != v && t.startsWith(v + "_") }
            val tables = schema.filter { it[0] == "table" && !shadow(it[1]) }.map { it[1] }
            val g = factory.createGenerator(out)
            g.writeStartObject()
            g.writeStringField("name", name)
            g.writeNumberField("version", db.version)
            g.writeArrayFieldStart("schema")
            for (s in schema.filter { !(it[0] == "table" && shadow(it[1])) }) { g.writeStartObject(); g.writeStringField("type", s[0]); g.writeStringField("name", s[1]); g.writeStringField("sql", s[3]); g.writeEndObject() }
            g.writeEndArray()
            g.writeArrayFieldStart("tables")
            for (t in tables) {
                val skipCalypso = name == "vault.db" && t == "calypso"
                if (skipCalypso && calypso == null) continue
                g.writeStartObject()
                g.writeStringField("name", t)
                val rowidAlias = db.rawQuery("PRAGMA table_info(${q(t)})", null).use { c ->
                    val pks = buildList { while (c.moveToNext()) if (c.getInt(c.getColumnIndexOrThrow("pk")) > 0) add(c.getString(c.getColumnIndexOrThrow("type"))) }
                    pks.size == 1 && pks[0].equals("INTEGER", ignoreCase = true)
                }
                val cur = (if (!rowidAlias) runCatching { db.rawQuery("SELECT rowid AS \"__rowid__\", * FROM ${q(t)}", null) }.getOrNull() else null)
                    ?: db.rawQuery("SELECT * FROM ${q(t)}", null)
                cur.use { c ->
                    val cols = c.columnNames.toList()
                    g.writeArrayFieldStart("cols"); cols.forEach { g.writeString(if (it == "__rowid__") "rowid" else it) }; g.writeEndArray()
                    val sealedCols = cols.indices.map { i -> if (cols[i] == "__rowid__") null else columnAlias(db, name, t, cols[i]) }
                    val idCol = cols.indexOf("id")
                    g.writeArrayFieldStart("rows")
                    while (c.moveToNext()) {
                        if (skipCalypso && calypso!!.items[c.getString(idCol)] == null) continue
                        if (skipCalypso) counts[3]++
                        g.writeStartArray()
                        for (i in cols.indices) writeCell(g, c, i, sealedCols[i], counts)
                        g.writeEndArray()
                        counts[0]++
                    }
                    g.writeEndArray()
                }
                g.writeEndObject()
            }
            g.writeEndArray()
            g.writeEndObject()
            g.flush()
        }
        return counts
    }

    /** A column is sealed when one of its first few long text/blob values opens under one of our keys. */
    private fun columnAlias(db: SQLiteDatabase, name: String, t: String, col: String): String? {
        val where = "d:$name:$t:$col"
        db.rawQuery("SELECT ${q(col)} FROM ${q(t)} WHERE typeof(${q(col)}) IN ('text', 'blob') AND length(${q(col)}) >= 28 LIMIT 5", null).use { c ->
            while (c.moveToNext()) {
                val hit = if (c.getType(0) == Cursor.FIELD_TYPE_BLOB) openerBytes(where, c.getBlob(0))?.first else opener(where, c.getString(0))?.first
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun writeCell(g: JsonGenerator, c: Cursor, i: Int, alias: String?, counts: IntArray) {
        when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> g.writeNull()
            Cursor.FIELD_TYPE_INTEGER -> g.writeNumber(c.getLong(i))
            Cursor.FIELD_TYPE_FLOAT -> { g.writeStartObject(); g.writeNumberField("f", c.getDouble(i)); g.writeEndObject() }
            Cursor.FIELD_TYPE_STRING -> {
                val v = c.getString(i)
                val plain = if (alias != null && v.length >= 40 && B64.matches(v)) bank.sealer(alias).open(v) else null
                if (alias != null && plain == null && v.length >= 40 && B64.matches(v)) counts[2]++
                if (plain != null) { counts[1]++; g.writeStartObject(); g.writeStringField("st", plain); g.writeStringField("a", alias); g.writeEndObject() } else g.writeString(v)
            }
            else -> {
                val v = c.getBlob(i)
                val plain = if (alias != null && v.size >= 28) bank.sealer(alias).openBytes(v) else null
                if (alias != null && plain == null && v.size >= 28) counts[2]++
                g.writeStartObject()
                if (plain != null) { counts[1]++; g.writeStringField("sb", b64(plain)); g.writeStringField("a", alias) } else g.writeStringField("b", b64(v))
                g.writeEndObject()
            }
        }
    }
}

/** What a verified archive holds, before anything on this phone changes. */
data class Verified(val manifest: JsonNode, val parts: List<PartStat>, val calypsoIn: Int, val calypsoLeftOut: Int)

data class RestoreReport(val prefs: Int, val dbs: Int, val files: Int, val calypso: Int, val calypsoLost: Int, val failed: List<String>)

/**
 * The new phone's side. [verify] reads the whole archive once and checks every entry against the index (the archive is
 * already authenticated chunk by chunk; this also catches a phone-side bug). [stage] reads it again and writes each
 * store, sealed again under this phone's keys, into a staging folder; [Stage.apply] moves them into place the next
 * time the process starts, before any store is open.
 */
class Restorer(private val layout: Layout, private val bank: SealerBank, private val newCalypso: () -> PublicKey, private val escrowKey: ByteArray?) {
    private val factory = JsonFactory().apply { disable(JsonParser.Feature.AUTO_CLOSE_SOURCE) }

    fun verify(open: () -> InputStream): Verified {
        var manifest: JsonNode? = null
        var index: JsonNode? = null
        val seen = LinkedHashMap<String, String>()
        ZipInputStream(open()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val md = MessageDigest.getInstance("SHA-256")
                val buf = ByteArray(64 * 1024)
                val keep = e.name == "manifest.json" || e.name == "index.json"
                val small = if (keep) java.io.ByteArrayOutputStream() else null
                while (true) { val k = z.read(buf); if (k < 0) break; md.update(buf, 0, k); small?.write(buf, 0, k) }
                if (e.name == "manifest.json") manifest = mapper.readTree(small!!.toByteArray())
                if (e.name == "index.json") index = mapper.readTree(small!!.toByteArray()) else seen[e.name] = hex(md.digest())
            }
        }
        val m = manifest ?: throw Zbk.Tampered("The backup has no manifest.")
        if (m.path("format").asText() != "zeus-mnemosyne") throw Zbk.Tampered("Not a Mnemosyne backup.")
        if (m.path("v").asInt() != 1) throw Zbk.Tampered("This backup was made by a newer Zeus (format ${m.path("v").asInt()}).")
        val idx = index ?: throw Zbk.Tampered("The backup is incomplete (no index).")
        fun JsonNode.s(k: String) = path(k).takeIf { it.isTextual }?.asText()
        val parts = idx.path("parts").map {
            PartStat(it.path("entry").asText(), it.path("kind").asText(), it.path("name").asText(), it.path("items").asInt(), it.path("sealed").asInt(),
                it.path("unopened").asInt(), it.path("sha256").asText(), it.path("bytes").asLong(), it.s("alias"), it.s("error"))
        }
        if (parts.map { it.entry }.toSet() != seen.keys) throw Zbk.Tampered("The backup's index doesn't match its contents.")
        for (p in parts) if (seen[p.entry] != p.sha256) throw Zbk.Tampered("${p.entry} doesn't match its checksum.")
        return Verified(m, parts, idx.path("calypso").path("in").asInt(), idx.path("calypso").path("left_out").asInt())
    }

    fun stage(open: () -> InputStream, v: Verified): RestoreReport {
        val st = layout.stage
        st.deleteRecursively(); st.mkdirs()
        val byEntry = v.parts.associateBy { it.entry }
        var escrowPriv: PrivateKey? = null
        var escrowItems: Map<String, ByteArray> = emptyMap()
        val plan = mutableMapOf("prefs" to ArrayList<String>(), "db" to ArrayList<String>(), "files" to ArrayList<String>())
        val failed = ArrayList<String>()
        var cal = 0
        ZipInputStream(open()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val n = e.name
                try {
                    when {
                        n == "calypso/escrow.json" -> {
                            val o = mapper.readTree(z.readBytes())
                            val k = escrowKey ?: throw IllegalStateException("no phrase")
                            escrowPriv = Escrow.openPrivate(unb64(o.path("private").asText()), k)
                            escrowItems = o.path("items").fields().asSequence().associate { it.key to unb64(it.value.asText()) }
                            File(st, "escrow.json").writeBytes(mapper.writeValueAsBytes(mapOf("public" to o.path("public").asText(), "private" to o.path("private").asText(),
                                "items" to o.path("items"))))
                        }
                        n.startsWith("prefs/") -> {
                            val o = mapper.readTree(z.readBytes())
                            val name = o.path("name").asText()
                            if (!Scope.prefs(name)) continue
                            val rows = o.path("entries").map { r ->
                                val a = r.path("a").takeIf { !it.isMissingNode }?.asText()
                                if (a == null) r else (r.deepCopy<ObjectNode>().put("v", bank.sealer(a).seal(r.path("v").asText())).also { it.remove("a") })
                            }
                            File(st, "prefs").mkdirs()
                            File(st, "prefs/$name.json").writeBytes(mapper.writeValueAsBytes(rows))
                            plan.getValue("prefs") += name
                        }
                        n.startsWith("db/") -> {
                            val name = n.removePrefix("db/").removeSuffix(".json")
                            if (!Scope.db(name) || name.contains('/')) continue
                            File(st, "db").mkdirs()
                            val f = File(st, "db/$name")
                            cal += buildDb(z, f, name, escrowPriv, escrowItems)
                            plan.getValue("db") += name
                        }
                        n.startsWith("files/") -> {
                            val rest = n.removePrefix("files/")
                            val root = rest.substringBefore('/')
                            val path = rest.substringAfter('/')
                            val allowed = Scope.FILES.any { (r, p) -> r == root && (path == p || path.startsWith("$p/")) }
                            if (!allowed || path.split('/').any { it == ".." || it.isEmpty() }) continue
                            val bytes = z.readBytes()
                            val a = byEntry[n]?.alias
                            val out = File(st, "files/$root/$path").apply { parentFile?.mkdirs() }
                            out.writeBytes(if (a != null) bank.sealer(a).sealBytes(bytes) else bytes)
                            plan.getValue("files") += "$root/$path"
                        }
                    }
                } catch (ex: Exception) {
                    failed += "$n: ${ex.javaClass.simpleName}"
                }
            }
        }
        File(st, "plan.json").writeBytes(mapper.writeValueAsBytes(plan))
        File(st, "READY").writeText("1")
        return RestoreReport(plan.getValue("prefs").size, plan.getValue("db").size, plan.getValue("files").size, cal, v.calypsoLeftOut, failed)
    }

    /** Streams one db/<name>.json into a fresh database file. Returns how many Calypso items came across. */
    private fun buildDb(input: InputStream, f: File, name: String, escrowPriv: PrivateKey?, escrow: Map<String, ByteArray>): Int {
        f.delete()
        var cal = 0
        val p = factory.createParser(input)
        p.codec = mapper
        val db = SQLiteDatabase.openOrCreateDatabase(f, null)
        try {
            var version = 0
            var later = emptyList<JsonNode>()
            check(p.nextToken() == JsonToken.START_OBJECT)
            db.beginTransaction()
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                when (p.currentName()) {
                    "version" -> { p.nextToken(); version = p.intValue }
                    "schema" -> {
                        p.nextToken()
                        val schema: JsonNode = p.readValueAsTree()
                        for (s in schema) if (s.path("type").asText() == "table") db.execSQL(s.path("sql").asText())
                        later = schema.filter { it.path("type").asText() != "table" }
                    }
                    "tables" -> {
                        check(p.nextToken() == JsonToken.START_ARRAY)
                        while (p.nextToken() == JsonToken.START_OBJECT) {
                            var table = ""
                            var cols = emptyList<String>()
                            while (p.nextToken() == JsonToken.FIELD_NAME) {
                                when (p.currentName()) {
                                    "name" -> { p.nextToken(); table = p.text }
                                    "cols" -> { p.nextToken(); cols = p.readValueAsTree<JsonNode>().map { it.asText() } }
                                    "rows" -> {
                                        check(p.nextToken() == JsonToken.START_ARRAY)
                                        val calypso = name == "vault.db" && table == "calypso"
                                        val wk = cols.indexOf("wk")
                                        val idc = cols.indexOf("id")
                                        val stmt = db.compileStatement("INSERT INTO ${q(table)} (${cols.joinToString(", ") { q(it) }}) VALUES (${cols.joinToString(", ") { "?" }})")
                                        while (p.nextToken() == JsonToken.START_ARRAY) {
                                            val row: JsonNode = p.readValueAsTree()
                                            stmt.clearBindings()
                                            if (calypso) {
                                                val id = row[idc].asText()
                                                val esc = escrow[id] ?: continue
                                                val priv = escrowPriv ?: continue
                                                for (i in cols.indices) if (i != wk) bind(stmt, i + 1, row[i])
                                                stmt.bindBlob(wk + 1, Escrow.rewrap(priv, esc, newCalypso()))
                                                cal++
                                            } else for (i in cols.indices) bind(stmt, i + 1, row[i])
                                            stmt.executeInsert()
                                        }
                                        stmt.close()
                                    }
                                    else -> { p.nextToken(); p.skipChildren() }
                                }
                            }
                        }
                    }
                    else -> { p.nextToken(); p.skipChildren() }
                }
            }
            for (s in later) db.execSQL(s.path("sql").asText())
            db.version = version
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
            p.close()
        }
        return cal
    }

    private fun bind(s: android.database.sqlite.SQLiteStatement, i: Int, v: JsonNode) {
        when {
            v.isNull -> s.bindNull(i)
            v.isIntegralNumber -> s.bindLong(i, v.asLong())
            v.isTextual -> s.bindString(i, v.asText())
            v.has("f") -> s.bindDouble(i, v.path("f").asDouble())
            v.has("b") -> s.bindBlob(i, unb64(v.path("b").asText()))
            v.has("st") -> s.bindString(i, bank.sealer(v.path("a").asText()).seal(v.path("st").asText()))
            v.has("sb") -> s.bindBlob(i, bank.sealer(v.path("a").asText()).sealBytes(unb64(v.path("sb").asText())))
            else -> s.bindNull(i)
        }
    }
}

/** The staged restore, moved into place at process start ([RestoreGate]) before any store has opened a file. */
object Stage {
    fun pending(layout: Layout) = File(layout.stage, "READY").isFile

    /** Returns true when a staged restore was applied. Each store is replaced whole; what the backup didn't hold stays. */
    fun apply(layout: Layout): Boolean {
        val st = layout.stage
        if (!File(st, "READY").isFile) return false
        val plan = runCatching { mapper.readTree(File(st, "plan.json")) }.getOrNull()
        if (plan != null) {
            for (name in plan.path("db").map { it.asText() }) {
                val src = File(st, "db/$name")
                if (!src.isFile) continue
                val dst = File(layout.dbDir.apply { mkdirs() }, name)
                for (s in listOf("", "-journal", "-wal", "-shm")) File(dst.path + s).delete()
                if (!src.renameTo(dst)) src.copyTo(dst, overwrite = true)
            }
            for (name in plan.path("prefs").map { it.asText() }) {
                val rows = runCatching { mapper.readTree(File(st, "prefs/$name.json")) }.getOrNull() ?: continue
                val ed = layout.prefs(name).edit().clear()
                for (r in rows) {
                    val k = r.path("k").asText()
                    val v = r.path("v")
                    when (r.path("t").asText()) {
                        "s" -> ed.putString(k, v.asText())
                        "i" -> ed.putInt(k, v.asInt())
                        "l" -> ed.putLong(k, v.asLong())
                        "f" -> ed.putFloat(k, v.asDouble().toFloat())
                        "b" -> ed.putBoolean(k, v.asBoolean())
                        "set" -> ed.putStringSet(k, v.map { it.asText() }.toSet())
                    }
                }
                ed.commit()
            }
            for (rel in plan.path("files").map { it.asText() }) {
                val src = File(st, "files/$rel")
                val root = rel.substringBefore('/')
                val dst = File(layout.root(root), rel.substringAfter('/')).apply { parentFile?.mkdirs() }
                if (src.isFile && !src.renameTo(dst)) src.copyTo(dst, overwrite = true)
            }
        }
        File(st, "escrow.json").takeIf { it.isFile }?.let { Mnemosyne.adoptEscrow(layout.context, mapper.readTree(it)) }
        st.deleteRecursively()
        return true
    }
}
