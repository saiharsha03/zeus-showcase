package dev.phonerecall.calls

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import dev.phonerecall.brain.ExecResult
import dev.phonerecall.rhythms.RhythmsStore

/** WhatsApp writes its own rows into each contact. Opening one with WhatsApp starts that action. */
const val WA_VOICE = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"
const val WA_VIDEO = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
const val WA_CHAT = "vnd.android.cursor.item/vnd.com.whatsapp.profile"

/**
 * Who a call proposal reaches, resolved on the phone. Only [name] (and the words the owner or the model used)
 * ever shows on a card; numbers and JIDs never leave this class except into the dialer or WhatsApp.
 */
data class CallTarget(
    val contactId: Long,
    val name: String,
    val video: Boolean,
    val voice: Boolean,
    val hasNumber: Boolean,
    /** What "call <name>" does by itself: [CallRouting.VIDEO] for India, [CallRouting.PHONE] otherwise. */
    val auto: String,
    val country: String,
    /** Their clock now, when their zone isn't his: "1:30 AM IST". */
    val there: String? = null,
    /** "usually up 6:30 AM–10:30 PM IST", learned from when their WhatsApp notifications arrive. */
    val awake: String? = null,
    val asleep: Boolean = false,
)

/**
 * Calls, ported from PhoneRecall (Actions.callWith, CallDefaults, Contacts). Every call Athena or Delphi
 * offers is a PLACE_CALL proposal; this only runs once Fates confirmed it (a tap on a notification
 * action counts as his confirm).
 */
object Calls {
    private data class Match(val id: Long, val name: String, val score: Float)

    private fun granted(c: Context, p: String) = c.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    fun canContacts(c: Context) = granted(c, Manifest.permission.READ_CONTACTS)

    /** The contact for [who] (or [contactId] when a nudge already knows it). Null when none or several match about equally. */
    fun target(c: Context, who: String, contactId: Long? = null): CallTarget? {
        if (!canContacts(c)) return null
        val m = contactId?.let { id -> displayName(c, id)?.let { Match(id, it, 1f) } } ?: clearWinner(findPeople(c, who)) ?: return null
        val numbers = phoneNumbers(c, m.id)
        val number = preferredNumber(c, m.id, numbers) ?: numbers.firstOrNull()
        val country = number?.let { countryOf(c, it, m.id) } ?: CallRouting.jidDigits(whatsappNumber(c, m.id))?.let { if (it.startsWith("91")) "IN" else null } ?: "OTHER"
        val d = RhythmsStore.load(c)
        val remembered = d.callVia["${m.id}"]
        val local = java.time.ZoneId.systemDefault()
        val zone = CallRouting.zoneFor(country, local)
        val now = System.currentTimeMillis()
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone)
        val w = d.seen[dev.phonerecall.brain.People.keyOf(m.name)]?.let { dev.phonerecall.rhythms.Awake.learn(it, zone, now) }
        val abbr = dev.phonerecall.rhythms.CallHabits.abbr(zone, t)
        return CallTarget(
            m.id, m.name, video = waRow(c, m.id, WA_VIDEO) != null, voice = waRow(c, m.id, WA_VOICE) != null, hasNumber = numbers.isNotEmpty(),
            auto = remembered ?: CallRouting.method(country), country = country,
            there = if (zone != local) dev.phonerecall.rhythms.CallHabits.clock(t) + " " + abbr else null,
            awake = w?.let { dev.phonerecall.rhythms.Awake.line(it, abbr) }, asleep = w != null && dev.phonerecall.rhythms.Awake.asleep(w, t),
        )
    }

    /** Places a confirmed call. Throws (so Fates records it) when nothing could start it. */
    fun place(c: Context, payload: Map<String, String>): ExecResult {
        val id = payload.getValue("contact_id").toLong()
        val name = payload["name"] ?: payload["who"].orEmpty()
        val via = payload.getValue("via")
        val intent = when (via) {
            CallRouting.VIDEO -> whatsappIntent(c, id, WA_VIDEO) ?: whatsappIntent(c, id, WA_VOICE) ?: error("$name isn't on WhatsApp in your contacts")
            CallRouting.VOICE -> whatsappIntent(c, id, WA_VOICE) ?: error("$name isn't on WhatsApp in your contacts")
            else -> {
                val numbers = phoneNumbers(c, id)
                val n = preferredNumber(c, id, numbers) ?: numbers.firstOrNull() ?: error("$name has no phone number")
                // No CALL_PHONE: the dialer opens with the number and he presses call.
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n)))
            }
        }
        if (runCatching { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) error(if (via == CallRouting.PHONE) "No phone app found" else "WhatsApp could not start the call")
        return ExecResult(when (via) { CallRouting.VIDEO -> "Video calling $name on WhatsApp"; CallRouting.VOICE -> "Calling $name on WhatsApp"; else -> "Dialing $name" })
    }

    /** Contacts that answer to [who], best first: ids and names only, for "Which Amma?". */
    fun matches(c: Context, who: String): List<Pair<Long, String>> =
        if (!canContacts(c)) emptyList() else findPeople(c, who).take(4).map { it.id to it.name }

    /**
     * Hermes's "text <name> <message>", after his tap: Messages with the text filled in, or the WhatsApp chat
     * with it. He presses send there. The number goes only into that intent.
     */
    fun message(c: Context, contactId: Long, name: String, whatsapp: Boolean, body: String?): String {
        val numbers = phoneNumbers(c, contactId)
        val n = preferredNumber(c, contactId, numbers) ?: numbers.firstOrNull()
        val intent = if (whatsapp) {
            val tm = c.getSystemService(TelephonyManager::class.java)
            val region = tm?.networkCountryIso.orEmpty().ifEmpty { tm?.simCountryIso.orEmpty() }.uppercase().ifEmpty { "US" }
            val digits = CallRouting.jidDigits(whatsappNumber(c, contactId)) ?: n?.let { PhoneNumberUtils.formatNumberToE164(it, region) }
                ?: error("$name isn't on WhatsApp in your contacts")
            Intent(Intent.ACTION_VIEW, Uri.parse(dev.phonerecall.alarms.Phones.whatsapp(digits, body))).setPackage("com.whatsapp")
        } else {
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(n ?: error("$name has no phone number")))).also { i -> body?.let { i.putExtra("sms_body", it) } }
        }
        if (runCatching { c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) error(if (whatsapp) "WhatsApp could not open the chat" else "No messaging app found")
        return if (whatsapp) "WhatsApp is open with $name" else "Messages is open for $name"
    }

    /** Opens his WhatsApp chat with [who]; he writes the message himself. Null when it opened, else why not. */
    fun openChat(c: Context, who: String): String? {
        val t = target(c, who) ?: return "No single contact named $who"
        val i = whatsappIntent(c, t.contactId, WA_CHAT) ?: return "${t.name} isn't on WhatsApp in your contacts"
        return if (runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) null else "WhatsApp could not open"
    }

    /** Remembers how he calls this person when he picks another way on a card or the nudge. */
    fun rememberVia(c: Context, contactId: Long, via: String) { RhythmsStore.update(c) { it.copy(callVia = it.callVia + ("$contactId" to via)) } }

    /** The number last picked for a contact with several; it is used first until another is picked. */
    fun preferredNumber(c: Context, contactId: Long, numbers: List<String>): String? {
        val saved = RhythmsStore.load(c).callNumber["$contactId"] ?: return null
        return numbers.firstOrNull { it.filter(Char::isDigit) == saved }
    }
    fun rememberNumber(c: Context, contactId: Long, number: String) { RhythmsStore.update(c) { it.copy(callNumber = it.callNumber + ("$contactId" to number.filter(Char::isDigit))) } }

    /** "IN", "US" or "OTHER", reading a bare 10-digit number through the contact's WhatsApp JID. */
    fun countryOf(c: Context, number: String, contactId: Long?): String {
        val tm = c.getSystemService(TelephonyManager::class.java)
        val region = (tm?.networkCountryIso.orEmpty().ifEmpty { tm?.simCountryIso.orEmpty() }).uppercase().ifEmpty { "US" }
        val wa = contactId?.let { CallRouting.jidDigits(whatsappNumber(c, it)) }
        return CallRouting.countryFor(number, wa, region, PhoneNumberUtils.formatNumberToE164(number, region)).first
    }

    /** The country for a contact saved under [name] (WhatsApp shows the saved name), or null without Contacts. */
    fun countryOfName(c: Context, name: String): Pair<String, Long>? {
        if (!canContacts(c)) return null
        val m = clearWinner(findPeople(c, name))?.takeIf { it.score >= 0.92f } ?: return null
        val n = phoneNumbers(c, m.id).firstOrNull()
        val country = n?.let { countryOf(c, it, m.id) } ?: CallRouting.jidDigits(whatsappNumber(c, m.id))?.let { if (it.startsWith("91")) "IN" else "OTHER" } ?: return null
        return country to m.id
    }

    // ---- contacts ----

    private fun findPeople(c: Context, who: String): List<Match> {
        val out = ArrayList<Match>()
        runCatching {
            c.contentResolver.query(ContactsContract.Contacts.CONTENT_URI, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    val name = cur.getString(1) ?: continue
                    val s = CallRouting.matchScore(who, name)
                    if (s >= 0.72f) out += Match(cur.getLong(0), name, s)
                }
            }
        }
        return out.sortedByDescending { it.score }.distinctBy { it.name.lowercase() }
    }

    private fun clearWinner(people: List<Match>): Match? {
        val best = people.firstOrNull() ?: return null
        val second = people.getOrNull(1)
        return best.takeIf { second == null || second.score < best.score - 0.05f }
    }

    private fun displayName(c: Context, id: Long): String? = runCatching {
        c.contentResolver.query(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id), arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun phoneNumbers(c: Context, contactId: Long): List<String> = runCatching {
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf("$contactId"), null,
        )?.use { cur -> buildList { while (cur.moveToNext()) cur.getString(0)?.let { add(it) } } }
            ?.distinctBy { n -> n.filter { it.isDigit() }.takeLast(10) }.orEmpty()
    }.getOrDefault(emptyList())

    private fun waRow(c: Context, contactId: Long, mime: String): Long? = runCatching {
        c.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data._ID),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?", arrayOf("$contactId", mime), null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()

    /** Not a public API, but present on most installs: VIEW on WhatsApp's own data row starts that call. */
    private fun whatsappIntent(c: Context, contactId: Long, mime: String): Intent? {
        val row = waRow(c, contactId, mime) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, row), mime).setPackage("com.whatsapp")
    }

    /** WhatsApp's own JID for the contact: the WhatsApp raw contact's SYNC1, else its data rows. */
    private fun whatsappNumber(c: Context, contactId: Long): String? = runCatching {
        c.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI, arrayOf(ContactsContract.RawContacts.SYNC1),
            "${ContactsContract.RawContacts.CONTACT_ID} = ? AND ${ContactsContract.RawContacts.ACCOUNT_TYPE} = 'com.whatsapp'", arrayOf("$contactId"), null,
        )?.use { cur -> generateSequence { if (cur.moveToNext()) cur.getString(0) else null }.firstOrNull { CallRouting.jidDigits(it) != null } }
    }.getOrNull() ?: runCatching {
        c.contentResolver.query(
            ContactsContract.Data.CONTENT_URI, arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} IN (?, ?, ?)", arrayOf("$contactId", WA_VOICE, WA_CHAT, WA_VIDEO), null,
        )?.use { cur -> generateSequence { if (cur.moveToNext()) cur.getString(0) else null }.firstOrNull { CallRouting.jidDigits(it) != null } }
    }.getOrNull()
}
