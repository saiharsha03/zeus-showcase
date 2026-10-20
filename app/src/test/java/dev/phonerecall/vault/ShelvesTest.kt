package dev.phonerecall.vault

import dev.phonerecall.Dest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ShelvesTest {
    @Test fun oldAegisDestinationsOpenShielded() {
        listOf("SHIELD", "AEGIS", "shielded").forEach { assertEquals(it, Shelf.SHIELDED, Shelf.forDest(it)) }
        listOf("VAULT", "CALYPSO", "Sealed").forEach { assertEquals(it, Shelf.SEALED, Shelf.forDest(it)) }
        listOf("HOME", "MONEY", "section:4").forEach { assertNull(it, Shelf.forDest(it)) }
    }

    @Test fun aegisIsNoLongerItsOwnDestination() {
        assertFalse(Dest.entries.any { it.name == "SHIELD" })
        assertEquals(Dest.VAULT.name, Dest.entries.single { Shelf.forDest(it.name) != null }.name)
    }

    @Test fun shelvesKeepTheStoredVaultNames() {
        assertEquals("calypso", Shelf.SEALED.vault)
        assertEquals("aegis", Shelf.SHIELDED.vault)
        assertEquals(Shelf.SHIELDED, Shelf.of(VaultLogRow(1, "aegis", "Athena", "used", "x", "")))
        assertEquals(Shelf.SEALED, Shelf.of(VaultLogRow(1, "calypso", "Iris", "deposited", "x", "")))
    }

    private fun row(at: Long, vault: String, action: String) = VaultLogRow(at, vault, "Athena", action, "item$at", "")

    @Test fun mergedLogIsNewestFirstAcrossShelves() {
        val sealed = listOf(row(50, "calypso", "opened"), row(30, "calypso", "deposited"), row(10, "calypso", "deposited"))
        val shielded = listOf(row(40, "aegis", "used"), row(20, "aegis", "refused"))
        assertEquals(listOf(50L, 40L, 30L, 20L, 10L), mergedLog(sealed, shielded).map { it.at })
    }

    @Test fun lockedShelfRowsStayOut() {
        val sealed = listOf(row(50, "calypso", "opened"))
        val shielded = listOf(row(40, "aegis", "used"))
        assertEquals(listOf("calypso"), mergedLog(sealed, null).map { it.vault }.distinct())
        assertEquals(listOf("aegis"), mergedLog(null, shielded).map { it.vault }.distinct())
        assertEquals(emptyList<VaultLogRow>(), mergedLog(null, null))
    }

    @Test fun equalTimesKeepEachShelfsOrder() {
        val sealed = listOf(row(5, "calypso", "opened").copy(item = "s1"), row(5, "calypso", "deposited").copy(item = "s2"))
        val shielded = listOf(row(5, "aegis", "used").copy(item = "a1"))
        assertEquals(listOf("s1", "s2", "a1"), mergedLog(sealed, shielded).map { it.item })
    }

    @Test fun movesSayShelves() {
        assertEquals("moved to Shielded", shelfAction("moved to Aegis"))
        assertEquals("moved to Sealed", shelfAction("moved to Calypso"))
        assertEquals("used", shelfAction("used"))
    }
}
