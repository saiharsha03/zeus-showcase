package dev.phonerecall.brain

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.phonerecall.MainActivity
import dev.phonerecall.R
import dev.phonerecall.calls.CallRouting
import dev.phonerecall.calls.Calls
import dev.phonerecall.data.BirthdaySource
import dev.phonerecall.data.CalItem
import dev.phonerecall.data.EventCategory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Android side of [BirthdayBook]: what it sees (his calendars, which carry Google Calendar's birthday events
 * through the phone's sync, Contacts, and WhatsApp wishes from the senses), what he already did ("Already wished",
 * a call or message from the card or the push, or a reply on her thread), and the one push per birthday.
 * Kept in its own small prefs file: ids ("sam@2026-10-20") and today's WhatsApp sightings, never message text.
 */
object BirthdayDay {
    const val CHANNEL = "birthdays"
    const val ACTION_WAKE = "dev.phonerecall.BIRTHDAY_WAKE"
    const val ACTION_WISHED = "dev.phonerecall.BIRTHDAY_WISHED"
    /** The message action: opens his WhatsApp chat with her (he writes it). Any other value is a CallRouting way. */
    const val CHAT = "chat"
    private const val DAY = 86_400_000L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("birthday_day", Context.MODE_PRIVATE)

    // ---- what he did ----

    fun done(c: Context): Set<String> = prefs(c).all.keys.filter { it.startsWith("done:") }.map { it.removePrefix("done:") }.toSet()
    private fun pushed(c: Context): Set<String> = prefs(c).all.keys.filter { it.startsWith("pushed:") }.map { it.removePrefix("pushed:") }.toSet()

    /** He wished (or said he did): the card, the brief line, the widget line and the push all go for that day. */
    fun markDone(c: Context, id: String) {
        prefs(c).edit().putLong("done:$id", System.currentTimeMillis()).apply()
        c.getSystemService(NotificationManager::class.java)?.cancel(notifId(id))
        dev.phonerecall.clio.Clio.info("chronos", "birthday wished")
        BrainCore.get(c).changed()
        arm(c)
    }

    /** Undo of a swipe: the birthday is open again. */
    fun unmarkDone(c: Context, id: String) {
        prefs(c).edit().remove("done:$id").apply()
        BrainCore.get(c).changed()
        arm(c)
    }

    /** The senses saw him answer on a thread (WhatsApp cleared it after a reply or an open): her birthday that day is done. */
    fun engaged(c: Context, thread: String, name: String?, at: Long = System.currentTimeMillis()) {
        val now = ZonedDateTime.now()
        val b = BirthdayBook.board(all(c, events(c, at), now), at, done(c))
        for (hit in BirthdayBook.wishedBy(b.active, thread, name)) markDone(c, hit.id)
    }

    // ---- what Zeus saw ----

    /** A birthday the senses caught in WhatsApp wishes. Dated on her clock, so an India wish lands on India's day. */
    fun seen(c: Context, name: String, thread: String, at: Long = System.currentTimeMillis()) {
        val india = india(c, name)
        val date = Instant.ofEpochMilli(at).atZone(if (india) BirthdayBook.IST else ZoneId.systemDefault()).toLocalDate()
        val line = listOf(name.replace('\t', ' '), "$date", if (india) "1" else "0", thread.replace('\t', ' ')).joinToString("\t")
        val p = prefs(c)
        val keep = p.getStringSet("whatsapp", emptySet()).orEmpty().filter { runCatching { LocalDate.parse(it.split('\t')[1]) }.getOrNull()?.isAfter(date.minusDays(3)) == true }
        p.edit().putStringSet("whatsapp", (keep + line).toSet()).apply()
        arm(c)
    }

    private fun whatsapp(c: Context): List<BirthdaySeen> = prefs(c).getStringSet("whatsapp", emptySet()).orEmpty().mapNotNull { l ->
        val f = l.split('\t')
        if (f.size < 4) null else runCatching { BirthdaySeen(f[0], LocalDate.parse(f[1]), BirthdayFrom.WHATSAPP, f[2] == "1", f[3]) }.getOrNull()
    }

    @Volatile private var indiaMemo: Pair<Long, MutableMap<String, Boolean>> = 0L to HashMap()

    /** India people: a +91 contact, a person whose zone is India, or family. Memoized for the day. */
    fun india(c: Context, name: String): Boolean {
        val day = LocalDate.now().toEpochDay()
        val memo = indiaMemo.takeIf { it.first == day }?.second ?: HashMap<String, Boolean>().also { indiaMemo = day to it }
        return memo.getOrPut(name.lowercase()) {
            val p = runCatching { BrainCore.get(c).store.person(People.keyOf(name)) }.getOrNull()
            p?.zone == TwoClocks.INDIA.id || p?.relation?.label == "family" || runCatching { Calls.countryOfName(c, name)?.first == "IN" }.getOrDefault(false)
        }
    }

    /** Every sighting near [now]: his curated calendar (contact birthdays come in with calendar id -1), Contacts again, and WhatsApp. */
    fun all(c: Context, events: List<CalItem>, now: ZonedDateTime): List<DayBirthday> {
        val zone = now.zone
        val today = now.toLocalDate()
        val days = (-1..1).map { today.plusDays(it.toLong()) }
        val seen = ArrayList<BirthdaySeen>()
        for (e in events) {
            if (e.category != EventCategory.BIRTHDAY || !e.allDay) continue
            val d = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
            if (d !in days) continue
            val name = BirthdayBook.nameOf(e.title)
            if (name.isEmpty()) continue
            seen += BirthdaySeen(name, d, if (e.calendarId == -1L) BirthdayFrom.CONTACTS else BirthdayFrom.CALENDAR, e.timeZone == BirthdayBook.IST.id || india(c, name))
        }
        for (b in runCatching { BirthdaySource(c).read() }.getOrDefault(emptyList())) for (d in days)
            if (b.month == d.monthValue && b.day == d.dayOfMonth) seen += BirthdaySeen(b.name, d, BirthdayFrom.CONTACTS, b.india || india(c, b.name))
        seen += whatsapp(c).filter { it.date in days }
        return BirthdayBook.merge(seen, zone)
    }

    /** For Olympus and the widget: today's birthdays from the curated calendar the board already has. */
    fun board(c: Context, events: List<CalItem>, now: ZonedDateTime): BirthdayBoard =
        BirthdayBook.board(all(c, events, now), now.toInstant().toEpochMilli(), done(c))

    /** The brief's lines: on now, or starting later today. */
    fun briefLines(c: Context, events: List<CalItem>, now: ZonedDateTime): List<String> {
        val ms = now.toInstant().toEpochMilli()
        return BirthdayBook.forBrief(all(c, events, now), now.zone, ms, done(c)).map { BirthdayBook.briefLine(it, now.zone, ms) }
    }

    /** Birthdays on [date] (her date) he hasn't wished, for the evening "set an alarm for midnight in India" offer. */
    fun on(c: Context, date: LocalDate): List<DayBirthday> {
        val at = date.atStartOfDay(ZoneId.systemDefault())
        val done = done(c)
        return all(c, events(c, at.toInstant().toEpochMilli()), at).filter { it.date == date && it.id !in done }
    }

    private fun events(c: Context, at: Long): List<CalItem> =
        runCatching { dev.phonerecall.mind.BackgroundReads(c, BrainCore.get(c)).relevantEvents(at - 2 * DAY, at + 2 * DAY) }.getOrDefault(emptyList())

    // ---- the one push ----

    private fun notifId(id: String) = 48_000 + (id.hashCode() and 0x3ff)

    @Volatile private var armedAt = 0L

    /** At most every ten minutes, off the main thread: for the board, which recomputes every minute. */
    fun armSoon(c: Context) {
        val now = System.currentTimeMillis()
        if (now - armedAt < 10 * 60_000L) return
        armedAt = now
        Thread { runCatching { arm(c) } }.start()
    }

    /** Posts the pushes that are due and sets the one alarm for the next. */
    fun arm(c: Context, nowMs: Long = System.currentTimeMillis()) {
        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault())
        val all = all(c, events(c, nowMs), now)
        val (post, next) = BirthdayBook.due(all, now.zone, nowMs, done(c), pushed(c))
        for (b in post) {
            prefs(c).edit().putLong("pushed:${b.id}", nowMs).apply()
            post(c, b, now.zone)
        }
        // Old ids go after three days.
        val old = prefs(c).all.filter { (k, v) -> (k.startsWith("done:") || k.startsWith("pushed:")) && (v as? Long ?: 0L) < nowMs - 3 * DAY }.keys
        if (old.isNotEmpty()) prefs(c).edit().apply { old.forEach { remove(it) } }.apply()
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(c, 48_999, Intent(c, BirthdayReceiver::class.java).setAction(ACTION_WAKE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (next == null) { am.cancel(pi); return }
        if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
    }

    /** The ways the card and the push offer: a WhatsApp video call for India, a phone call otherwise, then the chat. */
    fun callWay(b: DayBirthday): String = if (b.india) CallRouting.VIDEO else CallRouting.PHONE
    fun callLabel(b: DayBirthday): String = if (b.india) "Video call" else "Call"

    private fun post(c: Context, b: DayBirthday, home: ZoneId) {
        if (!AndroidLocalState.granted(c, Manifest.permission.POST_NOTIFICATIONS)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Birthdays", NotificationManager.IMPORTANCE_HIGH))
        fun act(via: String, code: Int) = PendingIntent.getActivity(
            c, notifId(b.id) + code, Intent(c, BirthdayActionActivity::class.java).putExtra("id", b.id).putExtra("name", b.name).putExtra("via", via).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val wished = PendingIntent.getBroadcast(
            c, notifId(b.id) + 3000, Intent(c, BirthdayReceiver::class.java).setAction(ACTION_WISHED).putExtra("id", b.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_owl)
            .setContentTitle(BirthdayBook.pushTitle(b))
            .setContentText(BirthdayBook.endsText(b, home))
            .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .addAction(0, callLabel(b), act(callWay(b), 1000))
            .addAction(0, "Message", act(CHAT, 2000))
            .addAction(0, "Already wished", wished)
            .build()
        nm.notify(notifId(b.id), n)
        dev.phonerecall.clio.Clio.info("chronos", "birthday push", b.name)
    }

    /** A call through Fates (a PLACE_CALL his tap confirms), or his WhatsApp chat with her. Null when it started, else why not. */
    fun reach(c: Context, id: String, name: String, via: String): String? {
        val why = if (via == CHAT) Calls.openChat(c, name) else {
            val brain = BrainCore.get(c)
            when (val r = brain.fates.propose(listOf(RawStep(ProposalKind.PLACE_CALL, mapOf("who" to name, "via" to via))), null, null, "Athena")) {
                is ProposeResult.Invalid -> r.reason.substringAfter(": ")
                is ProposeResult.Created -> when (val d = brain.fates.confirm(r.proposal.id, brain.fates.bind(r.proposal))) {
                    is Decision.Done -> null
                    is Decision.Failed -> d.reason
                    is Decision.Refused -> d.reason
                    else -> "Not done"
                }
            }
        }
        if (why == null) markDone(c, id)
        return why
    }
}

/** The push's call and message buttons. No UI: the tap is his confirm; an activity may start WhatsApp. */
class BirthdayActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("id"); val name = intent.getStringExtra("name"); val via = intent.getStringExtra("via")
        if (id != null && name != null && via != null) BirthdayDay.reach(this, id, name, via)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        finish()
    }
}

class BirthdayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                when (intent.action) {
                    BirthdayDay.ACTION_WISHED -> intent.getStringExtra("id")?.let { BirthdayDay.markDone(app, it) }
                    else -> BirthdayDay.arm(app)
                }
            } finally { pending.finish() }
        }.start()
    }
}
