package dev.phonerecall.lemnos

import dev.phonerecall.brain.NetGate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

// ---- what Lemnos returns (docs/ZEUS-GATEWAY.md) ----

data class Disk(val root: String, val path: String, val total: Long?, val used: Long?, val free: Long?, val ok: Boolean)
data class Health(val host: String, val uptime: Long, val load: List<Double>, val cpus: Int, val memTotal: Long, val memAvailable: Long, val swapTotal: Long, val swapFree: Long, val disks: List<Disk>)
data class TermSession(val name: String, val created: Long, val attached: Int, val activity: Long, val agent: String?, val kind: String)
data class SlashCmd(val cmd: String, val hint: String)
data class FileEntry(val name: String, val dir: Boolean, val link: Boolean, val size: Long, val mtime: Long)
data class ServiceUnit(val unit: String, val kind: String, val active: String, val sub: String, val description: String, val controllable: Boolean, val last: Long?, val next: Long?)
data class UnitDetail(val state: Map<String, String>, val logs: String, val controllable: Boolean)
data class AgentStatus(val id: String, val name: String, val kind: String, val session: String, val alive: Boolean, val channel: String, val busy: Boolean, val waiting: String?)
data class ChatMsg(val id: Long, val room: String, val sender: String, val text: String, val ts: Double, val kind: String, val replyTo: Long?, val jobId: String?)
data class WorkerJob(val jobId: String, val to: String, val kind: String, val title: String, val status: String, val approved: Boolean, val created: Double, val updated: Double, val result: String?)
data class Alert(val id: Long, val ts: Double, val source: String, val severity: String, val title: String, val body: String, val ack: Boolean)
data class InboxItem(val id: Long, val kind: String, val title: String, val body: String, val severity: String, val room: String?)
/** The remote desktop's unit (zeus-desktop.service). [memory] in bytes while it runs; [since] epoch seconds. */
data class DesktopStatus(val state: String, val ready: Boolean, val memory: Long?, val memoryMax: Long, val since: Long?, val viewers: Int, val idleMinutes: Int) {
    val running get() = state == "active"
}
data class IrisTask(val id: Long, val title: String, val status: String, val priority: String?, val due: String?, val owner: String?, val waitingOnYou: Boolean, val project: String?, val notes: String, val sensitive: Boolean)

class GatewayException(val code: Int, message: String) : Exception(message)

/**
 * Zeus's client for Lemnos. TLS presents the Keystore client key ([DeviceKeys.keyManager]); the server's
 * certificate is checked against the system CAs, which network_security_config.xml allows for this host only.
 * NetGate sees every request first. Blocking calls: run on IO.
 */
object Gateway {
    const val BASE = "https://${NetGate.LEMNOS_HOST}"
    private val JSON = "application/json".toMediaType()

    val client: OkHttpClient by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val trust = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
        val ssl = SSLContext.getInstance("TLS").apply { init(arrayOf(DeviceKeys.keyManager), arrayOf(trust), null) }
        OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, trust)
            .addInterceptor(NetGate.okHttp)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private fun q(v: String) = URLEncoder.encode(v, "UTF-8")

    private fun call(req: Request): Response {
        val r = client.newCall(req).execute()
        if (!r.isSuccessful) {
            val detail = runCatching { JSONObject(r.body?.string() ?: "").optString("detail") }.getOrNull().orEmpty()
            r.close()
            throw GatewayException(r.code, detail.ifEmpty { "Lemnos answered ${r.code}" })
        }
        return r
    }

    private fun text(method: String, path: String, body: JSONObject? = null): String {
        val b = Request.Builder().url(BASE + path)
        when (method) {
            "GET" -> b.get()
            "DELETE" -> b.delete()
            else -> b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        }
        return call(b.build()).use { it.body?.string().orEmpty() }
    }

    private fun obj(method: String, path: String, body: JSONObject? = null) = JSONObject(text(method, path, body))
    private fun arr(path: String) = JSONArray(text("GET", path))
    private fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
    private fun JSONObject.str(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
    private fun JSONObject.long(k: String): Long? = if (isNull(k) || !has(k)) null else optLong(k)

    // ---- health + sessions ----
    fun ping(): Boolean = runCatching { obj("GET", "/api/ping").optBoolean("ok") }.getOrDefault(false)

    fun health(): Health = obj("GET", "/api/health").let { h ->
        val load = h.optJSONArray("load")
        Health(
            h.optString("host"), h.optLong("uptime"), (0 until (load?.length() ?: 0)).map { load!!.getDouble(it) }, h.optInt("cpus"),
            h.optJSONObject("mem")?.optLong("total") ?: 0, h.optJSONObject("mem")?.optLong("available") ?: 0,
            h.optJSONObject("swap")?.optLong("total") ?: 0, h.optJSONObject("swap")?.optLong("free") ?: 0,
            (h.optJSONArray("disks") ?: JSONArray()).map { Disk(it.optString("root"), it.optString("path"), it.long("total"), it.long("used"), it.long("free"), it.optBoolean("ok")) },
        )
    }

    fun sessions(): List<TermSession> = arr("/api/sessions").map {
        TermSession(it.optString("name"), it.optLong("created"), it.optInt("attached"), it.optLong("activity"), it.str("agent"), it.optString("kind", "other"))
    }

    fun newShell(): String = obj("POST", "/api/shell").optString("session")
    fun closeShell(name: String) { text("DELETE", "/api/shell/${q(name)}") }
    fun screen(name: String, lines: Int = 3000): String = obj("GET", "/api/sessions/${q(name)}/screen?lines=$lines").optString("text")
    fun input(name: String, text: String, submit: Boolean = true) { obj("POST", "/api/sessions/${q(name)}/input", JSONObject().put("text", text).put("submit", submit)) }
    fun key(name: String, key: String) { obj("POST", "/api/sessions/${q(name)}/key", JSONObject().put("key", key)) }

    fun slash(): Map<String, List<SlashCmd>> = obj("GET", "/api/slash").let { o ->
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).map { SlashCmd(it.optString("cmd"), it.optString("hint")) } }
    }

    fun termTicket(session: String, readOnly: Boolean = false): String =
        obj("POST", "/api/term/ticket", JSONObject().put("session", session).put("ro", readOnly)).optString("ticket")

    fun openTerminal(ticket: String, cols: Int, rows: Int, listener: WebSocketListener): WebSocket =
        client.newWebSocket(Request.Builder().url("$BASE/ws/term?ticket=${q(ticket)}&cols=$cols&rows=$rows").build(), listener)

    // ---- remote desktop ----
    private fun desktopStatus(o: JSONObject) = DesktopStatus(o.optString("state"), o.optBoolean("ready"), o.long("memory"), o.optLong("memory_max", 1L shl 30), o.long("since"), o.optInt("viewers"), o.optInt("idle_minutes", 15))
    fun desktop(): DesktopStatus = desktopStatus(obj("GET", "/api/desktop"))
    /** "start" waits until Xvnc's socket answers (up to ~20 s on the server). */
    fun desktopAction(action: String): DesktopStatus = desktopStatus(obj("POST", "/api/desktop", JSONObject().put("action", action)))
    fun desktopTicket(): String = obj("POST", "/api/desktop/ticket").optString("ticket")
    fun openDesktop(ticket: String, listener: WebSocketListener): WebSocket =
        client.newWebSocket(Request.Builder().url("$BASE/ws/desktop?ticket=${q(ticket)}").build(), listener)

    // ---- files ----
    fun ls(root: String, path: String): List<FileEntry> = arr("/api/files/${q(root)}?path=${q(path)}").map {
        FileEntry(it.optString("name"), it.optBoolean("dir"), it.optBoolean("link"), it.optLong("size"), it.optLong("mtime"))
    }

    fun roots(): List<Disk> = arr("/api/files").map { Disk(it.optString("root"), it.optString("path"), it.long("total"), it.long("used"), it.long("free"), it.optBoolean("ok")) }
    fun readText(root: String, path: String): String = obj("GET", "/api/files/${q(root)}/text?path=${q(path)}").optString("text")

    /** Streams the file into [out]. Returns the byte count. */
    fun download(root: String, path: String, out: OutputStream, limit: Long = Long.MAX_VALUE): Long =
        call(Request.Builder().url("$BASE/api/files/${q(root)}/download?path=${q(path)}").build()).use { r ->
            val input = r.body!!.byteStream()
            val buf = ByteArray(64 * 1024)
            var n = 0L
            while (true) {
                val k = input.read(buf); if (k < 0) break
                n += k; if (n > limit) throw GatewayException(413, "too big to preview")
                out.write(buf, 0, k)
            }
            n
        }

    fun upload(root: String, path: String, size: Long, open: () -> InputStream) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) { open().use { input -> input.copyTo(sink.outputStream()) } }
        }
        call(Request.Builder().url("$BASE/api/files/${q(root)}/raw?path=${q(path)}").put(body).build()).close()
    }

    fun mkdir(root: String, path: String) { obj("POST", "/api/files/${q(root)}/mkdir", JSONObject().put("path", path)) }
    fun rename(root: String, path: String, to: String) { obj("POST", "/api/files/${q(root)}/rename", JSONObject().put("path", path).put("to", to)) }
    fun move(root: String, path: String, toRoot: String, to: String) { obj("POST", "/api/files/${q(root)}/move", JSONObject().put("path", path).put("to_root", toRoot).put("to", to)) }
    fun delete(root: String, path: String): String = obj("POST", "/api/files/${q(root)}/delete", JSONObject().put("path", path)).optString("result")

    // ---- services ----
    fun units(): List<ServiceUnit> = arr("/api/units").map {
        ServiceUnit(it.optString("unit"), it.optString("kind"), it.optString("active"), it.optString("sub"), it.optString("description"), it.optBoolean("controllable"), it.long("last"), it.long("next"))
    }

    fun unit(unit: String, lines: Int = 200): UnitDetail = obj("GET", "/api/units/${q(unit)}?lines=$lines").let { o ->
        val st = o.optJSONObject("state") ?: JSONObject()
        UnitDetail(st.keys().asSequence().associateWith { st.optString(it) }, o.optString("logs"), o.optBoolean("controllable"))
    }

    fun unitAction(unit: String, action: String) { obj("POST", "/api/units/${q(unit)}", JSONObject().put("action", action)) }

    // ---- Pantheon ----
    fun agents(): List<AgentStatus> = arr("/api/agents").map {
        AgentStatus(it.optString("id"), it.optString("name"), it.optString("kind"), it.optString("session"), it.optBoolean("alive"), it.optString("channel"), it.optBoolean("busy"), it.str("waiting"))
    }

    fun chatMsg(o: JSONObject) = ChatMsg(o.optLong("id"), o.optString("room"), o.optString("sender"), o.optString("text"), o.optDouble("ts"), o.optString("kind", "text"), o.long("reply_to"), o.str("job_id"))

    fun history(room: String, after: Long? = null, limit: Int = 100): List<ChatMsg> =
        arr("/api/chat/${q(room)}?limit=$limit" + (after?.let { "&after=$it" } ?: "")).map(::chatMsg)

    /** A message from Athena herself (an ask.request or write.request envelope), not typed by the owner. */
    fun sendAsAthena(room: String, text: String, clientId: String): ChatMsg =
        chatMsg(obj("POST", "/api/chat/${q(room)}", JSONObject().put("text", text).put("client_id", clientId).put("via", "athena")))

    fun send(room: String, text: String, clientId: String, via: String? = null): ChatMsg =
        chatMsg(obj("POST", "/api/chat/${q(room)}", JSONObject().put("text", text).put("client_id", clientId).put("channel", "typed").apply { via?.let { put("via", it) } }))

    /** A read-only question from Athena (ask.request): the gateway files it as FROM ATHENA, never as the owner. */
    fun ask(room: String, text: String, clientId: String): ChatMsg =
        chatMsg(obj("POST", "/api/chat/${q(room)}", JSONObject().put("text", text).put("client_id", clientId).put("via", "athena")))

    /** [by] "owner" marks his own request (Horologion); the gateway takes it only with his approval token. */
    fun submitJob(job: JobPolicy.Outgoing, by: String? = null): Pair<Boolean, String> = obj(
        "POST", "/api/jobs",
        JSONObject().put("job_id", job.req.jobId).put("to", job.req.executor).put("kind", job.req.kind).put("title", job.title)
            .put("payload", toJson(job.req.payload)).apply { job.approval?.let { put("approval", it); by?.let { b -> put("by", b) } } },
    ).let { it.optBoolean("approved") to it.optString("status") }

    // ---- Horologion: names, schedules and one-liners only (the gateway's helper never sends contents) ----
    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } } }.orEmpty()

    fun irisJobs(): List<dev.phonerecall.horologion.IrisJob> = arr("/api/iris/jobs").map {
        dev.phonerecall.horologion.IrisJob(it.optString("id"), it.str("cron"), it.str("status"), if (it.isNull("last_run")) null else it.opt("last_run").let { v -> if (v is Number) dev.phonerecall.horologion.Horologion.at(v.toLong() * 1000, java.time.ZoneId.of(dev.phonerecall.horologion.Horologion.ET)) else v.toString() })
    }

    fun irisSetup(): dev.phonerecall.horologion.IrisSetup = obj("GET", "/api/iris/setup").let { o ->
        dev.phonerecall.horologion.IrisSetup(
            skills = (o.optJSONArray("skills") ?: JSONArray()).map { dev.phonerecall.horologion.Skill(it.optString("name"), it.str("description"), it.optString("from", "own")) },
            hooks = (o.optJSONArray("hooks") ?: JSONArray()).map { dev.phonerecall.horologion.Hook(it.str("event"), it.str("matcher"), it.str("script"), it.optString("file")) },
            plugins = (o.optJSONArray("plugins") ?: JSONArray()).map { dev.phonerecall.horologion.Plugin(it.optString("name"), it.optBoolean("enabled")) },
            mcp = o.optJSONArray("mcp_servers").strings(),
            memory = (o.optJSONArray("memory") ?: JSONArray()).map { it.optString("file") to it.optInt("lines") },
            briefs = o.optJSONArray("briefs").strings(), channels = o.optJSONArray("channels").strings(), model = o.str("model"),
        )
    }

    fun atlasSetup(): dev.phonerecall.horologion.AtlasSetup = obj("GET", "/api/atlas/setup").let { o ->
        dev.phonerecall.horologion.AtlasSetup(o.str("version"), o.str("model"), o.str("notify"), o.str("approval"), o.str("sandbox"),
            o.optJSONArray("mcp_servers").strings(), o.optJSONArray("prompts").strings(), o.optJSONArray("skills").strings())
    }

    fun serverSetup(): dev.phonerecall.horologion.ServerSetup = obj("GET", "/api/server/setup").let { o ->
        val b = o.optJSONObject("server_backup")
        dev.phonerecall.horologion.ServerSetup(o.optJSONArray("caddy_sites").strings(), o.long("mnemosyne_last"), b?.str("result"), b?.str("finished"), o.optJSONArray("controllable_units").strings())
    }

    fun jobs(): List<WorkerJob> = arr("/api/jobs").map {
        WorkerJob(it.optString("job_id"), it.optString("to"), it.optString("kind"), it.optString("title"), it.optString("status"), it.optBoolean("approved"), it.optDouble("created"), it.optDouble("updated"), it.str("result"))
    }

    fun eventsTicket(): String = obj("POST", "/api/events/ticket").optString("ticket")
    fun openEvents(ticket: String, since: Long, listener: WebSocketListener): WebSocket =
        client.newWebSocket(Request.Builder().url("$BASE/ws/events?ticket=${q(ticket)}&since=$since").build(), listener)

    // ---- Cerberus + push ----
    fun alerts(since: Long = 0, limit: Int = 200): List<Alert> = arr("/api/alerts?since=$since&limit=$limit").map {
        Alert(it.optLong("id"), it.optDouble("ts"), it.optString("source"), it.optString("severity", "low"), it.optString("title"), it.optString("body"), it.optInt("ack") != 0 || it.optBoolean("ack"))
    }

    fun ack(id: Long) { obj("POST", "/api/alerts/$id/ack") }
    fun registerPush(token: String): Boolean = obj("POST", "/api/push/register", JSONObject().put("token", token)).optBoolean("push")

    fun inbox(since: Long): Pair<Long, List<InboxItem>> = obj("GET", "/api/inbox?since=$since").let { o ->
        o.optLong("seq", since) to (o.optJSONArray("items") ?: JSONArray()).map {
            InboxItem(it.optLong("id"), it.optString("kind"), it.optString("title"), it.optString("body"), it.optString("severity", "medium"), it.str("room"))
        }
    }

    // ---- Metron: the workers' plan windows ----
    fun usage(): String = text("GET", "/api/usage")

    // ---- Iris's tasks ----
    fun irisTasks(): List<IrisTask> = arr("/api/iris/tasks?status=pending").map {
        IrisTask(it.optLong("id"), it.optString("title"), it.optString("status"), it.str("priority"), it.str("due_date"), it.str("owner"), it.optBoolean("blocked_on_owner"), it.str("project"), it.optString("notes"), it.optBoolean("sensitive"))
    }

    fun irisDone(id: Long, approval: String) { obj("POST", "/api/iris/tasks/$id/done", JSONObject().put("approval", approval)) }
    fun irisNote(id: Long, text: String, approval: String) { obj("POST", "/api/iris/tasks/$id/note", JSONObject().put("approval", approval).put("text", text)) }

    // ---- Calypso drops (sealed by the sender; Lemnos holds ciphertext only) and Moneta's summary from Iris ----
    fun calypsoKey(pem: String) { obj("PUT", "/api/calypso/key", JSONObject().put("pem", pem)) }
    fun calypsoDrops(): List<String> = arr("/api/calypso/drops").let { a -> (0 until a.length()).map { a.getJSONObject(it).toString() } }
    fun calypsoAck(ids: List<String>) { obj("POST", "/api/calypso/drops/ack", JSONObject().put("ids", JSONArray(ids))) }
    /** Iris's money log after `since` (by row id): one page of rows, her subscriptions, and whether more follow. No full card numbers, no balances. */
    fun irisLedger(since: Long, limit: Int): String = text("GET", "/api/iris/ledger?since=$since&limit=$limit")
    /** Iris's ops on Moneta, oldest first, as their JSON envelopes. */
    fun monetaOps(): List<String> = arr("/api/moneta/ops").let { a -> (0 until a.length()).map { a.getJSONObject(it).toString() } }
    fun monetaAck(ids: List<String>) { obj("POST", "/api/moneta/ops/ack", JSONObject().put("ids", JSONArray(ids))) }
    /** Iris's trip.info messages for Argo, oldest first, as JSON. No confirmation numbers. */
    fun argoInfo(): List<String> = arr("/api/argo/info").let { a -> (0 until a.length()).map { a.getJSONObject(it).toString() } }
    fun argoAck(ids: List<String>) { obj("POST", "/api/argo/info/ack", JSONObject().put("ids", JSONArray(ids))) }

    private fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k as String, toJson(x)) } }
        is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        else -> v
    }
}
