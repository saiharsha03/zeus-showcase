package dev.phonerecall.prometheus

import com.fasterxml.jackson.databind.JsonNode
import dev.phonerecall.brain.FieldCat
import dev.phonerecall.brain.NetGate
import dev.phonerecall.brain.TlsGuard
import dev.phonerecall.brain.Outbound
import dev.phonerecall.clio.Clio
import dev.phonerecall.mind.stateJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The GitHub calls the phone makes for Prometheus. Blocking; call on IO. */
interface GitHub {
    /** The PR whose head is `branch` on your-github-user/zeus, open or closed, or null. */
    fun pull(branch: String): Pull?
    /** The latest run of `workflow` on commit `sha` (ci.yml), or the release run titled for `sha` (release.yml). */
    fun run(workflow: String, sha: String): CiRun?
    /** Squash-merges PR `number` only if its head is still `sha`. Returns the merge commit. */
    fun merge(number: Int, sha: String): String
    /** Starts release.yml on main for the merged commit `sha`. */
    fun dispatchRelease(sha: String)
    /** The Release whose target is `sha`, or null. */
    fun release(sha: String): Release?
    /** Streams the asset into `out` (at most `limit` bytes) and returns its SHA-256 in hex. */
    fun download(assetId: Long, out: File, limit: Long): String
}

/** GitHub's JSON, read with Jackson (plain JVM, so unit tests need no Android). */
object GitHubJson {
    fun pull(n: JsonNode): Pull = Pull(
        number = n.path("number").asInt(), url = n.path("html_url").asText(), title = n.path("title").asText().take(200),
        state = n.path("state").asText(), merged = !n.path("merged_at").isNull && n.path("merged_at").isTextual,
        headSha = n.path("head").path("sha").asText(), mergeSha = n.path("merge_commit_sha").takeIf { it.isTextual }?.asText(),
    )

    /** The newest run in a runs list (optionally only those titled `title`). */
    fun run(n: JsonNode, title: String? = null): CiRun? {
        val r = n.path("workflow_runs").firstOrNull { title == null || it.path("display_title").asText() == title } ?: return null
        val state = when {
            r.path("status").asText() != "completed" -> Ci.RUNNING
            r.path("conclusion").asText() == "success" -> Ci.GREEN
            else -> Ci.RED
        }
        return CiRun(state, r.path("html_url").asText().ifEmpty { null })
    }

    fun release(n: JsonNode): Release = Release(
        tag = n.path("tag_name").asText(), name = n.path("name").asText(), author = n.path("author").path("login").asText(),
        target = n.path("target_commitish").asText(), draft = n.path("draft").asBoolean(), body = n.path("body").asText().take(20_000),
        url = n.path("html_url").asText(),
        assets = n.path("assets").map { a ->
            Asset(a.path("id").asLong(), a.path("name").asText(), a.path("size").asLong(), a.path("digest").takeIf { it.isTextual }?.asText(), a.path("uploader").path("login").asText())
        },
    )
}

/**
 * The real client: the phone-only release token, NetGate on every request, and redirects followed by hand so
 * an APK download may only land on [ASSET_HOST], without the token. Every call is in Clio and Outbound.
 */
class GitHubApi(private val token: () -> String?, private val base: String = "https://api.github.com") : GitHub {
    private val repo = "/repos/${Repo.OWNER}/${Repo.NAME}"
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(TlsGuard.sslSocketFactory, TlsGuard.trustManager)
        .addInterceptor(NetGate.okHttp)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun req(path: String): Request.Builder {
        val t = token() ?: throw IllegalStateException("Add the GitHub release token in Calypso")
        return Request.Builder().url(base + path)
            .header("Authorization", "Bearer $t").header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun send(r: Request, what: String): String = Outbound.tagged("Prometheus (GitHub)", setOf(FieldCat.KEY)) {
        client.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { stateJson.readTree(body).path("message").asText() }.getOrNull().orEmpty()
                Clio.warn("prometheus", "github $what", "${resp.code} ${msg.take(160)}")
                throw IllegalStateException("GitHub said ${resp.code}${if (msg.isNotEmpty()) ": $msg" else ""}")
            }
            Clio.info("prometheus", "github $what", "${resp.code}")
            body
        }
    }

    private fun get(path: String, what: String): JsonNode = stateJson.readTree(send(req(path).get().build(), what))

    private val JSON = "application/json".toMediaType()

    override fun pull(branch: String): Pull? =
        get("$repo/pulls?head=${Repo.OWNER}:$branch&state=all&per_page=5", "pull").firstOrNull()?.let { GitHubJson.pull(it) }

    override fun run(workflow: String, sha: String): CiRun? = if (workflow == Repo.RELEASE) {
        GitHubJson.run(get("$repo/actions/workflows/${Repo.RELEASE}/runs?event=workflow_dispatch&per_page=20", "release run"), "release $sha")
    } else {
        GitHubJson.run(get("$repo/actions/workflows/$workflow/runs?head_sha=$sha&per_page=5", "ci run"))
    }

    override fun merge(number: Int, sha: String): String {
        val body = stateJson.writeValueAsString(mapOf("sha" to sha, "merge_method" to "squash")).toRequestBody(JSON)
        return stateJson.readTree(send(req("$repo/pulls/$number/merge").put(body).build(), "merge #$number")).path("sha").asText()
            .ifEmpty { throw IllegalStateException("GitHub merged without a commit id") }
    }

    override fun dispatchRelease(sha: String) {
        val body = stateJson.writeValueAsString(mapOf("ref" to "main", "inputs" to mapOf("sha" to sha))).toRequestBody(JSON)
        send(req("$repo/actions/workflows/${Repo.RELEASE}/dispatches").post(body).build(), "release dispatch")
    }

    override fun release(sha: String): Release? =
        get("$repo/releases?per_page=10", "releases").map { GitHubJson.release(it) }.firstOrNull { it.target == sha }

    override fun download(assetId: Long, out: File, limit: Long): String {
        val first = req("$repo/releases/assets/$assetId").header("Accept", "application/octet-stream").build()
        val location = Outbound.tagged("Prometheus (GitHub)", setOf(FieldCat.KEY)) {
            client.newCall(first).execute().use { r ->
                if (r.code !in 300..399) throw IllegalStateException("GitHub answered ${r.code} for the APK")
                r.header("Location") ?: throw IllegalStateException("GitHub gave no download address")
            }
        }
        if (!assetHost(location)) throw IllegalStateException("The download points somewhere other than GitHub's release storage")
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        // No Authorization header here: the signed address is the permission, and the token stays on api.github.com.
        Outbound.tagged("Prometheus update download", emptySet()) {
            client.newCall(Request.Builder().url(location).get().build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("The download failed (${r.code})")
                out.outputStream().use { o ->
                    val input = r.body!!.byteStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val k = input.read(buf); if (k < 0) break
                        n += k; if (n > limit) { o.close(); out.delete(); throw IllegalStateException("The APK is larger than ${limit / 1024 / 1024} MB") }
                        md.update(buf, 0, k); o.write(buf, 0, k)
                    }
                }
            }
        }
        Clio.info("prometheus", "downloaded", "asset $assetId, $n bytes")
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val ASSET_HOST = "release-assets.githubusercontent.com"

        /** The redirect may only go to GitHub's release storage, over https on 443. */
        fun assetHost(url: String): Boolean = runCatching { URI(url) }.getOrNull()?.let { it.host?.lowercase() == ASSET_HOST } == true && NetGate.allows(url)
    }
}
