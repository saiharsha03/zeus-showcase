package dev.phonerecall.music

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.app.SearchManager
import com.anthropic.core.jsonMapper
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.brain.BrainReads
import dev.phonerecall.brain.ExecResult
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.ProposalKind
import dev.phonerecall.brain.RawStep
import dev.phonerecall.brain.Sealer
import dev.phonerecall.brain.SensesListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class Player(val label: String, val pkg: String) {
    SPOTIFY("Spotify", "com.spotify.music"), APPLE("Apple Music", "com.apple.android.music");
    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } }
}

data class NowPlaying(val title: String, val artist: String, val playing: Boolean, val uri: String? = null, val source: Player = Player.SPOTIFY)

/** What confirmed music proposals call. Plain playback calls, no model below this. */
interface MusicHands {
    /** Plays the payload of a play_music / music_while_driving step. Throws on failure. */
    fun play(payload: Map<String, String>): ExecResult
    /** pause, resume, next, previous. Throws on failure. */
    fun control(action: String): ExecResult
}

/** What Delphi's read_music tool sees: never tokens, only names and Spotify URIs. */
interface MusicReads {
    fun summary(): Map<String, Any?>
}

/** Refresh token and the in-flight sign-in, sealed with a Keystore key, in Calypso's app-private prefs (excluded from backup). */
class SealedTokenStore(context: Context, private val sealer: Sealer = KeystoreSealer("zeus.calypso.spotify")) : TokenStore {
    private val sp = context.getSharedPreferences("calypso", Context.MODE_PRIVATE)
    private val json = jsonMapper()

    private fun read(key: String): Map<*, *>? = sp.getString(key, null)?.let { sealer.open(it) }?.let { runCatching { json.readValue(it, Map::class.java) }.getOrNull() }
    private fun write(key: String, m: Map<String, Any>) { sp.edit().putString(key, sealer.seal(json.writeValueAsString(m))).commit() }

    override fun load(): Tokens? = read(TOKENS)?.let { m ->
        Tokens(m["access"] as? String ?: return null, m["refresh"] as? String ?: return null, (m["expiresAt"] as? Number)?.toLong() ?: 0L, m["scope"] as? String ?: "")
    }
    override fun save(t: Tokens) = write(TOKENS, mapOf("access" to t.access, "refresh" to t.refresh, "expiresAt" to t.expiresAt, "scope" to t.scope))
    override fun clear() { sp.edit().remove(TOKENS).remove(PENDING).commit() }
    override fun savePending(p: PendingAuth) = write(PENDING, mapOf("state" to p.state, "verifier" to p.verifier, "createdAt" to p.createdAt))
    override fun takePending(): PendingAuth? {
        val m = read(PENDING)
        sp.edit().remove(PENDING).commit()
        return m?.let { PendingAuth(it["state"] as? String ?: return null, it["verifier"] as? String ?: return null, (it["createdAt"] as? Number)?.toLong() ?: 0L) }
    }

    private companion object {
        const val TOKENS = "spotify.tokens"
        const val PENDING = "spotify.pending"
    }
}

/**
 * Spotify App Remote: IPC to the Spotify app on the phone (bindService to com.spotify.music), no network
 * from Zeus. The first connect shows Spotify's own consent screen once.
 */
class SpotifyRemote(
    private val app: Context,
    private val onState: (NowPlaying?) -> Unit,
    private val onSeen: (Seen) -> Unit = {},
    private val onContext: (String?) -> Unit = {},
) {
    @Volatile private var remote: SpotifyAppRemote? = null
    private val main = Handler(Looper.getMainLooper())

    fun installed(): Boolean = SpotifyAppRemote.isSpotifyInstalled(app)
    fun connected(): Boolean = remote?.isConnected == true

    /** Connects (on the main thread, as the SDK wants) and waits up to `waitMs` when called off it. */
    fun connect(waitMs: Long = 6_000): SpotifyAppRemote? {
        remote?.takeIf { it.isConnected }?.let { return it }
        if (!installed()) return null
        val done = CountDownLatch(1)
        main.post {
            val params = ConnectionParams.Builder(SpotifyConfig.CLIENT_ID).setRedirectUri(SpotifyConfig.REDIRECT).showAuthView(true).build()
            SpotifyAppRemote.connect(app, params, object : Connector.ConnectionListener {
                override fun onConnected(r: SpotifyAppRemote) {
                    remote = r
                    r.playerApi.subscribeToPlayerState().setEventCallback { s ->
                        onState(s.track?.let { t -> NowPlaying(t.name.orEmpty(), t.artist?.name.orEmpty(), !s.isPaused, t.uri, Player.SPOTIFY) })
                        val t = s.track
                        onSeen(Seen(t?.uri, t?.name.orEmpty(), t?.artist?.name.orEmpty(), t?.duration ?: 0L, s.playbackPosition, s.isPaused, System.currentTimeMillis()))
                    }
                    r.playerApi.subscribeToPlayerContext().setEventCallback { c -> onContext(c.uri) }
                    done.countDown()
                }
                override fun onFailure(error: Throwable) { remote = null; done.countDown() }
            })
        }
        if (Looper.myLooper() != Looper.getMainLooper()) done.await(waitMs, TimeUnit.MILLISECONDS)
        return remote?.takeIf { it.isConnected }
    }

    fun play(uri: String, shuffle: Boolean) {
        val r = connect() ?: throw SpotifyError("The Spotify app did not connect")
        r.playerApi.setShuffle(shuffle)
        val res = r.playerApi.play(uri).await(5, TimeUnit.SECONDS)
        if (!res.isSuccessful) throw SpotifyError(res.errorMessage ?: "Spotify did not start")
    }

    fun control(action: String) {
        val r = connect() ?: throw SpotifyError("The Spotify app did not connect")
        val api = r.playerApi
        val res = when (action) {
            "pause" -> api.pause(); "resume" -> api.resume(); "next" -> api.skipNext(); "previous" -> api.skipPrevious()
            else -> throw SpotifyError("Unknown action $action")
        }.await(5, TimeUnit.SECONDS)
        if (!res.isSuccessful) throw SpotifyError(res.errorMessage ?: "Spotify did not respond")
    }

    fun disconnect() { remote?.let { r -> main.post { SpotifyAppRemote.disconnect(r) } }; remote = null }
}

/** Apple Music (or any player) through its media session, visible because Zeus holds notification access. */
class MediaFallback(private val app: Context) {
    private fun sessions(): List<MediaController> = runCatching {
        app.getSystemService(MediaSessionManager::class.java).getActiveSessions(ComponentName(app, SensesListener::class.java))
    }.getOrDefault(emptyList())

    fun controller(pkg: String? = null): MediaController? {
        val all = sessions()
        return pkg?.let { p -> all.firstOrNull { it.packageName == p } } ?: all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
    }

    fun now(): NowPlaying? {
        val m = controller() ?: return null
        val md = m.metadata ?: return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifEmpty { return null }
        val src = Player.entries.firstOrNull { it.pkg == m.packageName } ?: Player.APPLE
        return NowPlaying(title, md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(), m.playbackState?.state == PlaybackState.STATE_PLAYING, source = src)
    }

    fun play(query: String) {
        val ex = Bundle().apply { putString(SearchManager.QUERY, query); putString(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*") }
        controller(Player.APPLE.pkg)?.let { s -> s.transportControls.playFromSearch(query, ex); return }
        val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(Player.APPLE.pkg).putExtras(ex).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (i.resolveActivity(app.packageManager) == null) throw SpotifyError("Apple Music is not installed")
        app.startActivity(i)
    }

    fun control(action: String) {
        val t = controller()?.transportControls ?: throw SpotifyError("No music app is playing")
        when (action) { "pause" -> t.pause(); "resume" -> t.play(); "next" -> t.skipToNext(); "previous" -> t.skipToPrevious(); else -> throw SpotifyError("Unknown action $action") }
    }
}

/**
 * Music for the whole app: Spotify by default (App Remote when the app is installed, the Web API when
 * it isn't or the remote fails), Apple Music through MediaSession as the fallback player. One per process.
 */
class MusicCore private constructor(private val app: Context) : MusicHands, MusicReads {
    private val sp = app.getSharedPreferences("music", Context.MODE_PRIVATE)
    val auth = SpotifyAuth(GatedHttp, SealedTokenStore(app))
    val web = SpotifyWeb(GatedHttp) { auth.accessToken() }
    private val media = MediaFallback(app)

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now.asStateFlow()

    // ---- skip learning: on the phone, names and counts only ----
    private val skips = SkipBook(object : SkipStore {
        override fun load() = sp.getString("skips", null)
        override fun save(json: String) { sp.edit().putString("skips", json).apply() }
    })
    private val detector = SkipDetector()
    private val worker = Executors.newSingleThreadExecutor()
    /** The playlist Zeus started through App Remote: a usually-skipped song that comes on in it is skipped once. */
    @Volatile private var zeusContext: String? = null
    @Volatile private var playingContext: String? = null
    private var autoSkipped: String? = null
    private val _skipped = MutableStateFlow(skips.out(System.currentTimeMillis()))
    /** Usually skipped, newest first, for Themis. */
    val skipped: StateFlow<List<TrackStat>> = _skipped.asStateFlow()

    val remote = SpotifyRemote(app, onState = { _now.value = it }, onSeen = ::seen, onContext = { uri ->
        playingContext = uri?.ifBlank { null }
        detector.context(playingContext)
        if (playingContext != null && !samePlaylist(playingContext, zeusContext)) zeusContext = null
    })

    private fun samePlaylist(a: String?, b: String?) = a != null && b != null && a.substringAfterLast(':') == b.substringAfterLast(':')

    /** The remote's player state (main thread): learn from what he skips, and skip what he usually skips. */
    private fun seen(s: Seen) {
        if (!_state.value.learnSkips) return
        val was = detector.current?.uri
        detector.see(s)?.let { skips.record(it, s.at); _skipped.value = skips.out(s.at) }
        val uri = s.uri ?: return
        if (uri == was || uri == autoSkipped || s.paused || !samePlaylist(playingContext, zeusContext) || !SkipRules.out(skips.get(uri), s.at)) return
        autoSkipped = uri
        detector.ours(s.at)
        worker.execute { runCatching { remote.control("next") }.onFailure { dev.phonerecall.clio.Clio.error("music", it) } }
        dev.phonerecall.clio.Clio.info("music", "auto-skipped", "a song he usually skips")
    }

    fun setLearnSkips(on: Boolean) { sp.edit().putBoolean("skips.learn", on).apply(); reload() }

    /** Bring back: forget the song's history so it plays again. */
    fun bringBack(uri: String) { skips.forget(uri); _skipped.value = skips.out(System.currentTimeMillis()) }

    /** His playlist minus what he usually skips, shuffled, started as a list of tracks (Premium). Null: play it normally. */
    private fun playLearned(item: MusicItem): String? {
        if (!_state.value.learnSkips || !auth.connected() || !item.uri.startsWith("spotify:playlist:")) return null
        val now = System.currentTimeMillis()
        return runCatching {
            val pick = SkipRules.pick(web.playlistTracks(item.uri).map { it.uri }, skips.all(), now)
            if (pick.leftOut == 0 || pick.uris.isEmpty()) return null
            // Wakes Spotify on the phone so it shows up as a device.
            if (remote.installed()) remote.connect()
            detector.ours(now)
            web.playUris(pick.uris)
            zeusContext = null
            if (pick.rest.isNotEmpty()) worker.execute { for (u in pick.rest) if (runCatching { web.queue(u) }.isFailure) break }
            "Playing ${item.name} on Spotify, without ${pick.leftOut} ${if (pick.leftOut == 1) "song" else "songs"} you usually skip"
        }.onFailure { dev.phonerecall.clio.Clio.error("music", it) }.getOrNull()
    }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<MusicSettings> = _state.asStateFlow()

    private fun load() = MusicSettings(
        connected = auth.connected(),
        player = Player.of(sp.getString("player", null)) ?: Player.SPOTIFY,
        drivePlaylist = sp.getString("drive.uri", null)?.let { MusicItem(sp.getString("drive.name", "").orEmpty(), it) },
        driveSense = sp.getBoolean("drive.sense", false),
        spotifyInstalled = runCatching { remote.installed() }.getOrDefault(false),
        learnSkips = sp.getBoolean("skips.learn", true),
    )

    fun reload() { _state.value = load() }

    val driving: Boolean get() = sp.getBoolean("driving", false)
    fun setDriving(on: Boolean) { sp.edit().putBoolean("driving", on).apply() }

    fun setPlayer(p: Player) { sp.edit().putString("player", p.name).apply(); reload() }
    fun setDrivePlaylist(item: MusicItem?) {
        sp.edit().apply { if (item == null) remove("drive.uri").remove("drive.name") else putString("drive.uri", item.uri).putString("drive.name", item.name) }.apply(); reload()
    }
    fun setDriveSense(on: Boolean) { sp.edit().putBoolean("drive.sense", on).apply(); reload() }

    /** Called from the callback activity (off the main thread). */
    fun finishSignIn(redirect: String): String? = when (val c = auth.check(redirect)) {
        is Callback.Bad -> c.reason
        is Callback.Ok -> runCatching { auth.exchange(c); null }.getOrElse { it.message ?: "Sign-in failed" }.also { reload() }
    }

    fun disconnect() { auth.disconnect(); remote.disconnect(); _now.value = null; reload() }

    /** Now playing for Olympus: the remote's live state, else the Web API, else any media session. */
    fun refresh() {
        if (remote.connected()) return
        val n = if (auth.connected()) runCatching { web.nowPlaying() }.getOrNull() else null
        _now.value = n ?: media.now()
    }

    // ---- hands (Fates calls these after a confirm, or the auto-approve list) ----

    override fun play(payload: Map<String, String>): ExecResult {
        val shuffle = payload["shuffle"] == "true"
        val query = payload["query"]
        if (_state.value.player == Player.APPLE) {
            media.play(payload["name"] ?: query ?: throw SpotifyError("Apple Music needs words to search for"))
            return ExecResult("Playing ${payload["name"] ?: query} on Apple Music", mapOf("action" to "pause"))
        }
        val item = payload["uri"]?.let { MusicItem(payload["name"] ?: it, it) }
            ?: web.resolve(query ?: throw SpotifyError("Nothing to play"))
            ?: throw SpotifyError("Spotify found nothing for \"$query\"")
        playLearned(item)?.let { return ExecResult(it, mapOf("action" to "pause")) }
        val how = try {
            detector.ours(System.currentTimeMillis())
            remote.play(item.uri, shuffle)
            // Premium or not, a usually-skipped song that comes on is skipped for him.
            if (item.uri.startsWith("spotify:playlist:")) { zeusContext = item.uri; autoSkipped = null }
            "Spotify"
        } catch (e: Exception) {
            dev.phonerecall.clio.Clio.error("music", e)
            if (!auth.connected()) throw e
            web.play(item.uri, shuffle); "Spotify (another device)"
        }
        return ExecResult("Playing ${item.name} on $how", mapOf("action" to "pause"))
    }

    override fun control(action: String): ExecResult {
        val word = mapOf("pause" to "Paused", "resume" to "Playing", "next" to "Next track", "previous" to "Previous track")[action] ?: throw SpotifyError("Unknown action $action")
        val undo = mapOf("pause" to "resume", "resume" to "pause", "next" to "previous")[action]?.let { mapOf("action" to it) }
        val via = if (_state.value.player == Player.APPLE) { media.control(action); "Apple Music" } else try {
            remote.control(action); "Spotify"
        } catch (e: Exception) {
            dev.phonerecall.clio.Clio.error("music", e)
            if (auth.connected()) { web.control(action); "Spotify" } else { media.control(action); "the player" }
        }
        return ExecResult("$word on $via", undo)
    }

    override fun summary(): Map<String, Any?> {
        val s = _state.value
        val base = linkedMapOf<String, Any?>("default_player" to s.player.label, "spotify_connected" to s.connected)
        refresh()
        _now.value?.let { base["now_playing"] = mapOf("title" to it.title, "artist" to it.artist, "playing" to it.playing, "uri" to it.uri) }
        if (s.connected) {
            runCatching { web.playlists() }.getOrNull()?.let { l -> base["playlists"] = l.map { mapOf("name" to it.name, "uri" to it.uri) } }
            runCatching { web.recent(8) }.getOrNull()?.let { l -> base["recently_played"] = l.map { mapOf("name" to it.name, "by" to it.by, "uri" to it.uri) } }
            runCatching { web.devices() }.getOrNull()?.let { l -> base["devices"] = l.map { mapOf("name" to it.name, "type" to it.type, "active" to it.active) } }
        }
        s.drivePlaylist?.let { base["drive_playlist"] = it.name }
        if (s.learnSkips) skips.out(System.currentTimeMillis()).size.takeIf { it > 0 }?.let {
            base["usually_skipped"] = "$it ${if (it == 1) "song" else "songs"} you usually skip ${if (it == 1) "is" else "are"} left out of playlists Zeus plays"
        }
        return base
    }

    // ---- context cards and drive mode ----

    /** On resume: at most one context card per context per day, as a play_music proposal he taps. */
    fun offerContext(reads: BrainReads) {
        val s = _state.value
        if (s.player == Player.SPOTIFY && !s.connected) return
        val now = reads.now()
        val today = now.toLocalDate()
        val from = today.atStartOfDay(now.zone).toInstant().toEpochMilli()
        val titles = runCatching { reads.relevantEvents(from, from + 86_400_000L).map { it.title } }.getOrDefault(emptyList())
        val festival = dev.phonerecall.home.Festivals.on(today)?.name ?: MusicContext.festivalIn(titles)
        val offered = sp.getStringSet("offered", emptySet()).orEmpty().filter { it.endsWith(today.toString()) }.toSet()
        val pick = MusicContext.suggest(now, festival, reads.availability()[today]?.type, offered) ?: return
        sp.edit().putStringSet("offered", offered + pick.key).apply()
        BrainCore.get(app).fates.propose(
            listOf(RawStep(ProposalKind.PLAY_MUSIC, mapOf("query" to pick.query, "name" to pick.title, "reason" to pick.reason))),
            pick.title, null, proposer = "Music",
        )
    }

    /** Drive mode saw the car start: one music_while_driving proposal (automatic only if he put it on the list). */
    fun driveStarted() {
        setDriving(true)
        val d = _state.value.drivePlaylist
        // One per trip: a stop at the gas station doesn't make a second one.
        val now = System.currentTimeMillis()
        if (now - sp.getLong("drive.last", 0L) < 30 * 60_000L) return
        sp.edit().putLong("drive.last", now).apply()
        val args = if (d != null) mapOf("uri" to d.uri, "name" to d.name) else mapOf("query" to DEFAULT_PLAYLIST, "name" to DEFAULT_PLAYLIST)
        BrainCore.get(app).let { it.fates.propose(listOf(RawStep(ProposalKind.MUSIC_WHILE_DRIVING, args)), null, null, proposer = "Drive mode"); it.changed() }
    }

    fun driveEnded() {
        setDriving(false)
        // Small touches: whether music was on as the drive ended, for "Resume on the Fold" (qol2/Touches).
        runCatching { dev.phonerecall.qol2.Touches.driveEnded(app, _now.value?.playing == true) }
    }

    /** The car's music already started (qol/CarMusic): drive mode's proposal skips this trip. */
    fun drivePlayed(at: Long) { sp.edit().putLong("drive.last", at).apply() }

    companion object {
        /** His own playlist, matched by exact name, when no drive playlist is chosen. */
        const val DEFAULT_PLAYLIST = "Drive Mix"

        @Volatile private var instance: MusicCore? = null
        fun get(context: Context): MusicCore = instance ?: synchronized(this) { instance ?: MusicCore(context.applicationContext).also { instance = it } }
    }
}

data class MusicSettings(
    val connected: Boolean = false,
    val player: Player = Player.SPOTIFY,
    val drivePlaylist: MusicItem? = null,
    val driveSense: Boolean = false,
    val spotifyInstalled: Boolean = false,
    val learnSkips: Boolean = true,
)
