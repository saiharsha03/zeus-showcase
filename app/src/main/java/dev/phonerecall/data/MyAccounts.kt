package dev.phonerecall.data

import android.content.Context
import android.provider.CalendarContract

/**
 * A family member's mail and calendars may be signed in on the same phone. Only accounts whose address has "owner"
 * in it are his. Calendars on no email account (local, holidays) are his too. When ownership can't
 * be read, nothing is read: these fail closed.
 */
object MyAccounts {
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    private fun address(s: String?): String? = s?.let { EMAIL.find(it)?.value }

    fun isMine(address: String): Boolean = address.contains("owner", ignoreCase = true)

    /** True when the text names an email account that is not his. */
    fun notMine(account: String?): Boolean = address(account)?.let { !isMine(it) } ?: false

    /** Gmail names the account in the sub text when more than one is signed in: keep only his, drop when unnamed. */
    fun gmailMine(subText: String?): Boolean = address(subText)?.let(::isMine) ?: false

    /** His own Google account: new events go to its main calendar unless a proposal names another. */
    const val PRIMARY = "owner@example.com"

    /** The main calendar of [PRIMARY] (Google names it after the address), else any calendar on that account. */
    fun defaultCalendar(writable: List<dev.phonerecall.brain.CalendarChoice>): dev.phonerecall.brain.CalendarChoice? =
        writable.firstOrNull { it.account.equals(PRIMARY, true) && it.name.equals(PRIMARY, true) }
            ?: writable.firstOrNull { it.account.equals(PRIMARY, true) }

    /** Both WhatsApp accounts are his; the app tags each notification "[me]" or "[owner]". The tag is dropped. */
    private val WA_TAG = Regex("^\\s*\\[(me|owner)]\\s*", RegexOption.IGNORE_CASE)
    fun untag(s: String): String = s.replaceFirst(WA_TAG, "")

    /** His calendar ids, or null when the calendars couldn't be read. */
    fun myCalendars(c: Context): List<Long>? = runCatching {
        val out = ArrayList<Long>()
        c.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.ACCOUNT_NAME), null, null, null)?.use { q ->
            while (q.moveToNext()) if (!notMine(q.getString(1))) out += q.getLong(0)
        } ?: return@runCatching null
        out
    }.getOrNull()

    /** A selection for Instances queries: his calendars only. Matches nothing when they couldn't be read. */
    fun instancesWhere(c: Context): String {
        val ids = myCalendars(c)
        return if (ids.isNullOrEmpty()) "0" else ids.joinToString(",", "${CalendarContract.Instances.CALENDAR_ID} IN (", ")")
    }
}
