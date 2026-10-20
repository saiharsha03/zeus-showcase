package dev.phonerecall.music

import com.anthropic.core.jsonMapper
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/*
 * Spotify sign-in: Authorization Code with PKCE (docs/SPOTIFY.md). No client secret anywhere; the
 * verifier stays on the phone, sealed, until the callback. The refresh token lives only in Calypso's
 * Keystore-sealed store. Everything here is plain JVM so the tests run without a device.
 */

object SpotifyConfig {
    /** Public by design (PKCE). Register your own app at developer.spotify.com and put its id here. */
    const val CLIENT_ID = "your-spotify-client-id"
    const val REDIRECT = "zeus://spotify-callback"
    const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    const val TOKEN = "https://accounts.spotify.com/api/token"
    const val API = "https://api.spotify.com/v1"
    /** The least that playback, now playing, his playlists and recently played need. */
    val SCOPES = listOf(
        "user-read-playback-state", "user-modify-playback-state", "user-read-currently-playing",
        "user-library-read", "playlist-read-private", "playlist-read-collaborative", "user-read-recently-played",
    )
    /** A sign-in left half-done expires after this. */
    const val PENDING_TTL_MS = 10 * 60_000L
}

object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** 64 random bytes as base64url: 86 characters, inside RFC 7636's 43 to 128. */
    fun verifier(random: SecureRandom = SecureRandom()): String = b64.encodeToString(ByteArray(64).also(random::nextBytes))

    /** S256: base64url(sha256(ascii(verifier))), no padding. */
    fun challenge(verifier: String): String = b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun state(random: SecureRandom = SecureRandom()): String = b64.encodeToString(ByteArray(24).also(random::nextBytes))
}

/** One sign-in in flight: what the callback must match. */
data class PendingAuth(val state: String, val verifier: String, val createdAt: Long)

data class Tokens(val access: String, val refresh: String, val expiresAt: Long, val scope: String)

/** Where tokens and the in-flight sign-in live. The Android one seals both with the Keystore. */
interface TokenStore {
    fun load(): Tokens?
    fun save(t: Tokens)
    fun clear()
    fun savePending(p: PendingAuth)
    /** Returns the in-flight sign-in and forgets it, so a callback can be used once. */
    fun takePending(): PendingAuth?
}

/** The one form POST the token endpoint needs. Implementations must pass NetGate. */
fun interface FormPost {
    /** Returns the status code and body. */
    fun post(url: String, form: Map<String, String>): Pair<Int, String>
}

sealed class Callback {
    data class Ok(val code: String, val verifier: String) : Callback()
    data class Bad(val reason: String) : Callback()
}

class SpotifyAuthError(msg: String) : Exception(msg)

const val NO_SIGN_IN = "No sign-in was started"

class SpotifyAuth(private val http: FormPost, private val store: TokenStore, private val clock: () -> Long = System::currentTimeMillis) {
    private val json = jsonMapper()
    private val lock = Any()

    /** Starts a sign-in: remembers state + verifier and returns the URL for the Custom Tab. */
    fun begin(random: SecureRandom = SecureRandom()): String {
        val p = PendingAuth(Pkce.state(random), Pkce.verifier(random), clock())
        store.savePending(p)
        return authorizeUrl(p.state, Pkce.challenge(p.verifier))
    }

    /** Checks the redirect: our exact scheme and host, an unexpired sign-in, the same state, a code. Single use. */
    fun check(redirect: String): Callback {
        val pending = store.takePending() ?: return Callback.Bad(NO_SIGN_IN)
        return checkCallback(redirect, pending, clock())
    }

    /** Swaps the code for tokens and stores them. */
    fun exchange(ok: Callback.Ok): Tokens = synchronized(lock) {
        val t = tokenCall(mapOf(
            "grant_type" to "authorization_code", "code" to ok.code, "redirect_uri" to SpotifyConfig.REDIRECT,
            "client_id" to SpotifyConfig.CLIENT_ID, "code_verifier" to ok.verifier,
        ), previousRefresh = null)
        store.save(t); t
    }

    /** A valid access token, refreshed when it is within a minute of expiring. Null when not signed in. */
    fun accessToken(): String? = synchronized(lock) {
        val t = store.load() ?: return null
        if (clock() < t.expiresAt - 60_000L) return t.access
        val fresh = try {
            tokenCall(mapOf("grant_type" to "refresh_token", "refresh_token" to t.refresh, "client_id" to SpotifyConfig.CLIENT_ID), previousRefresh = t.refresh)
        } catch (e: SpotifyAuthError) {
            // A revoked or rotated-away refresh token: sign out rather than retry forever.
            if (e.message?.contains("invalid_grant") == true) store.clear()
            throw e
        }
        store.save(fresh); fresh.access
    }

    fun connected(): Boolean = store.load() != null

    fun disconnect() = store.clear()

    private fun tokenCall(form: Map<String, String>, previousRefresh: String?): Tokens {
        val (code, body) = http.post(SpotifyConfig.TOKEN, form)
        val m = runCatching { json.readValue(body, Map::class.java) }.getOrNull() ?: emptyMap<Any, Any>()
        if (code !in 200..299) throw SpotifyAuthError("Spotify sign-in failed ($code ${m["error"] ?: ""})".trim())
        val access = m["access_token"] as? String ?: throw SpotifyAuthError("Spotify sent no access token")
        // Spotify may rotate the refresh token; when it doesn't, the old one stays valid.
        val refresh = m["refresh_token"] as? String ?: previousRefresh ?: throw SpotifyAuthError("Spotify sent no refresh token")
        val expires = (m["expires_in"] as? Number)?.toLong() ?: 3600L
        return Tokens(access, refresh, clock() + expires * 1000L, m["scope"] as? String ?: "")
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun authorizeUrl(state: String, challenge: String): String = SpotifyConfig.AUTHORIZE + "?" + listOf(
            "client_id" to SpotifyConfig.CLIENT_ID, "response_type" to "code", "redirect_uri" to SpotifyConfig.REDIRECT,
            "code_challenge_method" to "S256", "code_challenge" to challenge, "state" to state,
            "scope" to SpotifyConfig.SCOPES.joinToString(" "),
        ).joinToString("&") { (k, v) -> "$k=${enc(v)}" }

        fun query(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('='); val v = it.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

        /** Pure: is this redirect the answer to `pending`? */
        fun checkCallback(redirect: String, pending: PendingAuth, now: Long): Callback {
            val u = runCatching { URI(redirect) }.getOrNull() ?: return Callback.Bad("Not a link")
            if (!u.scheme.equals("zeus", true) || !u.host.equals("spotify-callback", true) || (!u.rawPath.isNullOrEmpty() && u.rawPath != "/")) {
                return Callback.Bad("Not Zeus's Spotify link")
            }
            if (now - pending.createdAt > SpotifyConfig.PENDING_TTL_MS || now < pending.createdAt) return Callback.Bad("The sign-in took too long. Try again.")
            val q = query(u)
            q["error"]?.let { return Callback.Bad(if (it == "access_denied") "You said no on Spotify" else "Spotify said: $it") }
            val state = q["state"] ?: return Callback.Bad("No state")
            if (!MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray())) return Callback.Bad("State did not match")
            val code = q["code"]?.takeIf { it.isNotBlank() } ?: return Callback.Bad("No code")
            return Callback.Ok(code, pending.verifier)
        }
    }
}
