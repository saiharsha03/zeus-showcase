package dev.phonerecall.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** His transcript with Athena: sealed on disk, kept as one history, read back whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptSealTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun raw(db: BrainDb): List<String> =
        db.readableDatabase.rawQuery("SELECT text FROM chat_lines UNION ALL SELECT json FROM chat_params", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun linesAreSealedOnDiskAndReadBackPlain() {
        val db = BrainDb(app, FakeSealer())
        val chat = db.newChat()
        db.addLine(chat, LineKind.USER, "the blue jacket or the grey one?")
        db.addLine(chat, LineKind.ATHENA, "The grey one, it's cold out.")
        assertTrue(raw(db).none { "jacket" in it || "grey" in it })
        assertEquals(listOf("the blue jacket or the grey one?", "The grey one, it's cold out."), db.lines(chat).map { it.text })
    }

    @Test fun rowsWrittenBeforeAreSealedOnceAndStayReadable() {
        val db = BrainDb(app, FakeSealer())
        val chat = db.newChat()
        // As a version-2 database left them: plain text, not marked sealed.
        db.writableDatabase.execSQL("INSERT INTO chat_lines (chat_id, at, kind, text, sealed) VALUES (?, 1, 'USER', 'remind me about the dentist', 0)", arrayOf<Any>(chat))
        db.writableDatabase.execSQL("INSERT INTO chat_params (chat_id, seq, json, sealed) VALUES (?, 0, '{\"role\":\"user\",\"content\":\"dentist\"}', 0)", arrayOf<Any>(chat))
        assertEquals(2, db.sealPlain())
        assertEquals(0, db.sealPlain())
        assertTrue(raw(db).none { "dentist" in it })
        assertEquals("remind me about the dentist", db.lines(chat).single().text)
    }

    @Test fun theHistoryRunsAcrossChatsOldestFirst() {
        val db = BrainDb(app, FakeSealer())
        val a = db.newChat()
        db.addLine(a, LineKind.USER, "one")
        val b = db.newChat()
        db.addLine(b, LineKind.DIVIDER, "New session · Wed Oct 21")
        db.addLine(b, LineKind.USER, "two")
        db.addLine(b, LineKind.ATHENA, "three")
        assertEquals(listOf("one", "New session · Wed Oct 21", "two", "three"), db.history(10).map { it.second.text })
        assertEquals(listOf("two", "three"), db.history(2).map { it.second.text })
        assertEquals(listOf(a, b), db.history(10).map { it.first }.distinct())
        assertFalse(db.history(1, beforeId = db.history(10).first().second.id).isNotEmpty())
    }
}
