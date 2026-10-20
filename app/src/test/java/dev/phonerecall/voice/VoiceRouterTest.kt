package dev.phonerecall.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRouterTest {
    private val open = LiveGate(keySet = true, confirmedToday = true, leftSeconds = 1800)
    private fun quick(text: String) = (VoiceRouter.route(text, open) as Route.Quick).command
    private fun propose(text: String) = quick(text) as QuickCommand.Propose

    @Test fun shortCommandsStayOnThePhone() {
        assertEquals(QuickCommand.Propose("set_timer", mapOf("seconds" to 600)), propose("Set a timer for 10 minutes"))
        assertEquals(QuickCommand.Propose("set_timer", mapOf("seconds" to 5400)), propose("timer for 1 hour 30 minutes please"))
        assertEquals(QuickCommand.Propose("set_timer", mapOf("seconds" to 300)), propose("Hey Athena, five minute timer"))
        assertEquals(QuickCommand.Propose("set_timer", mapOf("seconds" to 1800)), propose("set a timer for half an hour"))
        assertEquals(QuickCommand.Propose("set_alarm", mapOf("hour" to 6, "minute" to 30)), propose("set an alarm for 6:30 am"))
        assertEquals(QuickCommand.Propose("set_alarm", mapOf("hour" to 19, "minute" to 0)), propose("wake me up at 7 p.m."))
        assertEquals(QuickCommand.Propose("set_alarm", mapOf("hour" to 0, "minute" to 15)), propose("alarm at 12:15 am"))
        assertEquals(QuickCommand.Propose("music_control", mapOf("action" to "pause")), propose("Pause the music."))
        assertEquals(QuickCommand.Propose("music_control", mapOf("action" to "next")), propose("skip this song"))
        assertEquals(QuickCommand.Propose("music_control", mapOf("action" to "resume")), propose("play"))
        assertEquals(QuickCommand.Propose("play_music", mapOf("query" to "lofi beats", "name" to "lofi beats")), propose("play Lofi beats on Spotify"))
        assertEquals(QuickCommand.Propose("add_to_list", mapOf("section" to "Groceries", "items" to "milk, eggs, tomatoes")), propose("add milk, eggs and tomatoes to groceries"))
        assertEquals(QuickCommand.Propose("add_to_list", mapOf("section" to "Hardware Store", "items" to "wd-40")), propose("add WD-40 to my hardware store list"))
        assertEquals(QuickCommand.WhatsNext, quick("What's next?"))
        assertEquals(QuickCommand.WhatsNext, quick("what's on my calendar today"))
    }

    @Test fun notCommandsGoToTheBrain() {
        for (t in listOf("remind me to call mom", "add dentist to my calendar", "set an alarm for 25:00", "timer", "alarm at 13 pm", "who won the game", "")) {
            assertEquals(t, Route.Brain, VoiceRouter.route(t, open))
        }
    }

    @Test fun letsTalkAndLongQuestionsGoLive() {
        assertEquals(Route.Live, VoiceRouter.route("Let's talk", open))
        assertEquals(Route.Live, VoiceRouter.route("ok zeus, can we talk about my week", open))
        assertEquals(Route.Live, VoiceRouter.route("so I have this trip to Boston next month and I'm not sure whether to drive or take the train what do you think", open))
    }

    @Test fun liveAsksOnceADayAndRespectsTheCapAndTheKey() {
        assertEquals(Route.ConfirmLive, VoiceRouter.route("let's talk", open.copy(confirmedToday = false)))
        assertTrue(VoiceRouter.route("let's talk", open.copy(keySet = false)) is Route.LiveBlocked)
        assertTrue(VoiceRouter.route("let's talk", open.copy(leftSeconds = 0)) is Route.LiveBlocked)
        // A long question without Live goes to Delphi instead of failing.
        val long = "so I have this trip to Boston next month and I'm not sure whether to drive or take the train what do you think"
        assertEquals(Route.Brain, VoiceRouter.route(long, open.copy(keySet = false)))
        assertEquals(Route.Brain, VoiceRouter.route(long, open.copy(leftSeconds = 0)))
        // A command is still a command when Live is out of minutes.
        assertTrue(VoiceRouter.route("pause", open.copy(leftSeconds = 0)) is Route.Quick)
    }

    @Test fun nanoMayOnlyNameTheQuickTools() {
        assertEquals(QuickCommand.Propose("set_timer", mapOf("seconds" to 90)), QuickCommands.fromNano("""```json
{"tool":"set_timer","input":{"seconds":90}}
```"""))
        assertNull(QuickCommands.fromNano("""{"tool":"none"}"""))
        assertNull(QuickCommands.fromNano("""{"tool":"add_calendar_event","input":{"title":"x","start":"2026-10-20"}}"""))
        assertNull(QuickCommands.fromNano("""{"tool":"aegis_read","input":{"name":"bank"}}"""))
        assertNull(QuickCommands.fromNano("""{"tool":"set_timer","input":"600"}"""))
        assertNull(QuickCommands.fromNano("sure! here you go"))
        assertNull(QuickCommands.fromNano(null))
    }
}
