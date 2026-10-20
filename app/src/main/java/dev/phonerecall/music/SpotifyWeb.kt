package dev.phonerecall.music

import com.anthropic.core.jsonMapper
import dev.phonerecall.brain.NetGate
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** One HTTP call. The real one passes NetGate first; tests use a fake. */
fun interface Http {
    fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String>
}

/** HttpURLConnection behind NetGate. Redirects are not followed, so a 30x can't lead to another host. */
object GatedHttp : Http, FormPost {
    override fun call(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> {
        NetGate.check(url)
        val t0 = System.currentTimeMillis()
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = method
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            } else if (method == "PUT" || method == "POST") {
                c.doOutput = true; c.setFixedLengthStreamingMode(0)
            }
            val code = c.responseCode
            NetGate.logRequest(method, url, code, System.currentTimeMillis() - t0)
            val text = (if (code >= 400) c.errorStream else runCatching { c.inputStream }.getOrNull())?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    override fun post(url: String, form: Map<String, String>): Pair<Int, String> = call(
        "POST", url, mapOf("Content-Type" to "application/x-www-form-urlencoded"),
        form.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}" },
    )
}

data class MusicItem(val name: String, val uri: String, val by: String? = null)
data class SpotifyDevice(val id: String, val name: String, val type: String, val active: Boolean)

class SpotifyError(msg: String) : Exception(msg)

/** The Web API (api.spotify.com): his playlists, search, devices, queue, now playing, and remote control without the app. */
class SpotifyWeb(private val http: Http, private val token: () -> String?) {
    private val json = jsonMapper()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    @Suppress("UNCHECKED_CAST")
    private fun req(method: String, path: String, body: Map<String, Any?>? = null): Map<String, Any?>? {
        val t = token() ?: throw SpotifyError("Spotify is not connected. Connect it in Themis, Music.")
        val (code, text) = http.call(
            method, SpotifyConfig.API + path,
            mapOf("Authorization" to "Bearer $t", "Content-Type" to "application/json"),
            body?.let { json.writeValueAsString(it) },
        )
        when {
            code == 204 || text.isBlank() && code in 200..299 -> return null
            code == 401 -> throw SpotifyError("Spotify needs you to connect again")
            code == 403 -> throw SpotifyError("Spotify refused that (Premium is needed for playback control)")
            code == 404 -> throw SpotifyError("No active Spotify device. Open Spotify once, then try again.")
            code == 429 -> throw SpotifyError("Spotify asked Zeus to slow down")
            code !in 200..299 -> throw SpotifyError("Spotify error $code")
        }
        return runCatching { json.readValue(text, Map::class.java) as Map<String, Any?> }.getOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(m: Map<String, Any?>?, key: String? = null): List<Map<String, Any?>> {
        val box = if (key == null) m else m?.get(key) as? Map<String, Any?>
        return (box?.get("items") as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()
    }

    private fun item(m: Map<String, Any?>): MusicItem? {
        val name = m["name"] as? String ?: return null
        val uri = m["uri"] as? String ?: return null
        @Suppress("UNCHECKED_CAST")
        val by = ((m["artists"] as? List<*>)?.firstOrNull() as? Map<String, Any?>)?.get("name") as? String
            ?: ((m["owner"] as? Map<*, *>)?.get("display_name") as? String)
        return MusicItem(name, uri, by)
    }

    fun playlists(limit: Int = 50): List<MusicItem> = items(req("GET", "/me/playlists?limit=$limit")).mapNotNull(::item)

    fun search(query: String, types: String = "playlist,artist,track", limit: Int = 3): Map<String, List<MusicItem>> {
        val m = req("GET", "/search?q=${enc(query)}&type=$types&limit=$limit")
        return types.split(',').associateWith { t -> items(m, t + "s").mapNotNull(::item) }
    }

    /** What to play for words: an artist named exactly that, one of his playlists, a playlist, then a track. */
    fun resolve(query: String, mine: List<MusicItem> = runCatching { playlists() }.getOrDefault(emptyList())): MusicItem? {
        val q = query.trim().lowercase()
        mine.firstOrNull { it.name.lowercase() == q }?.let { return it }
        val found = search(query)
        found["artist"].orEmpty().firstOrNull { it.name.lowercase() == q }?.let { return it }
        mine.firstOrNull { q in it.name.lowercase() }?.let { return it }
        return found["playlist"].orEmpty().firstOrNull() ?: found["track"].orEmpty().firstOrNull() ?: found["artist"].orEmpty().firstOrNull()
    }

    fun devices(): List<SpotifyDevice> = (req("GET", "/me/player/devices")?.get("devices") as? List<*>).orEmpty().mapNotNull { d ->
        (d as? Map<*, *>)?.let { SpotifyDevice(it["id"] as? String ?: return@let null, it["name"] as? String ?: "", it["type"] as? String ?: "", it["is_active"] == true) }
    }

    @Suppress("UNCHECKED_CAST")
    fun nowPlaying(): NowPlaying? {
        val m = req("GET", "/me/player/currently-playing") ?: return null
        val track = (m["item"] as? Map<String, Any?>)?.let(::item) ?: return null
        return NowPlaying(track.name, track.by.orEmpty(), playing = m["is_playing"] == true, uri = track.uri, source = Player.SPOTIFY)
    }

    @Suppress("UNCHECKED_CAST")
    fun recent(limit: Int = 10): List<MusicItem> = items(req("GET", "/me/player/recently-played?limit=$limit")).mapNotNull { (it["track"] as? Map<String, Any?>)?.let(::item) }

    fun queue(uri: String) { req("POST", "/me/player/queue?uri=${enc(uri)}") }

    /** His active device, else the phone when it's listed. */
    private fun target(): SpotifyDevice? = runCatching { devices() }.getOrDefault(emptyList()).let { ds -> ds.firstOrNull { it.active } ?: ds.firstOrNull { it.type.equals("Smartphone", true) } }

    /** Every track uri in a playlist, a page of 100 at a time. */
    @Suppress("UNCHECKED_CAST")
    fun playlistTracks(uri: String): List<MusicItem> {
        val id = uri.removePrefix("spotify:playlist:")
        val out = ArrayList<MusicItem>()
        var offset = 0
        while (true) {
            val page = items(req("GET", "/playlists/${enc(id)}/tracks?limit=100&offset=$offset&fields=${enc("items(track(name,uri,artists(name))),next")}"))
            out += page.mapNotNull { (it["track"] as? Map<String, Any?>)?.let(::item) }
            if (page.size < 100) return out
            offset += page.size
        }
    }

    /** Starts these tracks, in this order, on his active device or the phone. Premium only (403 otherwise). */
    fun playUris(uris: List<String>) {
        val q = target()?.let { "?device_id=${enc(it.id)}" }.orEmpty()
        req("PUT", "/me/player/play$q", mapOf("uris" to uris))
    }

    /** Starts playback on his active device (or the phone, when it's listed). */
    fun play(uri: String, shuffle: Boolean) {
        val device = target()
        val q = device?.let { "?device_id=${enc(it.id)}" }.orEmpty()
        if (shuffle) runCatching { req("PUT", "/me/player/shuffle?state=true" + device?.let { "&device_id=${enc(it.id)}" }.orEmpty()) }
        req("PUT", "/me/player/play$q", if (uri.startsWith("spotify:track:")) mapOf("uris" to listOf(uri)) else mapOf("context_uri" to uri))
    }

    fun control(action: String) {
        when (action) {
            "pause" -> req("PUT", "/me/player/pause")
            "resume" -> req("PUT", "/me/player/play")
            "next" -> req("POST", "/me/player/next")
            "previous" -> req("POST", "/me/player/previous")
            else -> throw SpotifyError("Unknown action $action")
        }
    }
}
