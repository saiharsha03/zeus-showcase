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

/** A package delivered while he was out, shown when he is home. */
class PackageTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 10, 20, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun deliveredNotificationsOnly() {
        assertEquals("Amazon", Deliveries.carrier("com.amazon.mShop.android.shopping", "Amazon Shopping", "Delivered: Your package", "Your package was delivered. It was handed to a resident."))
        assertEquals("UPS", Deliveries.carrier("com.google.android.gm", "Gmail", "UPS Update: Package Delivered", "Your package has been delivered at the front door"))
        assertNull(Deliveries.carrier("com.amazon.mShop.android.shopping", "Amazon Shopping", "Out for delivery", "Your package will be delivered today"))
        assertNull(Deliveries.carrier("com.ups.mobile.android", "UPS", "Delivery attempted", "We attempted to deliver your package"))
        assertNull(Deliveries.carrier("com.whatsapp", "WhatsApp", "Alex", "did it arrive?"))
    }

    private fun ctx(now: ZonedDateTime, home: Boolean?) = MomentContext(now, odysseyOn = true, atHome = home, qol = QolSignals(delivery = Delivery("Amazon", ms(at(14, 14)))))

    @Test fun cardOnArrivingHome() {
        val c = MomentRules.pick(ctx(at(18, 30), true), MomentsPrefs())!!
        assertEquals(MomentKind.PACKAGE, c.kind)
        assertEquals("Package at the door", c.content.title)
        assertEquals("Amazon, 2:14 PM.", c.content.body)
        assertNull(Deliveries.card(ctx(at(18, 30), false)))
        assertNull(Deliveries.card(ctx(at(18, 30), null)))
        assertNull(Deliveries.card(ctx(at(18, 30), true).let { it.copy(qol = it.qol.copy(busy = true)) }))
        assertNull(Deliveries.card(MomentContext(at(18, 30).plusDays(1), atHome = true, qol = QolSignals(delivery = Delivery("Amazon", ms(at(14, 14)))))))
    }
}
