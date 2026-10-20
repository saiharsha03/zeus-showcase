package dev.phonerecall.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import dev.phonerecall.brain.LocalModel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * alexandria.db: documents, chunks and vectors, with an FTS5 table for BM25. Excluded from backup like
 * every other database. If this SQLite build has no FTS5, the same BM25 runs in Kotlin over the chunks.
 */
class SqliteIndex(context: Context) : SQLiteOpenHelper(context, "alexandria.db", null, 1), MemoryIndex {
    @Volatile private var fts: Boolean? = null
    private val fallback by lazy { Bm25().also { b -> allChunks().forEach { b.put(it.id, it.title, it.text) } } }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE docs (id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT NOT NULL, at INTEGER NOT NULL, shield TEXT NOT NULL, ref TEXT, hash TEXT NOT NULL)")
        db.execSQL("CREATE TABLE chunks (id INTEGER PRIMARY KEY AUTOINCREMENT, doc_id TEXT NOT NULL, idx INTEGER NOT NULL, text TEXT NOT NULL, vec BLOB)")
        db.execSQL("CREATE INDEX chunks_doc ON chunks(doc_id)")
        createFts(db)
    }

    private fun createFts(db: SQLiteDatabase) {
        fts = try {
            db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts USING fts5(title, text, tokenize = 'porter unicode61')")
            true
        } catch (e: Exception) { Log.w(TAG, "FTS5 unavailable, using Kotlin BM25: ${e.message}"); false }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    private fun ftsOn(): Boolean = fts ?: run {
        val db = writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE name = 'fts'", null).use { it.moveToFirst() }
        if (!exists) createFts(db) else fts = true
        fts!!
    }

    override fun docHash(id: String): String? = readableDatabase.rawQuery("SELECT hash FROM docs WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }

    override fun upsert(doc: MemDoc, chunks: List<Chunk>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            removeIn(db, doc.id)
            db.insert("docs", null, ContentValues().apply {
                put("id", doc.id); put("source", doc.source.name); put("title", doc.title); put("at", doc.at); put("shield", doc.shield.name); put("ref", doc.ref); put("hash", doc.hash)
            })
            for (c in chunks) {
                val id = db.insert("chunks", null, ContentValues().apply { put("doc_id", doc.id); put("idx", c.index); put("text", c.text) })
                if (ftsOn()) db.execSQL("INSERT INTO fts(rowid, title, text) VALUES (?, ?, ?)", arrayOf<Any>(id, doc.title, c.text)) else fallback.put(id, doc.title, c.text)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun removeIn(db: SQLiteDatabase, id: String) {
        val ids = db.rawQuery("SELECT id FROM chunks WHERE doc_id = ?", arrayOf(id)).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        for (cid in ids) if (ftsOn()) db.execSQL("DELETE FROM fts WHERE rowid = ?", arrayOf<Any>(cid)) else fallback.remove(cid)
        db.delete("chunks", "doc_id = ?", arrayOf(id))
        db.delete("docs", "id = ?", arrayOf(id))
    }

    override fun remove(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try { removeIn(db, id); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    override fun ids(source: Source): Set<String> = readableDatabase.rawQuery("SELECT id FROM docs WHERE source = ?", arrayOf(source.name)).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    /** FTS5's bm25() is lower-is-better; returned negated so higher is better, like the Kotlin one. */
    override fun lexical(query: String, n: Int): List<Pair<Long, Double>> {
        val terms = Words.tokens(query).distinct()
        if (terms.isEmpty()) return emptyList()
        if (!ftsOn()) return fallback.search(query, n)
        val match = terms.joinToString(" OR ") { "\"" + it.replace("\"", "") + "\"*" }
        return try {
            readableDatabase.rawQuery("SELECT rowid, bm25(fts, 2.0, 1.0) AS s FROM fts WHERE fts MATCH ? ORDER BY s LIMIT $n", arrayOf(match)).use { c ->
                buildList { while (c.moveToNext()) add(c.getLong(0) to -c.getDouble(1)) }
            }
        } catch (e: Exception) { Log.w(TAG, "fts query failed: ${e.message}"); emptyList() }
    }

    override fun vectors(): List<Pair<Long, FloatArray>> = readableDatabase.rawQuery("SELECT id, vec FROM chunks WHERE vec IS NOT NULL", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getLong(0) to floats(c.getBlob(1))) }
    }

    private val chunkCols = "c.id, c.doc_id, c.idx, c.text, d.title, d.source, d.at, d.shield, d.ref"

    private fun row(c: android.database.Cursor) = StoredChunk(
        c.getLong(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4),
        runCatching { Source.valueOf(c.getString(5)) }.getOrDefault(Source.NOTE), c.getLong(6),
        runCatching { Shield.valueOf(c.getString(7)) }.getOrDefault(Shield.AEGIS), c.getString(8),
    )

    private fun allChunks(): List<StoredChunk> = readableDatabase.rawQuery("SELECT $chunkCols FROM chunks c JOIN docs d ON d.id = c.doc_id", null).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    override fun missingVectors(limit: Int): List<StoredChunk> =
        readableDatabase.rawQuery("SELECT $chunkCols FROM chunks c JOIN docs d ON d.id = c.doc_id WHERE c.vec IS NULL LIMIT $limit", null).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }

    override fun setVector(chunkId: Long, v: FloatArray) {
        writableDatabase.update("chunks", ContentValues().apply { put("vec", bytes(v)) }, "id = ?", arrayOf("$chunkId"))
    }

    override fun chunk(id: Long): StoredChunk? = readableDatabase.rawQuery("SELECT $chunkCols FROM chunks c JOIN docs d ON d.id = c.doc_id WHERE c.id = ?", arrayOf("$id")).use { if (it.moveToFirst()) row(it) else null }

    override fun counts(): Map<Source, Int> = readableDatabase.rawQuery("SELECT source, COUNT(*) FROM docs GROUP BY source", null).use { c ->
        buildMap { while (c.moveToNext()) runCatching { Source.valueOf(c.getString(0)) }.getOrNull()?.let { put(it, c.getInt(1)) } }
    }

    override fun chunkCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM chunks", null).use { it.moveToFirst(); it.getInt(0) }
    override fun vectorCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM chunks WHERE vec IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) }

    override fun docs(source: Source): List<MemDoc> = readableDatabase.rawQuery("SELECT id, title, at, shield, ref FROM docs WHERE source = ? ORDER BY at DESC", arrayOf(source.name)).use { c ->
        buildList { while (c.moveToNext()) add(MemDoc(c.getString(0), source, c.getString(1), "", c.getLong(2), runCatching { Shield.valueOf(c.getString(3)) }.getOrDefault(Shield.AEGIS), c.getString(4))) }
    }

    private fun bytes(v: FloatArray): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putFloat(it) } }.array()
    private fun floats(b: ByteArray): FloatArray = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().let { f -> FloatArray(f.remaining()).also { f.get(it) } }

    companion object { private const val TAG = "Zeus-Alexandria" }
}

/**
 * EmbeddingGemma 2 through MediaPipe's UniversalEmbedder, ported from PhoneRecall's Embedder.kt. The
 * model file is not shipped: it is loaded from the app's external files folder when the owner pushed it
 * there (embeddinggemma-2-740m.litertlm). Without it Alexandria is BM25 only. Loaded lazily, once,
 * and only off the main thread. MediaPipe runs it on the phone; nothing goes over the network.
 */
class GemmaEmbedder private constructor(private val inner: com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder) : Embedder {
    private var closed = false

    @Synchronized
    override fun embed(text: String): FloatArray? = if (closed) null else runCatching { inner.embedText(text.take(1_500)).embeddings().firstOrNull()?.floatEmbedding() }.getOrNull()

    @Synchronized fun close() { if (!closed) inner.close(); closed = true }

    companion object {
        const val FILE = "embeddinggemma-2-740m.litertlm"
        @Volatile private var shared: GemmaEmbedder? = null
        @Volatile private var failed = false
        @Volatile var status: String = "not loaded"; private set

        fun modelFile(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, FILE) }?.takeIf { it.isFile && it.length() > 10_000_000 }

        /** For Alexandria's page: absent, failed, or usable (not yet loaded counts as usable). */
        fun model(context: Context): EmbedModel = when {
            shared != null -> EmbedModel.READY
            modelFile(context) == null -> EmbedModel.MISSING
            failed -> EmbedModel.FAILED
            else -> EmbedModel.READY
        }

        /** The shared embedder, or null when the model file is absent or failed to load. */
        @Synchronized
        fun get(context: Context): GemmaEmbedder? {
            shared?.let { return it }
            if (failed) return null
            val f = modelFile(context) ?: run { status = "model not on the phone"; return null }
            fun make(d: com.google.mediapipe.tasks.core.Delegate) = runCatching {
                val options = com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedderOptions.builder()
                    .setBaseOptions(com.google.mediapipe.tasks.core.BaseOptions.builder().setModelAssetPath(f.absolutePath).setDelegate(d).build())
                    .setL2Normalize(true)
                    .setMaxInputLength(256)
                    .setTextDelegate(d)
                    .build()
                com.google.mediapipe.tasks.retrieval.universalembedder.UniversalEmbedder.createFromOptions(context, options)
            }.onFailure { Log.w("Zeus-Alexandria", "EmbeddingGemma on $d failed: ${it.message}") }.getOrNull()
            val e = make(com.google.mediapipe.tasks.core.Delegate.GPU) ?: make(com.google.mediapipe.tasks.core.Delegate.CPU)
            if (e == null) { failed = true; status = "model failed to load"; return null }
            status = "EmbeddingGemma 2 loaded"
            return GemmaEmbedder(e).also { shared = it }
        }
    }
}

/** Nano reorders the top candidates while Zeus is on screen; otherwise (or on any failure) the lexical reranker. */
class NanoReranker(private val local: LocalModel?, private val foreground: () -> Boolean) : Reranker {
    override fun rerank(query: String, c: List<Candidate>): List<Candidate> {
        val lexical = LexicalReranker.rerank(query, c)
        if (local == null || !foreground() || lexical.size < 3) return lexical
        val top = lexical.take(12)
        val prompt = buildString {
            append("Question: ").append(query.take(200)).append("\nRank these snippets by how well they answer it. Reply with the numbers only, best first, comma separated.\n")
            top.forEachIndexed { i, cand -> append("${i + 1}. ").append(cand.chunk.title.take(60)).append(": ").append(cand.chunk.text.replace(Regex("\\s+"), " ").take(220)).append('\n') }
        }
        val reply = runBlocking { withTimeoutOrNull(4_000L) { local.generate("rerank", prompt, 60, null) } } ?: return lexical
        val order = Regex("\\d+").findAll(reply).mapNotNull { it.value.toIntOrNull()?.minus(1) }.filter { it in top.indices }.distinct().toList()
        if (order.size < 2) return lexical
        val picked = order.map { top[it] }
        return picked + top.filter { it !in picked } + lexical.drop(12)
    }
}
