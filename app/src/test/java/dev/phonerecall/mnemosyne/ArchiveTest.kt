package dev.phonerecall.mnemosyne

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import dev.phonerecall.brain.Sealer
import dev.phonerecall.vault.Box
import dev.phonerecall.vault.ItemKind
import dev.phonerecall.vault.Envelope
import dev.phonerecall.vault.FakeKeys
import dev.phonerecall.vault.FakeUnwrapper
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A phone's Keystore in software: one AES-256-GCM key per alias, KeystoreSealer's format (IV in front). */
class SoftBank(private val names: List<String>) : SealerBank {
    private val keys = HashMap<String, SecretKey>()
    override fun aliases() = names
    override fun sealer(alias: String): Sealer = object : Sealer {
        val k = keys.getOrPut(alias) { KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() }
        override fun sealBytes(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.ENCRYPT_MODE, k); iv + doFinal(plain) }
        override fun openBytes(sealed: ByteArray): ByteArray? = try {
            Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, 12)); doFinal(sealed, 12, sealed.size - 12) }
        } catch (_: Exception) { null }
        override fun seal(plain: String) = Base64.getEncoder().encodeToString(sealBytes(plain.toByteArray()))
        override fun open(sealed: String) = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull()?.let { openBytes(it) }?.let { String(it) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ArchiveTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val layout = Layout(ctx)
    private val aliases = listOf("zeus.aegis.moneta", "zeus.aegis.places", "zeus.aegis.vault", "zeus.aegis.odyssey", "zeus.aegis.photos")
    private val oldPhone = SoftBank(aliases)
    private val newPhone = SoftBank(aliases)
    private val entropy = Phrase.newEntropy()
    private val oldBox = FakeKeys()
    private val newBox = FakeKeys()
    private lateinit var envA: Envelope
    private lateinit var envB: Envelope
    private var keyA = ByteArray(0)

    @Before fun stores() {
        // Plain settings, a sealed value, and the files that must never leave.
        ctx.getSharedPreferences("athena", Context.MODE_PRIVATE).edit().putBoolean("dark", true).putInt("n", 7).putLong("t", 1L shl 40).putFloat("f", 1.5f)
            .putStringSet("pins", setOf("a", "b")).putString("long plain", "x".repeat(60)).commit()
        ctx.getSharedPreferences("places", Context.MODE_PRIVATE).edit().putString("all", oldPhone.sealer("zeus.aegis.places").seal("""[{"name":"Wegmans"}]""")).putString("current", "p1").commit()
        ctx.getSharedPreferences("calypso", Context.MODE_PRIVATE).edit().putString("anthropic.ct", "sk-ant-secret").putString("spotify", "spot-tok-123").commit()
        ctx.getSharedPreferences("lemnos", Context.MODE_PRIVATE).edit().putBoolean("paired", true).commit()
        ctx.getSharedPreferences("vault", Context.MODE_PRIVATE).edit().putInt("pem.sent", 42).putString("other", "kept").commit()

        val m = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("moneta.db").apply { parentFile!!.mkdirs() }, null)
        m.execSQL("CREATE TABLE tx (id INTEGER PRIMARY KEY, cents INTEGER, merchant TEXT, note BLOB, rate REAL)")
        m.execSQL("CREATE INDEX tx_cents ON tx(cents)")
        m.execSQL("CREATE TABLE log (at INTEGER, what TEXT)")   // no INTEGER PRIMARY KEY: rowid is carried
        val s = oldPhone.sealer("zeus.aegis.moneta")
        for (i in 1..30) m.execSQL("INSERT INTO tx VALUES (?, ?, ?, ?, ?)", arrayOf<Any?>(i * 10, i * 100, s.seal("Merchant $i"), s.sealBytes("note $i".toByteArray()), i / 4.0))
        m.execSQL("INSERT INTO log(rowid, at, what) VALUES (77, 1, 'hello')")
        m.version = 2
        m.close()

        envA = Box.seal(oldBox.publicKey(), ItemKind.SECRET, "Bank PIN", "The owner", "1234".toByteArray(), id = "aaaaaaaaaaaa")
        envB = Box.seal(oldBox.publicKey(), ItemKind.NOTE, "Never opened", "Iris", "drop".toByteArray(), id = "bbbbbbbbbbbb")
        keyA = Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, oldBox.pair.private, Box.OAEP) }.doFinal(envA.wk)
        val v = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("vault.db"), null)
        v.execSQL("CREATE TABLE calypso (id TEXT PRIMARY KEY, kind TEXT NOT NULL, label TEXT NOT NULL, sender TEXT NOT NULL, at INTEGER NOT NULL, size INTEGER NOT NULL, wk BLOB NOT NULL, iv BLOB NOT NULL, ct BLOB NOT NULL)")
        v.execSQL("CREATE TABLE aegis (name TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val vs = oldPhone.sealer("zeus.aegis.vault")
        for (e in listOf(envA, envB)) v.execSQL("INSERT INTO calypso VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any?>(e.id, e.kind.wire, vs.seal(e.label), e.from, e.at, e.ct.size, e.wk, e.iv, e.ct))
        v.execSQL("INSERT INTO aegis VALUES ('github', ?)", arrayOf(vs.seal("ghp_value")))
        v.close()
        ctx.getDatabasePath("androidx.work.workdb").writeText("not ours")

        File(ctx.noBackupFilesDir, "odyssey.sealed").writeBytes(oldPhone.sealer("zeus.aegis.odyssey").sealBytes("""{"trips":3}""".toByteArray()))
        File(ctx.filesDir, "parnassus").mkdirs(); File(ctx.filesDir, "parnassus/p1.jpg").writeBytes(oldPhone.sealer("zeus.aegis.photos").sealBytes(ByteArray(5000) { 7 }))
        File(ctx.filesDir, "closet").mkdirs(); File(ctx.filesDir, "closet/shirt.webp").writeBytes(ByteArray(300) { 3 })
        File(ctx.filesDir, "embeddinggemma-2-740m.litertlm").writeBytes(ByteArray(10))
    }

    @After fun clean() { Box.keyTap = null }

    private fun backup(calypso: CalypsoEscrow?): Pair<ByteArray, ExportReport> {
        val out = ByteArrayOutputStream()
        lateinit var rep: ExportReport
        Zbk.writer(out, Keys.backupKey(entropy), "20261020T030000Z-00000001", 1L).use { w -> rep = Exporter(layout, oldPhone, calypso).export(w, mapOf("id" to "x")) }
        return out.toByteArray() to rep
    }

    /** The new phone: nothing of the old one survives except what the archive carries. */
    private fun wipe() {
        for (n in listOf("athena", "places", "calypso", "lemnos", "vault")) ctx.getSharedPreferences(n, Context.MODE_PRIVATE).edit().clear().commit()
        ctx.getDatabasePath("moneta.db").delete(); ctx.getDatabasePath("vault.db").delete()
        File(ctx.noBackupFilesDir, "odyssey.sealed").delete(); ctx.filesDir.resolve("parnassus").deleteRecursively(); ctx.filesDir.resolve("closet").deleteRecursively()
    }

    private fun restore(file: ByteArray, phrase: ByteArray = entropy): RestoreReport {
        val key = Keys.backupKey(phrase)
        val open = { Zbk.reader(ByteArrayInputStream(file), key).second }
        val r = Restorer(layout, newPhone, { newBox.publicKey() }, Keys.escrowKey(phrase))
        val v = r.verify(open)
        wipe()
        val rep = r.stage(open, v)
        assertTrue(Stage.pending(layout))
        assertTrue(Stage.apply(layout))
        assertFalse(layout.stage.exists())
        return rep
    }

    @Test fun everyStoreComesBackSealedUnderTheNewPhonesKeys() {
        val (file, rep) = backup(null)
        assertTrue(rep.parts.none { it.error != null })
        val r = restore(file)
        assertTrue(r.failed.toString(), r.failed.isEmpty())

        val a = ctx.getSharedPreferences("athena", Context.MODE_PRIVATE)
        assertEquals(true, a.getBoolean("dark", false)); assertEquals(7, a.getInt("n", 0)); assertEquals(1L shl 40, a.getLong("t", 0)); assertEquals(1.5f, a.getFloat("f", 0f))
        assertEquals(setOf("a", "b"), a.getStringSet("pins", null)); assertEquals("x".repeat(60), a.getString("long plain", null))

        val sealedPlace = ctx.getSharedPreferences("places", Context.MODE_PRIVATE).getString("all", null)!!
        assertNull(oldPhone.sealer("zeus.aegis.places").open(sealedPlace))                // the old phone's key no longer opens it
        assertEquals("""[{"name":"Wegmans"}]""", newPhone.sealer("zeus.aegis.places").open(sealedPlace))

        SQLiteDatabase.openDatabase(ctx.getDatabasePath("moneta.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT id, cents, merchant, note, rate FROM tx ORDER BY id", null).use { c ->
                var n = 0
                while (c.moveToNext()) {
                    n++
                    assertEquals(n * 10L, c.getLong(0)); assertEquals(n * 100L, c.getLong(1)); assertEquals(n / 4.0, c.getDouble(4), 0.0)
                    assertEquals("Merchant $n", newPhone.sealer("zeus.aegis.moneta").open(c.getString(2)))
                    assertEquals("note $n", String(newPhone.sealer("zeus.aegis.moneta").openBytes(c.getBlob(3))!!))
                }
                assertEquals(30, n)
            }
            db.rawQuery("SELECT rowid, what FROM log", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(77L, c.getLong(0)); assertEquals("hello", c.getString(1)) }
            db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'tx_cents'", null).use { assertTrue(it.moveToFirst()) }
        }
        assertEquals("""{"trips":3}""", String(newPhone.sealer("zeus.aegis.odyssey").openBytes(File(ctx.noBackupFilesDir, "odyssey.sealed").readBytes())!!))
        assertArrayEquals(ByteArray(5000) { 7 }, newPhone.sealer("zeus.aegis.photos").openBytes(File(ctx.filesDir, "parnassus/p1.jpg").readBytes()))
        assertArrayEquals(ByteArray(300) { 3 }, File(ctx.filesDir, "closet/shirt.webp").readBytes())
    }

    @Test fun tokensDeviceKeysAndPerPhoneStateNeverLeave() {
        val (file, rep) = backup(null)
        val entries = rep.parts.map { it.entry }
        assertFalse(entries.any { it.contains("calypso.json") || it.contains("lemnos") || it.contains("mnemosyne") || it.contains("androidx.work") || it.contains("litertlm") })
        // Nothing of the tokens is in the plaintext archive either.
        val plain = Zbk.reader(ByteArrayInputStream(file), Keys.backupKey(entropy)).second.readBytes()
        val text = String(java.util.zip.ZipInputStream(ByteArrayInputStream(plain)).let { z -> generateSequence { z.nextEntry }.map { z.readBytes() }.fold(ByteArray(0)) { a, b -> a + b } })
        for (s in listOf("sk-ant-secret", "spot-tok-123", "paired", "pem.sent")) assertFalse(s, text.contains(s))
        assertTrue(text.contains("Spotify sign-in"))     // the manifest says what was left out
        restore(file)
        assertFalse(ctx.getSharedPreferences("lemnos", Context.MODE_PRIVATE).contains("paired"))
        assertFalse(ctx.getSharedPreferences("calypso", Context.MODE_PRIVATE).contains("anthropic.ct"))
        val v = ctx.getSharedPreferences("vault", Context.MODE_PRIVATE)
        assertFalse(v.contains("pem.sent")); assertEquals("kept", v.getString("other", null))
    }

    @Test fun calypsoLeftOutByDefault() {
        val (file, rep) = backup(null)
        assertEquals(2, rep.calypsoLeftOut)
        val r = restore(file)
        assertEquals(0, r.calypso)
        SQLiteDatabase.openDatabase(ctx.getDatabasePath("vault.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM calypso", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.rawQuery("SELECT value FROM aegis", null).use { it.moveToFirst(); assertEquals("ghp_value", newPhone.sealer("zeus.aegis.vault").open(it.getString(0))) }
        }
    }

    @Test fun calypsoIncludedComesBackOpenableOnlyWithTheNewPhonesKey() {
        val pair = Escrow.newPair(Keys.escrowKey(entropy))
        // Only A was opened (or sealed in the app) while inclusion was on: only A is in the escrow.
        val (file, rep) = backup(CalypsoEscrow(pair, mapOf(envA.id to Escrow.wrap(Escrow.publicKey(pair.publicDer), keyA))))
        assertEquals(1, rep.calypsoIn); assertEquals(1, rep.calypsoLeftOut)
        val r = restore(file)
        assertEquals(1, r.calypso)
        SQLiteDatabase.openDatabase(ctx.getDatabasePath("vault.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT id, kind, label, sender, at, wk, iv, ct FROM calypso", null).use { c ->
                assertEquals(1, c.count); assertTrue(c.moveToFirst())
                val label = newPhone.sealer("zeus.aegis.vault").open(c.getString(2))!!
                val e = Envelope(c.getString(0), ItemKind.of(c.getString(1))!!, label, c.getString(3), c.getLong(4), c.getBlob(5), c.getBlob(6), c.getBlob(7))
                assertEquals("aaaaaaaaaaaa", e.id)
                assertThrows(Exception::class.java) { Box.open(e, FakeUnwrapper(oldBox.pair.private).apply { authorized = true }) }
                assertEquals("1234", Box.open(e, FakeUnwrapper(newBox.pair.private).apply { authorized = true }).text)
                assertThrows(IllegalStateException::class.java) { Box.open(e, FakeUnwrapper(newBox.pair.private)) }   // still behind the fingerprint
            }
        }
        // The escrow came along, so inclusion stays on and the next backup still carries the item.
        val sp = ctx.getSharedPreferences("mnemosyne", Context.MODE_PRIVATE)
        assertTrue(sp.getBoolean("calypso", false)); assertTrue(sp.contains("esc.item.${envA.id}"))
    }

    @Test fun escrowNeedsThePhrase() {
        val pair = Escrow.newPair(Keys.escrowKey(entropy))
        assertThrows(Exception::class.java) { Escrow.openPrivate(pair.sealedPrivate, Keys.escrowKey(Phrase.newEntropy())) }
        val wrapped = Escrow.wrap(Escrow.publicKey(pair.publicDer), keyA)
        assertArrayEquals(keyA, Escrow.unwrap(Escrow.openPrivate(pair.sealedPrivate, Keys.escrowKey(entropy)), wrapped))
    }

    @Test fun boxTapSeesItemKeysOnlyWhenSet() {
        val seen = HashMap<String, ByteArray>()
        val e0 = Box.seal(oldBox.publicKey(), ItemKind.NOTE, "x", "The owner", "y".toByteArray())
        assertTrue(seen.isEmpty())
        Box.keyTap = { id, k -> seen[id] = k.copyOf() }
        val e1 = Box.seal(oldBox.publicKey(), ItemKind.NOTE, "x", "The owner", "y".toByteArray())
        Box.open(e0, FakeUnwrapper(oldBox.pair.private).apply { authorized = true })
        assertEquals(setOf(e0.id, e1.id), seen.keys)
        val k1 = Cipher.getInstance(Box.RSA).apply { init(Cipher.DECRYPT_MODE, oldBox.pair.private, Box.OAEP) }.doFinal(e1.wk)
        assertArrayEquals(k1, seen[e1.id])
    }

    @Test fun manifestVersionIsChecked() {
        fun zipWith(manifest: String): ByteArray {
            val out = ByteArrayOutputStream()
            Zbk.writer(out, Keys.backupKey(entropy), "20261020T030000Z-00000002", 1L).use { w ->
                ZipOutputStream(w).apply { putNextEntry(ZipEntry("manifest.json")); write(manifest.toByteArray()); closeEntry(); finish() }
            }
            return out.toByteArray()
        }
        val r = Restorer(layout, newPhone, { newBox.publicKey() }, null)
        val key = Keys.backupKey(entropy)
        val newer = zipWith("""{"format":"zeus-mnemosyne","v":2}""")
        val e = assertThrows(Zbk.Tampered::class.java) { r.verify { Zbk.reader(ByteArrayInputStream(newer), key).second } }
        assertTrue(e.message!!.contains("newer"))
        val noIndex = zipWith("""{"format":"zeus-mnemosyne","v":1}""")
        assertThrows(Zbk.Tampered::class.java) { r.verify { Zbk.reader(ByteArrayInputStream(noIndex), key).second } }
    }

    @Test fun wrongPhraseChangesNothing() {
        val (file, _) = backup(null)
        val before = ctx.getSharedPreferences("athena", Context.MODE_PRIVATE).all
        assertThrows(Zbk.WrongKey::class.java) { restore(file, Phrase.newEntropy()) }
        assertEquals(before, ctx.getSharedPreferences("athena", Context.MODE_PRIVATE).all)
        assertFalse(Stage.pending(layout))
    }

    @Test fun idsSortByTime() {
        val a = Mnemosyne.newId(1_792_000_000_000L, SecureRandom())
        val b = Mnemosyne.newId(1_792_000_100_000L, SecureRandom())
        assertTrue(Regex("^\\d{8}T\\d{6}Z-[0-9a-f]{8}$").matches(a))
        assertTrue(a < b); assertNotEquals(a, b)
    }
}
