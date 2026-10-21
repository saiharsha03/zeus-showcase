package dev.phonerecall.lemnos

import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.TlsGuard
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Hetzner Cloud, direct from the phone (not through the box), so reboot and power cycle work when the box
 * is down. The token is the owner's, kept sealed in Calypso; it never goes to Lemnos or an agent.
 * Reboot and power cycle are tier 4: the screen asks for his fingerprint and a typed word first.
 */
object Hetzner {
    private const val API = "https://${NetGate.HETZNER_HOST}/v1"
    /** The box Lemnos runs on, picked out of the project by its address. */
    const val BOX_IP = "203.0.113.10"

    data class Server(val id: Long, val name: String, val status: String, val ip: String, val datacenter: String, val type: String)

    /** reboot = ACPI restart; reset = power cycle (hard). */
    enum class Action(val path: String, val label: String, val word: String) {
        REBOOT("reboot", "Reboot", "reboot"),
        RESET("reset", "Power cycle", "power cycle"),
        POWER_ON("poweron", "Power on", "power on"),
    }

    private val client by lazy { OkHttpClient.Builder().sslSocketFactory(TlsGuard.sslSocketFactory, TlsGuard.trustManager).addInterceptor(NetGate.okHttp).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build() }

    private fun req(token: String, path: String) = Request.Builder().url(API + path).header("Authorization", "Bearer $token")

    private fun run(r: Request): JSONObject = client.newCall(r).execute().use { res ->
        val body = res.body?.string().orEmpty()
        if (!res.isSuccessful) {
            val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
            throw GatewayException(res.code, msg.ifEmpty { "Hetzner answered ${res.code}" })
        }
        JSONObject(body.ifEmpty { "{}" })
    }

    /** The box, or the project's only server. Blocking. */
    fun box(token: String): Server? {
        val list = run(req(token, "/servers?per_page=50").get().build()).optJSONArray("servers") ?: return null
        val all = (0 until list.length()).map { list.getJSONObject(it) }.map {
            Server(
                it.optLong("id"), it.optString("name"), it.optString("status"),
                it.optJSONObject("public_net")?.optJSONObject("ipv4")?.optString("ip").orEmpty(),
                it.optJSONObject("datacenter")?.optString("name").orEmpty(), it.optJSONObject("server_type")?.optString("name").orEmpty(),
            )
        }
        return all.firstOrNull { it.ip == BOX_IP } ?: all.singleOrNull()
    }

    /** Starts the action; Hetzner answers at once with the action's status ("running"). Blocking. */
    fun act(token: String, serverId: Long, a: Action): String =
        run(req(token, "/servers/$serverId/actions/${a.path}").post("{}".toRequestBody("application/json".toMediaType())).build())
            .optJSONObject("action")?.optString("status").orEmpty()
}
