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

/** The member card at the store. */
class MemberCardTest {
    private val now = ZonedDateTime.of(2026, 10, 20, 11, 0, 0, 0, ZoneId.of("America/New_York"))

    @Test fun theStoresAppOrTheWallet() {
        assertEquals(Member("Costco", "com.costco.app.android"), MemberCard.at("Costco Wholesale") { it == "com.costco.app.android" })
        assertEquals(Member("Costco", "com.samsung.android.spay"), MemberCard.at("Costco Wholesale") { it == "com.samsung.android.spay" })
        assertNull(MemberCard.at("Costco Wholesale") { false })
        assertNull(MemberCard.at("Oak Street Ave") { true })
        assertNull(MemberCard.at(null) { true })
    }

    @Test fun oneTapOpensIt() {
        val c = MomentRules.pick(MomentContext(now, qol = QolSignals(member = Member("Costco", "com.costco.app.android"))), MomentsPrefs())!!
        assertEquals(MomentKind.MEMBER_CARD, c.kind)
        assertEquals("At Costco", c.content.title)
        assertEquals("app:com.costco.app.android", c.opens["open"])
        assertNull(MemberCard.card(MomentContext(now, driving = true, qol = QolSignals(member = Member("Costco", "com.costco.app.android")))))
    }
}
