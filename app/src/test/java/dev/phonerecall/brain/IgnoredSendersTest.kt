package dev.phonerecall.brain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IgnoredSendersTest {
    @Test fun temuMailIsIgnored() {
        assertTrue(AppKinds.ignoredSender("Temu", "Your order shipped"))
        assertTrue(AppKinds.ignoredSender("Lightning deals", "Temu: 90% off"))
        assertFalse(AppKinds.ignoredSender("Chase", "Your statement is ready"))
        assertFalse(AppKinds.ignoredSender("Temukin Rao", "Lunch?"))
        assertTrue("com.einnovation.temu" in AppKinds.IGNORED)
    }
}
