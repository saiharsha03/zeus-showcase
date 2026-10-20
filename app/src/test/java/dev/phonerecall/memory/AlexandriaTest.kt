package dev.phonerecall.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/** A bag-of-words hashing embedder: deterministic, offline, good enough to exercise the vector path. */
object HashEmbedder : Embedder {
    override fun embed(text: String): FloatArray {
        val v = FloatArray(256)
        for (w in Words.tokens(text)) { v[(w.hashCode() and 0x7fffffff) % 256] += 1f; if (w.length > 4) v[(w.take(4).hashCode() and 0x7fffffff) % 256] += 0.5f }
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
        return FloatArray(v.size) { v[it] / n }
    }
}

class ChunkerTest {
    private val sentence = "The owner renewed the lease for the apartment near the train station after talking to the leasing office about the heater. "

    @Test fun chunksAreSentenceAlignedAndSizedWithOverlap() {
        val text = sentence.repeat(30)
        val chunks = Chunker.chunks(text)
        assertTrue(chunks.size > 4)
        for (c in chunks.dropLast(1)) {
            assertTrue("chunk of ${c.text.length}", c.text.length in 300..600)
            assertTrue("ends on a sentence: ${c.text.takeLast(20)}", c.text.endsWith("."))
        }
        // Each chunk starts with the tail of the one before it (~80 characters).
        for (i in 1 until chunks.size) {
            val tail = chunks[i - 1].text.takeLast(40)
            assertTrue(chunks[i].text.take(120).contains(tail.substringAfter(' ').take(20)))
        }
    }

    @Test fun shortTextIsOneChunkAndHugeSentencesSplitOnWords() {
        assertEquals(1, Chunker.chunks("Return the green scarf by Nov 17.").size)
        val long = (1..400).joinToString(" ") { "word$it" }
        val chunks = Chunker.chunks(long)
        assertTrue(chunks.all { it.text.length <= 600 })
        assertTrue(chunks.size >= 4)
    }
}

class RrfTest {
    @Test fun fusionRewardsAgreement() {
        val fused = Rrf.fuse(listOf(listOf(1L, 2L, 3L), listOf(3L, 1L, 4L)))
        assertEquals(listOf(1L, 3L, 2L, 4L), fused.map { it.first })
        assertEquals(1.0 / 61 + 1.0 / 62, fused.first().second, 1e-12)
    }

    @Test fun oneListPassesThroughInOrder() {
        assertEquals(listOf(5L, 6L), Rrf.fuse(listOf(listOf(5L, 6L))).map { it.first })
    }
}

/**
 * A small golden set of his kind of questions over a fake corpus. Recall@3 must stay at or above the
 * bar, with BM25 alone and with the hybrid path (hash embedder), so a change that hurts retrieval fails here.
 */
class GoldenSetTest {
    private val docs = listOf(
        MemDoc("doc:lease", Source.DOCUMENT, "Lease agreement 2026", "This residential lease begins September 1, 2026. The lease ends on August 31, 2027. Rent is due on the first of each month. The tenant must give 60 days notice before moving out. The heater is maintained by the landlord.", 1),
        MemDoc("item:jordan", Source.NOTE, "Notes: Jordan", "Jordan is my roommate since early October. Splits the internet bill.", 2),
        MemDoc("task:scarf", Source.TASK, "Return the green scarf", "Return the green scarf. Next. Due Tue, Nov 17, 2026", 3),
        MemDoc("cal:messi", Source.CALENDAR, "Inter Miami at New York Red Bulls", "Inter Miami at New York Red Bulls. Sat, Nov 7, 2026 7:30 PM. Football", 4),
        MemDoc("closet:jacket", Source.CLOSET, "Navy rain jacket", "Navy rain jacket. Lightweight shell for rain, packs into its pocket. Outerwear. office, travel", 5),
        MemDoc("cap:tires", Source.PARNASSUS, "research winter tires", "research winter tires for the Camry before the first snow", 6),
        MemDoc("note:pkg", Source.NOTIFICATION, "Amazon notification", "delivery | app Amazon | says \"Your package was delivered to the front desk\"", 7, shield = Shield.AEGIS),
        MemDoc("item:groceries", Source.NOTE, "Groceries: parmesan", "Parmesan. The block, not grated", 8),
        MemDoc("doc:wifi", Source.DOCUMENT, "Apartment welcome sheet", "Welcome to Maple Court. Trash goes out on Tuesday nights. Quiet hours are after 11 PM. Laundry is in the basement.", 9),
        MemDoc("cal:taylor", Source.CALENDAR, "1:1 with Taylor", "1:1 with Taylor. Mon, Oct 26, 2026 10:00 AM. Work", 10),
        MemDoc("item:foliage", Source.NOTE, "Ideas: fall foliage drive", "Fall foliage drive to Allegheny before early November when the season ends", 11),
        MemDoc("doc:car", Source.DOCUMENT, "Car lease summary", "Vehicle lease summary. Payments autopay around the 15th. The lease term is 36 months and ends in June 2029.", 12),
    )

    private val golden = listOf(
        "when does my lease end" to "doc:lease",
        "who's Jordan" to "item:jordan",
        "when do I need to return the scarf" to "task:scarf",
        "when is the Messi game" to "cal:messi",
        "do I have a rain jacket" to "closet:jacket",
        "what did I want to research about tires" to "cap:tires",
        "did my package arrive" to "note:pkg",
        "what day does trash go out" to "doc:wifi",
        "when is my next meeting with Taylor" to "cal:taylor",
        "car lease end date" to "doc:car",
    )

    private fun recall(embedder: Embedder?, k: Int = 3): Double {
        val a = Alexandria(InMemoryIndex()) { embedder }
        docs.forEach { a.put(it) }
        a.embedPending(1_000)
        val hits = golden.count { (q, want) -> a.search(q, k).any { it.docId == want } }
        return hits.toDouble() / golden.size
    }

    @Test fun bm25AloneMeetsTheBar() { assertTrue("recall@3 ${recall(null)}", recall(null) >= 0.8) }

    @Test fun hybridMeetsTheBar() { assertTrue("recall@3 ${recall(HashEmbedder)}", recall(HashEmbedder) >= 0.8) }

    @Test fun theTopHitForTheLeaseIsTheLeaseWithACitation() {
        val a = Alexandria(InMemoryIndex()) { null }
        docs.forEach { a.put(it) }
        val top = a.search("when does my lease end").first()
        assertEquals("doc:lease", top.docId)
        assertEquals(1, top.n)
        assertTrue(top.snippet.contains("August 31, 2027"))
        val json = Alexandria.asToolJson(listOf(top), java.time.ZoneId.of("America/New_York"))
        assertTrue(json.contains("\"cite\":\"[1]\""))
    }

    @Test fun shieldedSnippetsStayHiddenOnScreenButAthenaCanUseThem() {
        val a = Alexandria(InMemoryIndex()) { null }
        docs.forEach { a.put(it) }
        val forPerson = a.search("did my package arrive", forAgent = false).first { it.docId == "note:pkg" }
        assertEquals("Shielded. Unlock to read.", forPerson.snippet)
        val forAthena = a.search("did my package arrive", forAgent = true).first { it.docId == "note:pkg" }
        assertTrue(forAthena.snippet.contains("delivered"))
    }

    @Test fun unchangedDocumentsAreNotReindexedAndGoneOnesAreRemoved() {
        val a = Alexandria(InMemoryIndex()) { null }
        assertEquals(12, docs.count { a.put(it) })
        assertEquals(0, docs.count { a.put(it) })
        a.sync(Source.DOCUMENT, docs.filter { it.source == Source.DOCUMENT && it.id != "doc:wifi" })
        assertTrue(a.search("trash Tuesday").none { it.docId == "doc:wifi" })
    }
}
