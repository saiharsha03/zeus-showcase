package dev.phonerecall.brain

import android.graphics.Bitmap
import dev.phonerecall.data.Task
import dev.phonerecall.data.TaskList
import org.robolectric.RuntimeEnvironment
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64

/** Seals by reversing and base64: never the plain text on disk, and reversible for assertions. */
class FakeSealer : Sealer {
    override fun seal(plain: String) = "S:" + Base64.getEncoder().encodeToString(plain.reversed().toByteArray())
    override fun open(sealed: String) = if (sealed.startsWith("S:")) String(Base64.getDecoder().decode(sealed.removePrefix("S:"))).reversed() else null
    override fun sealBytes(plain: ByteArray) = plain.reversedArray()
    override fun openBytes(sealed: ByteArray) = sealed.reversedArray()
}

class FakeTasks : TaskHands {
    val rows = LinkedHashMap<Long, Task>()
    private var next = 1L
    override fun add(title: String, due: Long?, dueHasTime: Boolean): Long {
        val id = next++
        rows[id] = Task(id, title, TaskList.INBOX, null, due, dueHasTime, 0.0, false, null, false)
        return id
    }
    fun addTask(title: String, list: TaskList = TaskList.INBOX, due: Long? = null): Long {
        val id = add(title, due, false); rows[id] = rows.getValue(id).copy(list = list); return id
    }
    override fun delete(id: Long) { rows.remove(id) }
    override fun complete(id: Long, now: Long) { rows[id]?.let { rows[id] = it.copy(list = TaskList.DONE, doneAt = now) } }
    override fun uncomplete(id: Long) { rows[id]?.let { rows[id] = it.copy(list = TaskList.INBOX, doneAt = null) } }
    override fun open(): List<Task> = rows.values.filter { it.list != TaskList.DONE }
}

class FakeEffects : SenseEffects {
    var onScreen = false
    val nudges = ArrayList<String>()
    val triage = ArrayList<Long>()
    val known = HashSet<String>()
    var changes = 0
    override fun foreground() = onScreen
    override fun nudge(key: String, title: String, body: String) { nudges += title }
    override fun scheduleTriage(delayMs: Long) { triage += delayMs }
    override fun knownBirthday(name: String, month: Int, day: Int) = name.lowercase() in known
    override fun changed() { changes++ }
    /** Metron's routing rule, faked: what workerHold answers for a worker. */
    var hold: (String) -> String? = { null }
    override fun workerHold(worker: String) = hold(worker)
}

/** A model that records every prompt it was given and answers from a script. */
class FakeCloud(var answer: (String) -> String = { "[]" }) : CloudModel {
    val prompts = ArrayList<String>()
    override fun complete(feature: String, system: String, user: String, maxTokens: Int): String { prompts += user; return answer(user) }
}

class FakeLocal(var answer: (String) -> String? = { null }) : LocalModel {
    val prompts = ArrayList<String>()
    override suspend fun generate(feature: String, prompt: String, maxTokens: Int, image: Bitmap?): String? { prompts += prompt; return answer(prompt) }
}

/** In-memory section persistence with the same semantics as SenseStore's. */
class MemorySections : SectionPersistence {
    val rows = LinkedHashMap<Long, Section>()
    val log = LinkedHashMap<Long, SectionChange>()
    val openItems = HashMap<Long, Int>()
    private var next = 1L
    private var nextChange = 1L
    override fun sections() = rows.values.toList()
    override fun insertSection(s: Section): Long { val id = next++; rows[id] = s.copy(id = id); return id }
    override fun updateSection(s: Section) { rows[s.id] = s }
    override fun openItemCount(sectionId: Long) = openItems[sectionId] ?: 0
    override fun logChange(c: SectionChange): Long { val id = nextChange++; log[id] = c.copy(id = id); return id }
    override fun changes(limit: Int) = log.values.toList().asReversed().take(limit)
    override fun change(id: Long) = log[id]
    override fun markUndone(id: Long) { log[id]?.let { log[id] = it.copy(undone = true) } }
}

/** A real SenseEngine over a real SenseStore (Robolectric SQLite, in memory) with fakes around it. */
class EngineRig(start: ZonedDateTime = ZonedDateTime.of(2026, 10, 19, 15, 0, 0, 0, ZoneId.of("America/New_York"))) {
    var now = start.toInstant().toEpochMilli()
    val zone: ZoneId = start.zone
    val store = SenseStore(RuntimeEnvironment.getApplication(), FakeSealer(), name = null)
    val audit = ArrayList<String>()
    val auditLog = Audit { a, b, c -> audit += "$a $b $c" }
    val sections = SectionRegistry(store, auditLog) { now }
    val tasks = FakeTasks()
    val proposals = MemoryStore()
    val state = object : LocalState by FakeState() {
        override fun listItems(section: String) = sections.byName(section)?.let { s -> store.items(s.id).filter { !it.done }.map { it.text } }.orEmpty()
    }
    val fates = Fates(proposals, state, FakeExecutor(), auditLog, { now })
    val effects = FakeEffects()
    val local = FakeLocal()
    var cloud: FakeCloud? = FakeCloud()
    val engine = SenseEngine(store, sections, tasks, fates, auditLog, effects, local, { cloud }, { now }, { zone })

    fun note(pkg: String, title: String, text: String, key: String = "k${now}", group: Boolean = false, conversation: String? = null, sender: String? = null) =
        Incoming(key, pkg, APPS[pkg] ?: "App", title, text, now, sender, conversation, group)

    companion object {
        val APPS = mapOf(
            "com.whatsapp" to "WhatsApp", "com.google.android.dialer" to "Phone", "com.google.android.apps.messaging" to "Messages",
            "com.microsoft.teams" to "Teams", "com.chase.sig.android" to "Chase", "com.x8bit.bitwarden" to "Bitwarden",
        )
    }

    fun pending() = proposals.all().filter { it.status == ProposalStatus.PENDING }
}
