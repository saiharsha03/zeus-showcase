package dev.phonerecall.qol2

import dev.phonerecall.brain.QuietHours
import dev.phonerecall.home.ActionRole
import dev.phonerecall.home.CardAction
import dev.phonerecall.home.CardContent
import dev.phonerecall.home.CardEngine
import dev.phonerecall.home.Fact
import dev.phonerecall.home.Fmt
import dev.phonerecall.home.LiveInfo
import dev.phonerecall.moneta.Categories
import dev.phonerecall.moneta.Category
import dev.phonerecall.ui.Tone
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** The touches' rules. Pure: the same context and prefs give the same cards. */
object TouchRules {
    private const val MIN = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L
    private val DATE = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val HOUR_ONLY = DateTimeFormatter.ofPattern("h a", Locale.US)

    /** These may show in quiet hours (11 PM to 7 AM). */
    private val NIGHT = setOf(Touch.BEDTIME_MODE, Touch.QUICK_TIMER, Touch.LEAVE_NOTE, Touch.LAYOVER)
    /** These still show on a call, driving or gaming: his own timers and notes, and a connection to catch. */
    private val QUIET_OK = setOf(Touch.QUICK_TIMER, Touch.LEAVE_NOTE, Touch.LAYOVER)

    fun open(t: Touch, p: TouchPrefs, ctx: TouchContext): Boolean =
        t !in p.off && p.snoozed[t] != ctx.now.toLocalDate().toEpochDay() &&
            (!QuietHours.quiet(ctx.now) || t in NIGHT) && (!ctx.quiet || t in QUIET_OK)

    /** Every card that is open and not already handled, best first. */
    fun cards(ctx: TouchContext, p: TouchPrefs): List<TouchCard> = listOf(
        { tripPhotos(ctx) }, { fixCategory(ctx) }, { restock(ctx) }, { clearList(ctx) }, { receipt(ctx) }, { tip(ctx) },
        { cancelProof(ctx, p) }, { garage(ctx) }, { carRenewal(ctx, p) }, { carWash(ctx, p) }, { leaveNote(ctx, p) }, { layover(ctx) },
        { ticket(ctx) }, { hotDay(ctx) }, { walkWindow(ctx) }, { matchMode(ctx) }, { quickTimer(ctx, p) }, { bedtime(ctx) },
        { plugIn(ctx, p) }, { workFocus(ctx, p) }, { lunch(ctx) }, { resumeMusic(ctx, p) }, { borrowed(ctx, p) }, { readLater(ctx, p) },
        { dayFolder(ctx, p) }, { packet(ctx, p) }, { healthSync(ctx) }, { lightDay(ctx) }, { audioDrop(ctx) },
    ).mapNotNull { runCatching { it() }.getOrNull() }
        .filter { open(it.touch, p, ctx) && p.handled[it.touch] != it.key }
        .sortedByDescending { it.fit }

    // ---- helpers ----

    fun payload(t: Touch, key: String, option: String) = "${t.name}|$key|$option"
    private fun action(t: Touch, key: String, option: String, label: String) = CardAction(label, ActionRole.TOUCH, payload(t, key, option))
    private fun later(t: Touch, key: String) = action(t, key, "later", "Not now")
    private fun never(t: Touch, key: String) = action(t, key, "never", "Never")
    private fun ack(t: Touch, key: String, label: String = "Got it") = action(t, key, "ack", label)
    private val STD = mapOf("later" to TouchDo.Later, "never" to TouchDo.Never, "ack" to TouchDo.Ack)

    private fun ms(ctx: TouchContext) = ctx.now.toInstant().toEpochMilli()
    private fun at(ms: Long, ctx: TouchContext): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(ctx.now.zone)
    private fun time(ms: Long, ctx: TouchContext) = Fmt.time(ms, ctx.now)
    private fun today(ctx: TouchContext) = ctx.now.toLocalDate().toString()
    fun usd(cents: Long) = "$" + "%.2f".format(Locale.US, cents / 100.0)
    fun f(c: Double) = (c * 9 / 5 + 32).roundToInt()

    /** Rain, snow or storms by Open-Meteo's weather code, or measurable precipitation. */
    fun wet(h: Hour) = h.code in 51..67 || h.code in 71..77 || h.code in 80..86 || h.code >= 95 || h.precipMm >= 0.2

    /** Words shared by two names ("Fresh Mart Maple" and "FRESH MART #57"), ignoring short ones. */
    fun similar(a: String, b: String): Boolean {
        fun words(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
        val x = words(a); val y = words(b)
        return x.isNotEmpty() && y.isNotEmpty() && (x intersect y).isNotEmpty()
    }

    /** "BUF" from "Buffalo (BUF)", else the lower-cased name. */
    fun airport(s: String): String = Regex("\\(([A-Z]{3})\\)").find(s)?.groupValues?.get(1) ?: s.trim().lowercase()

    /** Free from now until the next timed event (or `within` minutes, whichever is sooner); null when an event is on now. */
    fun freeMinutes(ctx: TouchContext, within: Long): Long? {
        val now = ms(ctx)
        val timed = ctx.events.filter { !it.allDay && it.end > now }
        if (timed.any { it.begin <= now }) return null
        val next = timed.minOfOrNull { it.begin } ?: (now + within * MIN)
        return ((next - now) / MIN).coerceAtMost(within)
    }

    // ---- the touches ----

    fun tripPhotos(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        if (ctx.now.hour !in 9..21) return null
        val e = ctx.ended.filter { now - it.to in 0..3 * DAY }.maxByOrNull { it.to } ?: return null
        val t = Touch.TRIP_PHOTOS
        val range = at(e.from, ctx).let { s -> val end = at(e.to, ctx); if (s.toLocalDate() == end.toLocalDate()) DATE.format(s) else "${DATE.format(s)} to ${DATE.format(end)}" }
        return TouchCard(
            t, e.key,
            CardContent(
                title = "Send the ${e.title} photos to family?", body = "Pick from $range; the share sheet opens with them and you choose who.",
                actions = listOf(action(t, e.key, "pick", "Pick photos"), later(t, e.key), never(t, e.key)),
            ),
            fit = 14f, does = STD + ("pick" to TouchDo.PickPhotos(e.from, e.to)),
        )
    }

    /** Three likely categories: the merchant's words, the place it was at, then what he spends on most. */
    fun guesses(c: Charge, all: List<Charge>): List<Category> {
        val skip = setOf(Category.OTHER, Category.TRANSFERS)
        val usual = all.map { Category.of(it.category) }.filter { it !in skip }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (listOf(Categories.of(c.merchant), Categories.of(c.place.orEmpty())) + usual + listOf(Category.DINING, Category.GROCERIES, Category.SHOPPING, Category.FUN))
            .filter { it !in skip }.distinct().take(3)
    }

    fun fixCategory(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        val c = ctx.charges.filter { it.category == Category.OTHER.name && now - it.at in 0..10 * DAY }.maxByOrNull { it.at } ?: return null
        val t = Touch.FIX_CATEGORY
        val g = guesses(c, ctx.charges)
        return TouchCard(
            t, c.ref,
            CardContent(
                title = "What was ${c.merchant}?",
                facts = listOfNotNull(Fact("Amount", usd(c.cents)), Fact("When", "${DATE.format(at(c.at, ctx))}, ${time(c.at, ctx)}"), c.place?.let { Fact("At", it) }),
                actions = g.mapIndexed { i, cat -> action(t, c.ref, "c$i", cat.word) } + later(t, c.ref),
            ),
            fit = 12f, does = STD + g.mapIndexed { i, cat -> "c$i" to TouchDo.Categorize(c.ref, cat.name) },
        )
    }

    fun restock(ctx: TouchContext): TouchCard? {
        val s = ctx.staples.minByOrNull { it.last } ?: return null
        val t = Touch.RESTOCK
        val key = "${s.list}:${s.item}:${s.last}"
        return TouchCard(
            t, key,
            CardContent(
                title = "Add ${s.item} to the ${s.list} list?",
                body = "You buy it about every ${s.everyDays} days; the last was ${DATE.format(at(s.last, ctx))}.",
                actions = listOf(action(t, key, "add", "Add it"), later(t, key), never(t, key)),
            ),
            fit = 10f, does = STD + ("add" to TouchDo.AddToList(s.list, s.item)),
        )
    }

    fun clearList(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        val paid = setOf(Category.GROCERIES.name, Category.SHOPPING.name)
        val (v, c) = ctx.visits.filter { now - it.end in 0..2 * HOUR && it.open > 0 }.sortedByDescending { it.end }.firstNotNullOfOrNull { v ->
            ctx.charges.firstOrNull { it.at in (v.start - 5 * MIN)..(v.end + 20 * MIN) && (it.category in paid || similar(it.merchant, v.name)) }?.let { v to it }
        } ?: return null
        val t = Touch.CLEAR_LIST
        val key = "${v.list}:${v.end}"
        return TouchCard(
            t, key,
            CardContent(
                title = "Clear the ${v.list} list?",
                body = "You paid at ${c.merchant} at ${time(c.at, ctx)}. ${if (v.open == 1) "1 item is" else "${v.open} items are"} still on it.",
                actions = listOf(action(t, key, "clear", "Clear it"), later(t, key)),
            ),
            fit = 22f, does = STD + ("clear" to TouchDo.ClearList(v.list)),
        )
    }

    fun receipt(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        val c = ctx.charges.filter { it.category == Category.GROCERIES.name && now - it.at in 0..3 * HOUR }.maxByOrNull { it.at } ?: return null
        val t = Touch.RECEIPT_DRIVE
        return TouchCard(
            t, c.ref,
            CardContent(
                title = "Save the ${c.merchant} receipt?", body = "Pick the receipt photo; it goes to your Drive folder.",
                actions = listOf(action(t, c.ref, "save", "Save receipt"), later(t, c.ref), never(t, c.ref)),
            ),
            fit = 11f, does = STD + ("save" to TouchDo.SaveReceipt("${c.merchant} ${at(c.at, ctx).toLocalDate()}")),
        )
    }

    fun tip(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        val c = ctx.charges.filter { it.category == Category.DINING.name && now - it.at in 0..90 * MIN }.maxByOrNull { it.at } ?: return null
        val t = Touch.TIP
        val tips = listOf(15, 18, 20).associateWith { (c.cents * it / 100.0).roundToLong() }
        return TouchCard(
            t, c.ref,
            CardContent(
                title = "Tip at ${c.merchant}", lead = "18% = ${usd(tips.getValue(18))}",
                facts = listOf(Fact("Bill", usd(c.cents))) + tips.map { (p, v) -> Fact("$p%", "${usd(v)}, total ${usd(c.cents + v)}") },
                actions = tips.keys.map { action(t, c.ref, "t$it", "$it%") } + ack(t, c.ref, "Done"),
            ),
            fit = 24f, does = STD + tips.map { (p, v) -> "t$p" to TouchDo.Copy(usd(v).removePrefix("$")) },
        )
    }

    fun cancelProof(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val now = ms(ctx)
        val t = Touch.CANCEL_PROOF
        for (proof in p.proofs.sortedByDescending { it.at }) {
            val c = ctx.charges.filter { it.at > proof.at + DAY && similar(it.merchant, proof.service) }.maxByOrNull { it.at } ?: continue
            val key = "${proof.service}:${c.ref}"
            return TouchCard(
                t, key,
                CardContent(
                    title = "${proof.service} charged after you cancelled", tone = Tone.ALERT,
                    facts = listOfNotNull(
                        Fact("Cancelled", DATE.format(at(proof.at, ctx)) + if (proof.app.isNotEmpty()) " (${proof.app})" else ""),
                        Fact("Charged", "${DATE.format(at(c.at, ctx))}, ${usd(c.cents)}"),
                        proof.line.takeIf { it.isNotBlank() }?.let { Fact("Proof", it) },
                    ),
                    actions = listOf(action(t, key, "moneta", "Open Moneta"), ack(t, key)),
                ),
                fit = 26f, does = STD + ("moneta" to TouchDo.Go("moneta")),
            )
        }
        val fresh = p.proofs.filter { now - it.at in 0..DAY }.maxByOrNull { it.at } ?: return null
        val key = "saved:${fresh.service}:${fresh.at}"
        return TouchCard(
            t, key,
            CardContent(title = "Kept: your ${fresh.service} cancellation", body = "If ${fresh.service} charges you again, Zeus shows this proof with it.", actions = listOf(ack(t, key))),
            fit = 5f, does = STD,
        )
    }

    fun garage(ctx: TouchContext): TouchCard? {
        val since = ctx.staySince ?: return null
        val now = ms(ctx)
        if (!ctx.garage || ctx.atHome == true || ctx.atOffice == true || now - since !in 5 * MIN..12 * HOUR) return null
        val name = ctx.stayName ?: "the garage"
        val last = ctx.charges.filter { it.at < since && similar(it.merchant, name) }.maxByOrNull { it.at }
        val t = Touch.GARAGE
        val key = "$since"
        return TouchCard(
            t, key,
            CardContent(
                title = "Parked at $name",
                facts = listOfNotNull(Fact("In at", time(since, ctx)), Fact("So far", Fmt.minutes((now - since) / MIN)), last?.let { Fact("Last time", usd(it.cents)) }),
                actions = listOf(action(t, key, "pay", "Pay"), ack(t, key)),
            ),
            fit = 18f, does = STD + ("pay" to TouchDo.Open(PARKING_APPS)),
        )
    }

    /** ParkMobile, SpotHero, ParkWhiz, Passport: the first installed opens. */
    const val PARKING_APPS = "app:net.sharewire.parkmobilev2,app:com.spothero.spothero,app:com.parkwhiz.driverApp,app:com.passportparking.mobile"

    const val DMV_REGISTRATION = "https://dmv.ny.gov/registration/renew-registration"
    const val DMV_INSPECTION = "https://dmv.ny.gov/inspection"

    fun carRenewal(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val dates = buildMap {
            ctx.renewals.forEach { (k, v) -> put(k, v) }
            p.registration?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { put("registration", it) }
            p.inspection?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { put("inspection", it) }
        }
        val today = ctx.now.toLocalDate()
        val (kind, due) = dates.entries.filter { java.time.temporal.ChronoUnit.DAYS.between(today, it.value) in -14..30 }.minByOrNull { it.value }?.toPair() ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
        val t = Touch.CAR_RENEWAL
        val key = "$kind:$due"
        val what = if (kind == "inspection") "Inspection" else "Car registration"
        return TouchCard(
            t, key,
            CardContent(
                title = "$what ${if (days < 0) "was due" else "due"} ${DATE.format(due)}",
                body = if (kind == "inspection") "Find an inspection station on the NY DMV site." else "Renew online with the NY DMV.",
                status = when { days < 0 -> "Overdue"; days == 0L -> "Today"; else -> "In $days days" }, tone = if (days <= 7) Tone.ALERT else Tone.ACTIVE,
                actions = listOf(action(t, key, "dmv", "Open DMV"), ack(t, key, "Done"), later(t, key)),
            ),
            fit = 10f + (30 - days.coerceIn(0, 30)) / 2f, does = STD + ("dmv" to TouchDo.Open(if (kind == "inspection") DMV_INSPECTION else DMV_REGISTRATION)),
        )
    }

    fun carWash(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val now = ms(ctx)
        if (ctx.now.hour !in 9..16 || ctx.atOffice == true) return null
        if (now - (p.shownAt[Touch.CAR_WASH] ?: 0L) < 7 * DAY && p.handled[Touch.CAR_WASH]?.startsWith(today(ctx)) != true) return null
        val ahead = ctx.hours.filter { it.at in now - HOUR..now + 36 * HOUR }
        if (ahead.size < 24 || ahead.any(::wet)) return null
        val eveningEnd = ctx.now.toLocalDate().atTime(18, 0).atZone(ctx.now.zone).toInstant().toEpochMilli()
        val high = ahead.filter { it.at <= eveningEnd }.mapNotNull { it.tempC }.maxOrNull() ?: return null
        if (high !in 4.0..30.0) return null
        val free = freeMinutes(ctx, 240) ?: return null
        if (free < 60) return null
        val t = Touch.CAR_WASH
        val key = "${today(ctx)}:wash"
        val go = ctx.carWash?.let { action(t, key, "go", "Navigate") }
        return TouchCard(
            t, key,
            CardContent(
                title = "Car wash weather",
                body = "Dry through tomorrow, up to ${f(high)}°F. You're free ${if (free >= 240) "this afternoon" else "until ${time(now + free * MIN, ctx)}"}.",
                actions = listOfNotNull(go, ack(t, key), later(t, key)),
            ),
            fit = 7f, does = STD + listOfNotNull(ctx.carWash?.let { "go" to TouchDo.Open("geo:0,0?q=" + java.net.URLEncoder.encode(it, "UTF-8")) }),
        )
    }

    fun leaveNote(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val since = ctx.staySince
        val n = p.leave.filter { (since != null && it.stayStart == since) || (ctx.stayName != null && it.place.isNotEmpty() && it.place.equals(ctx.stayName, true)) }.maxByOrNull { it.at } ?: return null
        val t = Touch.LEAVE_NOTE
        return TouchCard(
            t, n.id,
            CardContent(title = "When you leave: ${n.text}", body = "Zeus reminds you as you leave ${n.place.ifEmpty { "here" }}.", actions = listOf(action(t, n.id, "drop", "Cancel it"))),
            fit = 9f, does = STD + ("drop" to TouchDo.DropNote(n.id)),
        )
    }

    fun layover(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        val legs = ctx.legs.filter { it.departAt > 0 && it.arriveAt > 0 }.sortedBy { it.departAt }
        for ((i, a) in legs.withIndex()) {
            if (now !in a.arriveAt..(a.arriveAt + 4 * HOUR)) continue
            val b = legs.drop(i + 1).firstOrNull { it.departAt > now && it.departAt - a.arriveAt <= 10 * HOUR && airport(it.from) == airport(a.to) } ?: continue
            val boards = b.departAt - 30 * MIN
            val left = ((boards - now) / MIN).coerceAtLeast(0)
            val t = Touch.LAYOVER
            val key = "${b.number}:${b.departAt}"
            val title = (if (b.gate.isNotBlank()) "${b.gate.replaceFirstChar { it.uppercase() }}, boards " else "Boards ") + if (left > 0) "in ${Fmt.minutes(left)}" else "now"
            return TouchCard(
                t, key,
                CardContent(
                    title = title,
                    facts = listOfNotNull(Fact("Flight", listOf(b.number, "to ${b.to}").filter { it.isNotBlank() }.joinToString(" ")), Fact("Departs", time(b.departAt, ctx)), if (b.gate.isBlank()) Fact("Gate", "Not posted yet") else null),
                    tone = Tone.ACTIVE, actions = listOf(ack(t, key)),
                    live = LiveInfo("Boards", title, "Departs ${time(b.departAt, ctx)}", countdownTo = boards, key = "touch:layover:$key"),
                ),
                fit = 50f, does = STD,
            )
        }
        return null
    }

    fun ticket(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        val e = ctx.events.filter { !it.allDay && it.links.isNotEmpty() && now in (it.begin - 45 * MIN)..(it.begin + 30 * MIN) }.minByOrNull { it.begin } ?: return null
        val t = Touch.TICKET
        return TouchCard(
            t, e.key,
            CardContent(
                title = "Your ticket for ${e.title}", body = "Starts at ${time(e.begin, ctx)}.",
                actions = listOf(action(t, e.key, "ticket", "Ticket"), action(t, e.key, "wallet", "Wallet"), ack(t, e.key)),
                live = LiveInfo("Ticket", "Ticket: ${e.title}", "Starts at ${time(e.begin, ctx)}", countdownTo = e.begin.takeIf { it > now }, key = "touch:ticket:${e.key}"),
            ),
            fit = 40f, does = STD + mapOf("ticket" to TouchDo.Open(e.links.first()), "wallet" to TouchDo.Open(WALLET)),
        )
    }

    const val WALLET = "app:com.google.android.apps.walletnfcrel"

    fun hotDay(ctx: TouchContext): TouchCard? {
        val dep = ctx.departure ?: return null
        val now = ms(ctx)
        val d = dep.toInstant().toEpochMilli()
        if (ctx.atHome != true || d - now !in 0..45 * MIN) return null
        val hot = ctx.hours.filter { it.at in (d - HOUR)..(d + 8 * HOUR) && (it.tempC ?: 0.0) >= 26.7 }.maxByOrNull { it.tempC ?: 0.0 } ?: return null
        val t = Touch.HOT_DAY
        val key = today(ctx)
        return TouchCard(
            t, key,
            CardContent(title = "Hot out: ${f(hot.tempC!!)}°F by ${HOUR_ONLY.format(at(hot.at, ctx))}", body = "Water and sunscreen before you go.", actions = listOf(ack(t, key))),
            fit = 16f, does = STD,
        )
    }

    fun walkWindow(ctx: TouchContext): TouchCard? {
        val now = ms(ctx)
        if (ctx.now.hour !in 8..18 || ctx.driving) return null
        val ahead = ctx.hours.filter { it.at >= now - HOUR }.sortedBy { it.at }
        val current = ahead.firstOrNull { it.at <= now && now < it.at + HOUR } ?: return null
        if (wet(current)) return null
        val rain = ahead.firstOrNull { it.at > now && wet(it) } ?: return null
        if (rain.at - now !in 45 * MIN..4 * HOUR) return null
        val free = freeMinutes(ctx, 90) ?: return null
        if (free < 30) return null
        val t = Touch.WALK_WINDOW
        val key = today(ctx)
        return TouchCard(
            t, key,
            CardContent(title = "Walk now, rain at ${HOUR_ONLY.format(at(rain.at, ctx))}", body = "You're free for the next ${Fmt.minutes(free)}.", actions = listOf(ack(t, key), later(t, key))),
            fit = 12f, does = STD,
        )
    }

    /** A followed match about to start (or in its first minutes). */
    fun match(ctx: TouchContext): Followed? {
        val now = ms(ctx)
        return ctx.matches.filter { !it.done && (it.start - now in 0..5 * MIN || (it.live && now - it.start in 0..10 * MIN)) }.minByOrNull { it.start }
    }

    fun matchMode(ctx: TouchContext): TouchCard? {
        val m = match(ctx) ?: return null
        val now = ms(ctx)
        val t = Touch.MATCH_MODE
        val mins = (m.start - now) / MIN
        return TouchCard(
            t, m.id,
            CardContent(
                title = if (mins > 0) "${m.title} in $mins min" else "${m.title} is on",
                body = "Zeus's own alerts are priority-only until full time.",
                actions = listOf(action(t, m.id, "watch", "Watch"), action(t, m.id, "off", "Alerts on"), ack(t, m.id)),
            ),
            fit = 45f, does = STD + mapOf("watch" to TouchDo.Go("olympia"), "off" to TouchDo.FocusOff),
        )
    }

    /** The focus Zeus should hold now (a match, the office), or null. Taps that turned it off today win. */
    fun focus(ctx: TouchContext, p: TouchPrefs): Focus? {
        val day = ctx.now.toLocalDate().toEpochDay()
        val m = match(ctx)
        if (m != null && Touch.MATCH_MODE !in p.off) {
            // "Alerts on" for this match: the marker stays while the match is in its first minutes.
            if (p.focus?.key == "off:${m.id}") return p.focus
            return Focus("match", m.start + 3 * HOUR, m.id)
        }
        p.focus?.takeIf { it.why == "match" && it.until > ms(ctx) && ctx.matches.none { m -> m.id == it.key && m.done } }?.let { return it }
        if (Touch.WORK_FOCUS !in p.off && ctx.atOffice == true && p.focusOffDay != day) {
            return Focus("office", ctx.now.toLocalDate().atTime(20, 0).atZone(ctx.now.zone).toInstant().toEpochMilli(), "office:${ctx.now.toLocalDate()}")
        }
        return null
    }

    fun quickTimer(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val now = ms(ctx)
        val t = Touch.QUICK_TIMER
        val run = p.running.filter { it.endsAt > now }.minByOrNull { it.endsAt }
        if (run != null) {
            val left = ((run.endsAt - now + MIN - 1) / MIN)
            return TouchCard(
                t, "run:${run.id}",
                CardContent(
                    title = "${run.label}: ${Fmt.minutes(left)} left", status = "Ends ${time(run.endsAt, ctx)}", tone = Tone.ACTIVE,
                    actions = listOf(action(t, "run:${run.id}", "more", "+5 min"), action(t, "run:${run.id}", "stop", "Stop")),
                    live = LiveInfo(CardEngine.chipOf(run.label), "${run.label} timer", "Ends at ${time(run.endsAt, ctx)}", countdownTo = run.endsAt, key = "touch:timer:${run.id}"),
                ),
                fit = 48f, does = mapOf("more" to TouchDo.Extend(run.id, 5), "stop" to TouchDo.StopTimer(run.id)),
            )
        }
        val saved = p.timers.firstOrNull()
        val options = listOf(5, 10, 30).map { "m$it" to TouchDo.Timer(it, "$it min") } + listOfNotNull(saved?.let { "saved" to TouchDo.Timer(it.minutes, it.label) })
        return TouchCard(
            t, "idle",
            CardContent(
                title = "Quick timer",
                actions = listOf(5, 10, 30).map { action(t, "idle", "m$it", "$it min") } + listOfNotNull(saved?.let { action(t, "idle", "saved", "${it.label} ${it.minutes}".take(14)) }),
            ),
            fit = 1f, does = options.toMap(),
        )
    }

    fun bedtime(ctx: TouchContext): TouchCard? {
        val alarm = ctx.nextAlarm ?: return null
        val now = ms(ctx)
        val h = ctx.now.hour
        if (!(h == 23 || (h == 22 && ctx.now.minute >= 30))) return null
        if (alarm.toInstant().toEpochMilli() - now !in 0..10 * HOUR || alarm.hour >= 7) return null
        val t = Touch.BEDTIME_MODE
        val key = today(ctx)
        return TouchCard(
            t, key,
            CardContent(
                title = "Early alarm at ${Fmt.time(alarm)}", body = "Bedtime mode turns on Do not disturb, with Undo. Your alarm still rings.",
                actions = listOf(action(t, key, "bed", "Bedtime mode"), action(t, key, "night", "Night light"), later(t, key)),
            ),
            fit = 26f, does = STD + mapOf("bed" to TouchDo.Bedtime, "night" to TouchDo.NightLight),
        )
    }

    fun plugIn(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val b = ctx.battery ?: return null
        val since = ctx.staySince ?: return null
        if (ctx.atHome != true || ctx.charging || b >= 30 || ms(ctx) - since !in 0..30 * MIN) return null
        val t = Touch.PLUG_IN
        val key = "$since"
        val plug = p.charger?.let { id -> ctx.plugs.firstOrNull { it.id == id } }
        val acts = buildList {
            if (plug != null && !plug.on) add(action(t, key, "plug", "Turn on ${plug.name}".take(14)))
            else if (plug == null && ctx.plugs.isNotEmpty()) add(action(t, key, "tag", "Charger plug?"))
            add(ack(t, key))
        }
        return TouchCard(
            t, key,
            CardContent(title = "Plug in: $b%", body = "You're home with a low battery.", status = "$b%", tone = Tone.ALERT, actions = acts),
            fit = 20f, does = STD + listOfNotNull(plug?.let { "plug" to TouchDo.Plug(it.id, it.name) }, "tag" to TouchDo.Go("touches")),
        )
    }

    fun workFocus(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val f = focus(ctx, p)?.takeIf { it.why == "office" } ?: return null
        val t = Touch.WORK_FOCUS
        return TouchCard(
            t, f.key,
            CardContent(
                title = "Office focus is on", body = "Zeus holds its non-urgent alerts until you leave the office. Urgent ones and calls still come.",
                actions = listOf(action(t, f.key, "off", "Turn off"), ack(t, f.key)),
            ),
            fit = 8f, does = STD + ("off" to TouchDo.FocusOff),
        )
    }

    fun lunch(ctx: TouchContext): TouchCard? {
        val at = ctx.lunchAt ?: return null
        if (!ctx.officeDay || ctx.atOffice != true) return null
        val m = ctx.now.hour * 60 + ctx.now.minute
        if (m !in (at - 20)..at) return null
        val t = Touch.WORK_FOCUS
        val key = "lunch:${today(ctx)}"
        val usual = Fmt.time(ctx.now.toLocalDate().atTime(at / 60, at % 60).atZone(ctx.now.zone))
        return TouchCard(t, key, CardContent(title = "Lunch out around $usual?", body = "You usually step out about now on office days.", actions = listOf(ack(t, key), later(t, key))), fit = 14f, does = STD)
    }

    fun resumeMusic(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        if (!ctx.innerScreen || !p.musicAtDriveEnd || ctx.musicNow || ms(ctx) - p.driveEndedAt !in 0..20 * MIN) return null
        val t = Touch.RESUME_MUSIC
        val key = "${p.driveEndedAt}"
        return TouchCard(
            t, key,
            CardContent(title = "Resume ${ctx.playlist}?", body = "Music was on in the car.", actions = listOf(action(t, key, "play", "Resume"), ack(t, key, "No"))),
            fit = 30f, does = STD + ("play" to TouchDo.Resume),
        )
    }

    /** "Jamie has my controller", "Jamie has my controller till Nov 2", "lent Alex my drill". */
    fun parseBorrowed(text: String, today: LocalDate, now: Long): Borrowed? {
        val s = text.trim()
        val due = Regex("(?i)\\s+(?:till|until|by|due)\\s+(.+)$").find(s)
        val head = (due?.let { s.substring(0, it.range.first) } ?: s).trim()
        val m = Regex("(?i)^(.+?)\\s+(?:has|borrowed|took)\\s+(?:my\\s+)?(.+)$").find(head)
            ?: Regex("(?i)^(?:lent|gave)\\s+(.+?)\\s+my\\s+(.+)$").find(head) ?: return null
        val date = due?.groupValues?.get(1)?.let { dateOf(it.trim(), today) }
        return Borrowed("b$now", m.groupValues[1].trim(), m.groupValues[2].trim().trimEnd('.'), date?.toString(), now)
    }

    /** "Nov 2", "November 2", "11/2", "2026-11-02", "tomorrow", "friday". Next occurrence on or after today. */
    fun dateOf(s: String, today: LocalDate): LocalDate? {
        val l = s.lowercase().trim().trimEnd('.')
        if (l == "today") return today
        if (l == "tomorrow") return today.plusDays(1)
        runCatching { return LocalDate.parse(l) }
        java.time.DayOfWeek.entries.firstOrNull { l.length >= 3 && it.name.lowercase().startsWith(l) }?.let { d ->
            var x = today.plusDays(1); while (x.dayOfWeek != d) x = x.plusDays(1); return x
        }
        Regex("^(\\d{1,2})/(\\d{1,2})$").find(l)?.let { m -> return roll(today, m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        Regex("^([a-z]{3,9})\\.?\\s+(\\d{1,2})$").find(l)?.let { m ->
            val month = java.time.Month.entries.firstOrNull { it.name.lowercase().startsWith(m.groupValues[1].take(3)) } ?: return null
            return roll(today, month.value, m.groupValues[2].toInt())
        }
        return null
    }

    private fun roll(today: LocalDate, month: Int, day: Int): LocalDate? = runCatching {
        val d = LocalDate.of(today.year, month, day); if (d.isBefore(today)) d.plusYears(1) else d
    }.getOrNull()

    fun borrowed(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val today = ctx.now.toLocalDate()
        val now = ms(ctx)
        val b = p.borrowed.filter { !it.returned }.filter { b ->
            val due = b.due?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (due != null) !due.isAfter(today) else now - b.since >= 14 * DAY && ((now - b.since) / DAY) % 7 == 0L
        }.minByOrNull { it.due ?: "9999" } ?: return null
        val t = Touch.BORROWED
        val key = "${b.id}:$today"
        return TouchCard(
            t, key,
            CardContent(
                title = "${b.who} has your ${b.what}",
                facts = listOfNotNull(Fact("Since", DATE.format(at(b.since, ctx))), b.due?.let { Fact("Due back", DATE.format(LocalDate.parse(it))) }),
                actions = listOf(action(t, key, "chat", "Open chat"), action(t, key, "back", "Returned"), later(t, key)),
            ),
            fit = 12f, does = STD + mapOf("chat" to TouchDo.Open("app:com.whatsapp"), "back" to TouchDo.Returned(b.id)),
        )
    }

    /** Hours of the week (0..167) he reads: his three most frequent once he has opened five, else evenings and weekend mornings. */
    fun leisure(p: TouchPrefs): Set<Int> {
        if (p.readHours.size >= 5) {
            val top = p.readHours.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
            return top.flatMap { listOf((it + 167) % 168, it) }.toSet()
        }
        return (0 until 7).flatMap { d -> (if (d >= 5) listOf(10, 11, 20, 21) else listOf(20, 21)).map { d * 24 + it } }.toSet()
    }

    fun readLater(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val unread = p.links.filter { it.openedAt == null }.sortedBy { it.savedAt }
        if (unread.isEmpty() || ctx.atOffice == true || ctx.driving) return null
        if (CardEngine.hourOfWeek(ctx.now) !in leisure(p)) return null
        val t = Touch.READ_LATER
        val key = "${today(ctx)}:${ctx.now.hour}"
        val pick = unread.take(2)
        return TouchCard(
            t, key,
            CardContent(
                title = if (unread.size == 1) "1 saved to read" else "${unread.size} saved to read",
                facts = pick.map { Fact(host(it.url), it.title.ifBlank { it.url }.take(80)) },
                actions = pick.mapIndexed { i, l -> action(t, key, "r$i", "Open ${host(l.url)}".take(14)) } + later(t, key),
            ),
            fit = 9f, does = STD + pick.mapIndexed { i, l -> "r$i" to TouchDo.ReadLink(l.id) },
        )
    }

    fun host(url: String): String = runCatching { java.net.URI(url).host?.removePrefix("www.")?.substringBefore('.') }.getOrNull()?.ifBlank { null } ?: "link"

    fun dayFolder(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        if (ctx.now.hour !in 6..21) return null
        val pins = p.pins.filter { it.date == today(ctx) }
        if (pins.isEmpty()) return null
        val t = Touch.DAY_FOLDER
        val key = today(ctx)
        return TouchCard(
            t, key,
            CardContent(
                title = "Today's folder", facts = pins.map { Fact("Pinned", it.label) },
                actions = pins.take(3).mapIndexed { i, pin -> action(t, key, "p$i", pin.label.take(14)) } + ack(t, key, "Done"),
            ),
            fit = if (ctx.now.hour in 6..10) 20f else 6f, does = STD + pins.take(3).mapIndexed { i, pin -> "p$i" to TouchDo.OpenPin(pin.uri) },
        )
    }

    fun packet(ctx: TouchContext, p: TouchPrefs): TouchCard? {
        val now = ms(ctx)
        val (e, pins) = ctx.events.filter { !it.allDay && now in (it.begin - 3 * HOUR)..it.end }.sortedBy { it.begin }
            .firstNotNullOfOrNull { e -> p.pins.filter { it.event == e.key }.takeIf { it.isNotEmpty() }?.let { e to it } } ?: return null
        val t = Touch.PACKET
        return TouchCard(
            t, e.key,
            CardContent(
                title = "${e.title} at ${time(e.begin, ctx)}", body = if (pins.size == 1) "1 file attached." else "${pins.size} files attached.",
                facts = pins.map { Fact("File", it.label) },
                actions = pins.take(3).mapIndexed { i, pin -> action(t, e.key, "p$i", pin.label.take(14)) },
            ),
            fit = 30f, does = STD + pins.take(3).mapIndexed { i, pin -> "p$i" to TouchDo.OpenPin(pin.uri) },
        )
    }

    fun healthSync(ctx: TouchContext): TouchCard? {
        val last = ctx.stepsSyncedAt ?: return null
        if (ctx.now.hour !in 10..21 || ms(ctx) - last < 6 * HOUR) return null
        val t = Touch.HEALTH_SYNC
        val key = today(ctx)
        val since = at(last, ctx).let { if (it.toLocalDate() == ctx.now.toLocalDate()) Fmt.time(it) else "${DATE.format(it)}, ${Fmt.time(it)}" }
        return TouchCard(
            t, key,
            CardContent(title = "Samsung Health hasn't synced since $since", body = "Open it so today's steps come through.", actions = listOf(action(t, key, "open", "Open it"), later(t, key))),
            fit = 8f, does = STD + ("open" to TouchDo.Open(SAMSUNG_HEALTH)),
        )
    }

    const val SAMSUNG_HEALTH = "app:com.sec.android.app.shealth"

    fun lightDay(ctx: TouchContext): TouchCard? {
        if (!ctx.shortNight || ctx.now.hour !in 6..11) return null
        val t = Touch.LIGHT_DAY
        val key = today(ctx)
        return TouchCard(t, key, CardContent(title = "Light day?", body = "A short night. Keep today easy if you can; nothing changes.", actions = listOf(ack(t, key))), fit = 10f, does = STD)
    }

    fun audioDrop(ctx: TouchContext): TouchCard? {
        val at = ctx.audioDropAt ?: return null
        if (ctx.musicNow || ms(ctx) - at !in 0..10 * MIN) return null
        val t = Touch.AUDIO_DROP
        val key = "$at"
        return TouchCard(
            t, key,
            CardContent(
                title = "Audio disconnected", body = "Music was playing. Reconnect, then pick it up again.",
                actions = listOf(action(t, key, "bt", "Bluetooth"), action(t, key, "music", "Music"), ack(t, key)),
            ),
            fit = 34f, does = STD + mapOf("bt" to TouchDo.Open(BLUETOOTH), "music" to TouchDo.Open("app:com.spotify.music")),
        )
    }

    const val BLUETOOTH = "settings:bluetooth"
}
