package dev.phonerecall.qol

import dev.phonerecall.moments.MomentContext
import dev.phonerecall.moments.MomentKind
import dev.phonerecall.moments.MomentRules
import dev.phonerecall.moments.MomentsPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Pickup reminders: ready notices, and near the store. */
class PickupsTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, day: Int = 20) = ZonedDateTime.of(2026, 10, day, h, 0, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun readyNoticesOnly() {
        assertEquals(Pickup("CVS", "Prescription", 5L), Pickups.parse("com.cvs.launchers.cvs", "CVS Pharmacy", "Your prescription is ready for pickup", "", 5L))
        assertEquals(Pickup("Amazon Locker", "Package", 5L), Pickups.parse("com.amazon.mShop.android.shopping", "Amazon", "Your package is in the locker. Pickup code 123456", "", 5L))
        assertNull(Pickups.parse("com.cvs.launchers.cvs", "CVS Pharmacy", "Thanks for picking up your prescription", "", 5L))
        assertNull(Pickups.parse("com.whatsapp", "WhatsApp", "Alex", "ready when you are", 5L))
    }

    @Test fun nearTheStoreItComesForward() {
        val p = Pickup("CVS", "Prescription", ms(at(9)))
        val away = Pickups.card(MomentContext(at(12), qol = QolSignals(pickups = listOf(p))))!!
        assertEquals("Prescription ready at CVS", away.content.title)
        assertEquals(10f, away.fit)
        val near = MomentRules.pick(MomentContext(at(12), qol = QolSignals(pickups = listOf(p), stayName = "CVS Pharmacy")), MomentsPrefs())!!
        assertEquals(MomentKind.PICKUP, near.kind)
        assertEquals("You're near CVS: prescription ready", near.content.title)
        assertEquals("google.navigation:q=CVS", near.opens["nav"])
        assertNull(Pickups.card(MomentContext(at(12, day = 28), qol = QolSignals(pickups = listOf(p)))))
    }
}
