package dev.phonerecall.hermes

import android.provider.Settings
import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HermesTest {
    private val now = LocalDateTime.of(2026, 10, 20, 15, 0)
    private fun p(s: String) = Commands.parse(s, now)

    // ---- the parser ----

    @Test fun opensAppsAndSettings() {
        assertEquals(OpenCmd("spotify"), p("open spotify"))
        assertEquals(OpenCmd("wifi settings"), p("open wifi settings"))
        assertEquals(OpenCmd("Bluetooth"), p("Open Bluetooth"))
        assertEquals(OpenCmd("wifi", true), p("turn on wifi"))
        assertEquals(OpenCmd("hotspot", false), p("turn hotspot off"))
        assertEquals(OpenCmd("display settings"), p("display settings"))
    }

    @Test fun alarmsAtClockTimes() {
        assertEquals(AlarmCmd(6, 30), p("set alarm 6:30"))
        assertEquals(AlarmCmd(6, 0), p("wake me at 6"))
        assertEquals(AlarmCmd(18, 30), p("set an alarm for 6:30 pm"))
        assertEquals(AlarmCmd(7, 15), p("Hey Zeus, wake me up at 7.15 am"))
        assertEquals(AlarmCmd(15, 20), p("alarm in 20 minutes"))
    }

    @Test fun clockAndWhen() {
        assertEquals(LocalTime.of(6, 0), Commands.parseClock("6"))
        assertEquals(LocalTime.of(18, 30), Commands.parseClock("6:30 pm"))
        assertEquals(LocalTime.of(0, 0), Commands.parseClock("12 am"))
        assertEquals(LocalTime.NOON, Commands.parseClock("at noon"))
        assertNull(Commands.parseClock("13 pm"))
        assertEquals(now.plusMinutes(20), Commands.parseWhen("in 20 minutes", now)!!.first)
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0), Commands.parseWhen("tomorrow", now)!!.first)
        // A bare "5" said at 3 PM is this evening; "9 am" that has passed is tomorrow.
        assertEquals(LocalDateTime.of(2026, 10, 20, 17, 0), Commands.parseWhen("at 5", now)!!.first)
        assertEquals(LocalDateTime.of(2026, 10, 21, 9, 0), Commands.parseWhen("at 9 am", now)!!.first)
        assertNull(Commands.parseWhen("call home", now))
    }

    @Test fun timers() {
        assertEquals(TimerCmd(600), p("timer 10 min"))
        assertEquals(TimerCmd(5400), p("set a timer for 1 hour 30 minutes"))
        assertEquals(TimerCmd(1800), p("timer for half an hour"))
    }

    @Test fun reminders() {
        assertEquals(RemindCmd("call home", LocalDateTime.of(2026, 10, 20, 17, 0)), p("remind me at 5 to call home"))
        assertEquals(RemindCmd("take the pizza out", now.plusMinutes(20)), p("remind me to take the pizza out in 20 minutes"))
        assertEquals(RemindCmd("pay rent", null), p("remind me to pay rent"))
    }

    @Test fun deviceCommands() {
        assertEquals(TorchCmd(true), p("torch on"))
        assertEquals(TorchCmd(false), p("turn off the flashlight"))
        assertEquals(VolumeCmd("up"), p("volume up"))
        assertEquals(VolumeCmd("down"), p("volume down"))
        assertEquals(VolumeCmd("mute"), p("mute"))
        assertEquals(VolumeCmd("set", 50), p("volume 50%"))
        assertEquals(DndCmd(true), p("dnd on"))
        assertEquals(DndCmd(false), p("turn off do not disturb"))
        assertEquals(OpenCmd("volume"), p("open volume"))
    }

    @Test fun callsAndTexts() {
        assertEquals(CallCmd("mom", "auto"), p("call mom"))
        assertEquals(CallCmd("amma", "whatsapp"), p("call amma on whatsapp"))
        assertEquals(CallCmd("amma", "whatsapp_video"), p("video call amma"))
        assertEquals(CallCmd("dad", "phone"), p("phone call dad"))
        assertEquals(MessageCmd("mom", "sms", "running late"), p("text mom running late"))
        assertEquals(MessageCmd("Casey", "sms", "on my way"), p("text Casey saying on my way"))
        assertEquals(MessageCmd("amma", "whatsapp", "call me"), p("whatsapp amma call me"))
    }

    @Test fun playAgendaNavigate() {
        assertEquals(PlayCmd("lofi beats"), p("play lofi beats"))
        assertEquals(MusicCmd("pause"), p("pause the music"))
        assertEquals(MusicCmd("next"), p("skip"))
        assertEquals(AgendaCmd(LocalDate.of(2026, 10, 20)), p("what's on today"))
        assertEquals(AgendaCmd(LocalDate.of(2026, 10, 21)), p("what's on tomorrow?".trimEnd('?')))
        assertEquals(NavigateCmd("Wegmans"), p("navigate to Wegmans"))
    }

    @Test fun questionsAreNotCommands() {
        assertNull(p("what should I cook tonight"))
        assertNull(p("why is the sky blue"))
        assertNull(p("draft a reply to Casey about the deploy"))
        assertNull(p(""))
    }

    // ---- fuzzy app names ----

    private val apps = listOf("Spotify", "WhatsApp", "WhatsApp Business", "Microsoft Teams", "Settings", "Sports Center", "Gmail", "Google Maps")
    private fun open(q: String) = (Commands.pick(apps.map { Commands.Target(it, "App", Commands.matchScore(q, it), it) }) as? Commands.Pick.One)?.target?.label

    @Test fun fuzzyAppNames() {
        assertEquals("Spotify", open("spotify"))
        assertEquals("Spotify", open("spotfy"))
        assertEquals("WhatsApp", open("whatsap"))
        assertEquals("WhatsApp", open("whatsapp"))
        assertEquals("WhatsApp Business", open("whatsapp business"))
        assertEquals("Microsoft Teams", open("teams"))
        assertEquals("Google Maps", open("maps"))
        assertNull(open("zzqx"))
    }

    @Test fun closeNamesAsk() {
        val pick = Commands.pick(listOf("Google Maps", "Google Meet").map { Commands.Target(it, "App", Commands.matchScore("google", it), it) })
        assertTrue(pick is Commands.Pick.Several)
    }

    @Test fun settingsPanels() {
        assertEquals(Settings.Panel.ACTION_WIFI, Pages.of("wifi")!!.action)
        assertEquals(Settings.ACTION_WIFI_SETTINGS, Pages.of("wifi settings")!!.action)
        assertEquals(Settings.Panel.ACTION_INTERNET_CONNECTIVITY, Pages.of("internet")!!.action)
        assertEquals(Settings.Panel.ACTION_NFC, Pages.of("nfc")!!.action)
        assertEquals(Settings.Panel.ACTION_VOLUME, Pages.of("volume")!!.action)
        assertEquals(Settings.ACTION_BLUETOOTH_SETTINGS, Pages.of("bluetooth")!!.action)
        assertEquals(Settings.ACTION_DISPLAY_SETTINGS, Pages.of("display")!!.action)
        assertEquals(Settings.ACTION_BATTERY_SAVER_SETTINGS, Pages.of("battery")!!.action)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, Pages.of("location")!!.action)
        assertEquals("android.settings.TETHER_SETTINGS", Pages.of("hotspot")!!.action)
        assertNull(Pages.of("spotify"))
    }

    // ---- corrections ----

    @Test fun correctionsAreLearnedPerPhrase() {
        assertEquals(CorrectionCmd("Microsoft Teams"), p("no, I meant Microsoft Teams"))
        val (redo, teach) = Corrections.redo(OpenCmd("teams"), "Microsoft Teams")!!
        assertEquals(OpenCmd("Microsoft Teams"), redo)
        val book = Corrections.learn(emptyMap(), teach!!.first, teach.second, "Microsoft Teams")
        assertEquals("Microsoft Teams", Corrections.lookup(book, "app", "Teams"))
        assertNull(Corrections.lookup(book, "app", "steam"))
        assertNull(Corrections.lookup(book, "contact", "teams"))
    }

    @Test fun correctingTheWayKeepsThePerson() {
        val (redo, teach) = Corrections.redo(CallCmd("amma", "auto"), "whatsapp")!!
        assertEquals(CallCmd("amma", "whatsapp"), redo)
        assertNull(teach)
        assertEquals(CallCmd("Amma Doe", "auto") to ("contact" to "amma"), Corrections.redo(CallCmd("amma", "auto"), "Amma Doe"))
        assertNull(Corrections.redo(TorchCmd(true), "off"))
        assertNull(Corrections.redo(null, "spotify"))
    }

    // ---- execution policy ----

    @Test fun reversibleRunsAtOnceCallsAndTextsConfirm() {
        for (s in listOf("open spotify", "set alarm 6:30", "timer 10 min", "remind me at 5 to call home", "torch on", "volume up", "dnd on", "play lofi", "navigate to Wegmans")) {
            assertEquals(s, Policy.RUN, Commands.policy(p(s)!!))
        }
        assertEquals(Policy.CONFIRM, Commands.policy(p("call mom")!!))
        assertEquals(Policy.CONFIRM, Commands.policy(p("text mom running late")!!))
        assertEquals(Policy.READ, Commands.policy(p("what's on today")!!))
    }

    @Test fun clioGetsTheKindNotTheWords() {
        assertEquals("message", p("text mom running late")!!.kind)
        assertEquals("alarm", p("wake me at 6")!!.kind)
    }

    // ---- his own commands through Fates ----

    private var clock = java.time.ZonedDateTime.of(2026, 10, 20, 15, 0, 0, 0, java.time.ZoneId.of("America/New_York")).toInstant().toEpochMilli()
    private val store = MemoryStore()
    private val exec = FakeExecutor()
    private val audit = ArrayList<String>()
    private val fates = Fates(store, FakeState(), exec, { a, b, _ -> audit += "$a $b" }, { clock })

    @Test fun ownCommandRunsAtOnceAsHisAndUndoes() {
        val r = fates.own(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "6", "minute" to "30"))), null) as ProposeResult.Created
        val d = r.decision as Decision.Done
        assertEquals(1, exec.ran.size)
        assertEquals(ProposalStatus.CONFIRMED, d.proposal.status)
        assertEquals(false, d.proposal.auto)
        assertEquals("Hermes", d.proposal.proposer)
        assertTrue(audit.contains("The owner confirmed"))
        assertTrue(fates.undo(d.proposal.id) is Decision.Undone)
        assertEquals(1, exec.undone.size)
        assertEquals(ProposalStatus.UNDONE, store.get(d.proposal.id)!!.status)
    }

    @Test fun ownCommandIsStillValidated() {
        val r = fates.own(listOf(RawStep(ProposalKind.SET_ALARM, mapOf("hour" to "25"))), null)
        assertTrue(r is ProposeResult.Invalid)
        assertTrue(exec.ran.isEmpty())
    }

    @Test fun ownCommandsDoNotTeachTheFatigueMeter() {
        repeat(6) { fates.own(listOf(RawStep(ProposalKind.SET_TIMER, mapOf("seconds" to "60"))), null) }
        assertTrue(fates.stats().isEmpty() || fates.stats().values.all { it.first.confirms == 0 })
    }
}
