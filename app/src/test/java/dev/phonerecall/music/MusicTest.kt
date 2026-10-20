package dev.phonerecall.music

import dev.phonerecall.brain.Decision
import dev.phonerecall.brain.EngineTest
import dev.phonerecall.brain.FakeExecutor
import dev.phonerecall.brain.FakeState
import dev.phonerecall.brain.Fates
import dev.phonerecall.brain.MemoryStore
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.ProposalStatus
import dev.phonerecall.brain.ProposeResult
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.ToolBox
import dev.phonerecall.data.DayType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.URI
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZonedDateTime

class MusicTest {
    private class MemTokens : TokenStore {
        var tokens: Tokens? = null
        var pending: PendingAuth? = null
        override fun load() = tokens
        override fun save(t: Tokens) { tokens = t }
        override fun clear() { tokens = null; pending = null }
        override fun savePending(p: PendingAuth) { pending = p }
        override fun takePending() = pending.also { pending = null }
    }

    private class FakeToken(vararg replies: Pair<Int, String>) : FormPost {
        val calls = ArrayList<Pair<String, Map<String, String>>>()
        private val queue = ArrayDeque(replies.toList())
        override fun post(url: String, form: Map<String, String>): Pair<Int, String> { calls += url to form; return queue.removeFirst() }
    }

    private var now = 1_000_000L

    // ---- PKCE ----

    @Test fun challengeMatchesRfc7636() {
        // RFC 7636 appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun verifierIsLongRandomAndUrlSafe() {
        val a = Pkce.verifier(); val b = Pkce.verifier()
        assertTrue(a.length in 43..128)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(a))
        assertFalse(a == b)
        assertFalse(Pkce.challenge(a).contains('='))
    }

    @Test fun authorizeUrlAsksForS256AndOnlyTheNeededScopes() {
        val store = MemTokens()
        val url = SpotifyAuth(FakeToken(), store) { now }.begin(SecureRandom())
        val q = SpotifyAuth.query(URI(url))
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertEquals(SpotifyConfig.CLIENT_ID, q["client_id"])
        assertEquals("code", q["response_type"])
        assertEquals("zeus://spotify-callback", q["redirect_uri"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals(Pkce.challenge(store.pending!!.verifier), q["code_challenge"])
        assertEquals(store.pending!!.state, q["state"])
        assertEquals(SpotifyConfig.SCOPES.toSet(), q["scope"]!!.split(' ').toSet())
        assertFalse(q.containsKey("client_secret"))
    }

    // ---- callback ----

    private fun started(): Pair<SpotifyAuth, PendingAuth> {
        val store = MemTokens()
        val auth = SpotifyAuth(FakeToken(), store) { now }
        auth.begin()
        return auth to store.pending!!
    }

    @Test fun callbackWithTheRightStateGivesTheCodeAndVerifier() {
        val (auth, p) = started()
        val c = auth.check("zeus://spotify-callback?code=AQB123&state=${p.state}")
        assertEquals(Callback.Ok("AQB123", p.verifier), c)
    }

    @Test fun callbackIsSingleUse() {
        val (auth, p) = started()
        assertTrue(auth.check("zeus://spotify-callback?code=A&state=${p.state}") is Callback.Ok)
        assertEquals(Callback.Bad(NO_SIGN_IN), auth.check("zeus://spotify-callback?code=A&state=${p.state}"))
    }

    @Test fun callbackRejectsWrongStateHostSchemeAgeAndErrors() {
        val p = PendingAuth("s1", "v1", now)
        fun bad(url: String, at: Long = now) = SpotifyAuth.checkCallback(url, p, at) is Callback.Bad
        assertTrue(bad("zeus://spotify-callback?code=A&state=s2"))
        assertTrue(bad("zeus://spotify-callback?code=A"))
        assertTrue(bad("zeus://spotify-callback?state=s1"))
        assertTrue(bad("zeus://evil?code=A&state=s1"))
        assertTrue(bad("https://spotify-callback?code=A&state=s1"))
        assertTrue(bad("zeus://spotify-callback/other?code=A&state=s1"))
        assertTrue(bad("zeus://spotify-callback?code=A&state=s1", now + SpotifyConfig.PENDING_TTL_MS + 1))
        assertEquals(Callback.Bad("You said no on Spotify"), SpotifyAuth.checkCallback("zeus://spotify-callback?error=access_denied&state=s1", p, now))
        assertFalse(bad("zeus://spotify-callback?code=A&state=s1"))
    }

    // ---- tokens ----

    @Test fun exchangeSendsTheVerifierAndNoSecret() {
        val http = FakeToken(200 to """{"access_token":"at1","refresh_token":"rt1","expires_in":3600,"scope":"user-read-playback-state"}""")
        val store = MemTokens()
        val t = SpotifyAuth(http, store) { now }.exchange(Callback.Ok("code1", "ver1"))
        assertEquals(Tokens("at1", "rt1", now + 3_600_000L, "user-read-playback-state"), t)
        assertEquals(t, store.tokens)
        val (url, form) = http.calls.single()
        assertEquals("https://accounts.spotify.com/api/token", url)
        assertEquals("authorization_code", form["grant_type"]); assertEquals("ver1", form["code_verifier"]); assertEquals("code1", form["code"])
        assertFalse(form.containsKey("client_secret"))
    }

    @Test fun refreshOnlyWhenExpiringAndKeepsOrRotatesTheRefreshToken() {
        val http = FakeToken(
            200 to """{"access_token":"at2","expires_in":3600}""",
            200 to """{"access_token":"at3","refresh_token":"rt2","expires_in":3600}""",
        )
        val store = MemTokens().apply { tokens = Tokens("at1", "rt1", now + 3_600_000L, "") }
        val auth = SpotifyAuth(http, store) { now }
        assertEquals("at1", auth.accessToken())
        assertTrue(http.calls.isEmpty())
        now += 3_600_000L - 30_000L   // inside the last minute
        assertEquals("at2", auth.accessToken())
        assertEquals("refresh_token", http.calls[0].second["grant_type"])
        assertEquals("rt1", store.tokens!!.refresh)          // no new one sent: the old one stays
        now += 3_600_000L
        assertEquals("at3", auth.accessToken())
        assertEquals("rt2", store.tokens!!.refresh)          // rotated
    }

    @Test fun revokedRefreshTokenSignsOut() {
        val store = MemTokens().apply { tokens = Tokens("at1", "rt1", 0L, "") }
        val auth = SpotifyAuth(FakeToken(400 to """{"error":"invalid_grant"}"""), store) { now }
        try { auth.accessToken(); fail("should throw") } catch (_: SpotifyAuthError) { }
        assertNull(store.tokens)
        assertNull(SpotifyAuth(FakeToken(), MemTokens()) { now }.accessToken())
    }

    // ---- proposals: tools only propose, auto-approve gating ----

    private val store = MemoryStore()
    private val state = FakeState()
    private val exec = FakeExecutor()
    private val fates = Fates(store, state, exec, { _, _, _ -> }, { EngineTest.EmptyReads.now().toInstant().toEpochMilli() })
    private val box = ToolBox(EngineTest.EmptyReads, fates)

    @Test fun musicToolsOnlyPropose() {
        val play = box.run("play_music", mapOf("uri" to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "name" to "Deep Focus"), 1L)
        assertFalse(play.text, play.isError)
        assertEquals(ProposalStatus.PENDING, play.proposal!!.status)
        assertEquals(ProposalKind.PLAY_MUSIC, play.proposal!!.steps.single().kind)
        val pause = box.run("music_control", mapOf("action" to "pause"), 1L)
        assertEquals(ProposalStatus.PENDING, pause.proposal!!.status)
        assertTrue(exec.ran.isEmpty())
        assertTrue(box.isWrite("play_music") && box.isWrite("music_control"))
    }

    @Test fun modelCannotCallTheDrivingKind() {
        state.drive = true
        assertTrue(box.run("music_while_driving", mapOf("query" to "Driving"), 1L).isError)
        val plan = box.run("propose_plan", mapOf("title" to "x", "steps" to listOf(mapOf("tool" to "music_while_driving", "input" to mapOf("query" to "Driving")))), 1L)
        assertTrue(plan.isError)
        assertTrue(store.rows.isEmpty())
    }

    @Test fun badMusicArgumentsAreNotProposed() {
        assertTrue(box.run("play_music", mapOf("uri" to "https://open.spotify.com/playlist/x"), 1L).isError)
        assertTrue(box.run("play_music", emptyMap(), 1L).isError)
        assertTrue(box.run("music_control", mapOf("action" to "delete_library"), 1L).isError)
        assertTrue(store.rows.isEmpty())
    }

    private fun drive(vararg args: Pair<String, String>) = fates.propose(listOf(RawStep(ProposalKind.MUSIC_WHILE_DRIVING, mapOf(*args))), null, null, proposer = "Drive mode")

    @Test fun playAndControlNeverAutoRunEvenIfListed() {
        fates.autoApprove = ProposalKind.entries.toSet()
        val p = (box.run("play_music", mapOf("query" to "Telugu devotional"), 1L)).proposal!!
        assertEquals(ProposalStatus.PENDING, p.status)
        assertEquals(ProposalStatus.PENDING, box.run("music_control", mapOf("action" to "next"), 1L).proposal!!.status)
        assertTrue(exec.ran.isEmpty())
        assertFalse(ProposalKind.PLAY_MUSIC in ProposalKind.autoCandidates)
        assertFalse(ProposalKind.MUSIC_CONTROL in ProposalKind.autoCandidates)
    }

    @Test fun drivingMusicAsksUnlessHeTurnedItOn() {
        state.drive = true
        assertTrue(fates.autoApprove.isEmpty())
        val asked = (drive("query" to "Driving") as ProposeResult.Created).proposal
        assertEquals(ProposalStatus.PENDING, asked.status)
        assertTrue(exec.ran.isEmpty())

        fates.autoApprove = setOf(ProposalKind.MUSIC_WHILE_DRIVING)
        val r = drive("uri" to "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M", "name" to "Road trip") as ProposeResult.Created
        assertTrue(r.decision is Decision.Done)
        assertTrue(r.proposal.auto)
        assertEquals(1, exec.ran.size)
        // Still undoable: "Playing X" with Undo.
        assertTrue(fates.undo(r.proposal.id) is Decision.Undone)
    }

    @Test fun drivingMusicNeedsDrivingNow() {
        fates.autoApprove = setOf(ProposalKind.MUSIC_WHILE_DRIVING)
        state.drive = false
        assertTrue(drive("query" to "Driving") is ProposeResult.Invalid)
        assertTrue(exec.ran.isEmpty())
    }

    // ---- context cards ----

    private val ny = ZoneId.of("America/New_York")
    private fun at(h: Int) = ZonedDateTime.of(2026, 10, 20, h, 0, 0, 0, ny)

    @Test fun festivalMorningOffersDevotionalOnce() {
        val f = MusicContext.festivalIn(listOf("Team sync", "Dussehra"))
        assertEquals("Dussehra", f)
        val s = MusicContext.suggest(at(8), f, DayType.HOLIDAY, emptySet())!!
        assertEquals("Telugu devotional", s.query)
        assertNull(MusicContext.suggest(at(8), f, DayType.HOLIDAY, setOf(s.key)))
    }

    @Test fun officeMorningAndEveningSuggestions() {
        assertEquals("Deep Focus", MusicContext.suggest(at(9), null, DayType.WORKDAY, emptySet())!!.query)
        assertEquals("Evening chill", MusicContext.suggest(at(20), null, DayType.WEEKEND, emptySet())!!.query)
        assertNull(MusicContext.suggest(at(14), null, DayType.WEEKEND, emptySet()))
        assertNull(MusicContext.festivalIn(listOf("Holiday party", "Hold music review")))
    }

    // ---- Web API ----

    @Test fun webApiSendsTheBearerToApiSpotifyComOnly() {
        val seen = ArrayList<Pair<String, Map<String, String>>>()
        val web = SpotifyWeb({ _, url, h, _ -> seen += url to h; 200 to """{"items":[{"name":"Road trip","uri":"spotify:playlist:abc","owner":{"display_name":"The owner"}}]}""" }) { "at1" }
        assertEquals(listOf(MusicItem("Road trip", "spotify:playlist:abc", "The owner")), web.playlists())
        assertTrue(seen.all { it.first.startsWith("https://api.spotify.com/v1/") && it.second["Authorization"] == "Bearer at1" })
        val signedOut = SpotifyWeb({ _, _, _, _ -> fail("no call without a token"); 0 to "" }) { null }
        try { signedOut.playlists(); fail("should throw") } catch (_: SpotifyError) { }
    }
}
