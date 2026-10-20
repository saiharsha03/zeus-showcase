package dev.phonerecall.moneta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sample alert texts in each bank's usual wording (made up: no real merchants' store ids or card numbers). */
class ParseTest {
    private val at = 1_792_000_000_000L
    private val chase = "com.chase.sig.android"
    private val bofa = "com.infonow.bofa"
    private val amex = "com.americanexpress.android.acctsvcs.us"
    private val capOne = "com.konylabs.capitalone"

    private fun p(pkg: String, title: String, text: String, app: String = "") = BankParser.parse(pkg, app, title, text, at)

    private fun check(t: Txn?, cents: Long, merchant: String, last4: String?, kind: TxnKind = TxnKind.SPEND) {
        requireNotNull(t)
        assertEquals(cents, t.cents)
        assertEquals(merchant, t.merchant)
        assertEquals(last4, t.last4)
        assertEquals(kind, t.kind)
    }

    @Test fun chase() {
        check(p(chase, "Chase Sapphire", "You made a $47.82 transaction with WHOLEFDS AMH 10234 on Oct 19, 2026 at 6:12 PM ET."), 4782, "Whole Foods", null)
        check(p(chase, "Transaction alert", "A charge of (\$USD) 23.45 at STARBUCKS STORE 0412 has been authorized on 10/19/2026 8:15:00 AM EDT on your card ending in 1234."), 2345, "Starbucks", "1234")
        check(p(chase, "Chase", "Your $1,250.00 Zelle® payment to John Smith was sent."), 125000, "John Smith", null, TxnKind.TRANSFER)
        check(p(chase, "Chase Freedom", "Your payment of $500.00 was received. Thank you."), 50000, "Card payment", null, TxnKind.PAYMENT)
        assertEquals(Category.GROCERIES, p(chase, "Chase", "You made a $47.82 transaction with WHOLEFDS AMH 10234")!!.category)
    }

    @Test fun bankOfAmerica() {
        check(p(bofa, "Bank of America", "A purchase of $54.21 at COSTCO WHSE #0123 was made on your credit card ending in 5678."), 5421, "Costco", "5678")
        check(p(bofa, "Bank of America", "Your debit card ending in 4455 was used for $20.00 at SHELL OIL 57444."), 2000, "Shell", "4455")
        check(p(bofa, "Refund", "A refund of $19.99 from AMAZON.COM was credited to your card ending in 5678."), 1999, "Amazon", "5678", TxnKind.REFUND)
        check(p(bofa, "Bank of America", "A direct deposit of $2,100.00 was made to your account ending in 9876."), 210000, "Deposit", "9876", TxnKind.INCOME)
    }

    @Test fun amex() {
        // Amex shows five digits; only the last four are kept.
        check(p(amex, "Large Purchase Approved", "Card ending in 41009 was charged $1,204.10 at DELTA AIR LINES."), 120410, "Delta", "1009")
        check(p(amex, "Purchase Alert", "A $45.23 charge at UBER EATS was made on your Card ending in 41009."), 4523, "Uber Eats", "1009")
        assertEquals(Category.TRAVEL, p(amex, "Large Purchase Approved", "Card ending in 41009 was charged $1,204.10 at DELTA AIR LINES.")!!.category)
    }

    @Test fun capitalOne() {
        check(p(capOne, "Capital One", "A transaction of $32.10 was made at TARGET 00012345 on your Venture card ending in 4321."), 3210, "Target", "4321")
        check(p(capOne, "Capital One", "Your purchase of $15.49 at NETFLIX.COM was approved on your Quicksilver card ...4321."), 1549, "Netflix", "4321")
        check(p(capOne, "Capital One", "A $82.00 transaction at SUNOCO 0363 was declined on your card ending in 4321."), 8200, "Sunoco", "4321", TxnKind.DECLINED)
    }

    @Test fun `codes, balances, offers and statements are not transactions`() {
        assertNull(p(chase, "Chase", "Your Chase verification code is 482913. Don't share it."))
        assertNull(p(bofa, "Bank of America", "Your available balance is $1,234.56 as of 10/19."))
        assertNull(p(amex, "Amex Offers", "Earn $10 back: spend $50 or more at Target by Nov 30. Limited time offer."))
        assertNull(p(capOne, "Capital One", "Your statement is ready to view."))
        assertNull(p("com.whatsapp", "Alex", "I paid $40 at the restaurant, send me half"))
    }

    @Test fun `a full card number is never kept`() {
        val t = p(chase, "Chase", "A charge of $12.00 at TIM HORTONS 4111 1111 1111 1111 was made on card 4111111111111111.")
        requireNotNull(t)
        assertEquals(1200, t.cents)
        assertTrue(t.toString(), !Regex("\\d{5,}").containsMatchIn(t.toString().replace(t.at.toString(), "")))
        assertTrue(t.last4 == null || t.last4!!.length == 4)
    }

    @Test fun `bank texts from SMS are read when they name the bank`() {
        check(BankParser.parse("com.google.android.apps.messaging", "Messages", "22000", "Chase: A $9.99 charge at SPOTIFY USA was made on card ending 1234", at), 999, "Spotify", "1234")
        assertNull(BankParser.parse("com.google.android.apps.messaging", "Messages", "Alex", "Paid you $20 for dinner", at))
        assertEquals(Via.SMS, BankParser.parse("com.google.android.apps.messaging", "Messages", "22000", "Chase: A $9.99 charge at SPOTIFY USA was made on card ending 1234", at)!!.via)
    }

    @Test fun `Google Wallet taps are purchases on the card's bank`() {
        val w = BankParser.parse("com.google.android.apps.walletnfcrel", "Google Wallet", "Starbucks", "$23.45 with Chase Visa •••• 1234", at)
        check(w, 2345, "Starbucks", "1234")
        assertEquals(listOf("Chase", Via.WALLET), listOf(w!!.bank, w.via))
        // No merchant and no bank named: still a purchase, on Google Wallet.
        val bare = BankParser.parse("com.google.android.apps.walletnfcrel", "Google Wallet", "Google Wallet", "$5.00 with Visa •••• 4321", at)!!
        assertEquals(listOf(BankParser.NO_MERCHANT, "Google Wallet", "4321"), listOf(bare.merchant, bare.bank, bare.last4))
        // The new and renamed bank apps are read; the authorized wording marks a charge pending.
        for (pkg in listOf("com.capitalone.mobile", "com.citibank.mobile.us", "com.discover.mobile", "com.americanexpress.android.acctsvcs.us")) assertEquals(pkg, Via.APP, BankApps.via(pkg))
        assertEquals("Capital One", BankApps.bank("com.capitalone.mobile", ""))
        assertTrue(p(chase, "Transaction alert", "A charge of (\$USD) 23.45 at STARBUCKS STORE 0412 has been authorized on your card ending in 1234.")!!.pending)
        assertTrue(!p(bofa, "Bank of America", "A purchase of $54.21 at COSTCO WHSE #0123 was made on your credit card ending in 5678.")!!.pending)
    }
}
