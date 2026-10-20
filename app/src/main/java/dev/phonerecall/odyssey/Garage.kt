package dev.phonerecall.odyssey

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.phonerecall.brain.BrainCore
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Gateway
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.UUID

/**
 * Athena's asks to Iris. Argo's generic ask_worker plugs in as [IrisAsks.worker]; until it does, the
 * default sends the envelope into Iris's room on Lemnos (marked as Athena's, not typed by the owner) and
 * reads her ask.reply messages back from the same room. No channel (not paired): nothing is sent.
 */
interface IrisAsk {
    /** Sends one envelope (ask.request, or a low-authority write.request). False when it couldn't go. */
    fun send(id: String, envelope: String): Boolean

    /** Iris's ask.reply texts that arrived since the last call. */
    fun replies(): List<String>
}

object IrisAsks {
    @Volatile var worker: IrisAsk? = null
    fun current(c: Context): IrisAsk? = worker ?: if (DeviceKeys.paired(c)) RoomAsk(c.applicationContext) else null
}

/** The default: Iris's room on Lemnos, with a cursor so each reply is read once. */
private class RoomAsk(private val app: Context) : IrisAsk {
    private val prefs = app.getSharedPreferences("iris_ask", Context.MODE_PRIVATE)

    override fun send(id: String, envelope: String): Boolean = runCatching {
        val m = Gateway.sendAsAthena("iris", envelope, id)
        if (prefs.getLong("cursor", 0L) == 0L) prefs.edit().putLong("cursor", m.id).apply()
        true
    }.getOrDefault(false)

    override fun replies(): List<String> = runCatching {
        val after = prefs.getLong("cursor", 0L)
        val msgs = Gateway.history("iris", after = after.takeIf { it > 0 }, limit = 100)
        msgs.maxOfOrNull { it.id }?.let { prefs.edit().putLong("cursor", maxOf(after, it)).apply() }
        msgs.filter { it.text.contains("ask.reply") }.map { it.text }
    }.getOrDefault(emptyList())
}

/** The car on Android: the fuel stop job, Iris's answers, his confirm, Moneta, the lease ask. Deciding is in Car.kt. */
object Garage {
    private const val TAG = "Zeus-Car"

    /** A fuel stop (a FUEL place, or a fuel payment during a stay): one open fill-up, one ask to Iris, a short polling job. */
    fun fuelStop(c: Context, stayStart: Long, place: String) {
        val askId = "ask-fuel-" + UUID.randomUUID().toString().take(8)
        var opened = false
        OdysseyStore.update(c) { d ->
            val car = FuelStop.open(d.car, stayStart, place, askId) ?: return@update d
            opened = true
            val f = FuelStop.fromMoneta(car.fillups.last(), runCatching { dev.phonerecall.moneta.MonetaCore.get(c).state.value.txns }.getOrDefault(emptyList()))
            d.copy(car = car.copy(fillups = car.fillups.dropLast(1) + f))
        }
        if (!opened) return
        BrainCore.get(c).changed()
        val q = IrisEnvelope.fuelQuestion(place, stayStart, ZoneId.systemDefault())
        if (IrisAsks.current(c)?.send(askId, IrisEnvelope.request(askId, "fuel", q)) == true) AskWork.start(c)
    }

    /** The lease, once: Iris looks first; he is asked only for what she couldn't find. */
    fun askLease(c: Context) {
        if (OdysseyStore.load(c).car.leaseAsked > 0) return
        val id = "ask-lease-" + UUID.randomUUID().toString().take(8)
        if (IrisAsks.current(c)?.send(id, IrisEnvelope.request(id, "car_lease", IrisEnvelope.LEASE_QUESTION)) != true) return
        val now = System.currentTimeMillis()
        OdysseyStore.update(c) { d -> d.copy(car = d.car.copy(leaseAsked = now, asks = d.car.asks + (id to "car_lease"))) }
        AskWork.start(c)
    }

    /** Reads Iris's new replies; true while asks are still open. */
    fun poll(c: Context): Boolean {
        val ask = IrisAsks.current(c) ?: return false
        for (r in ask.replies()) reply(c, r)
        return OdysseyStore.load(c).car.asks.isNotEmpty()
    }

    /** One ask.reply: fills the fill-up or the lease it answers. Unknown ids are ignored. */
    fun reply(c: Context, text: String) {
        val (id, answer) = IrisEnvelope.reply(text) ?: return
        var hit = false
        OdysseyStore.update(c) { d ->
            val what = d.car.asks[id] ?: return@update d
            hit = true
            val car = d.car.copy(asks = d.car.asks - id)
            when {
                what == "car_lease" -> d.copy(car = car.copy(lease = IrisEnvelope.lease(car.lease, answer), leaseAnswered = System.currentTimeMillis()))
                what.startsWith("fuel:") -> d.copy(car = car.copy(fillups = car.fillups.map { if (it.id == what.removePrefix("fuel:")) IrisEnvelope.fuel(it, answer) else it }))
                else -> d.copy(car = car)
            }
        }
        if (hit) BrainCore.get(c).changed()
    }

    /**
     * His confirm: the odometer is stored here, the fill-up goes to Iris's fillups table (a low-authority
     * write.request she checks and applies) and to Moneta as Fuel (the bank alert's row is recategorized
     * when it is already there, so nothing is counted twice).
     */
    fun confirm(c: Context, id: String, odometer: Int, source: String) {
        var done: FillUp? = null
        OdysseyStore.update(c) { d ->
            val f = d.car.fillups.firstOrNull { it.id == id } ?: return@update d
            val logged = f.copy(odometer = odometer, state = "logged")
            done = logged
            d.copy(car = d.car.copy(fillups = d.car.fillups.map { if (it.id == id) logged else it }, readings = (d.car.readings + Reading(System.currentTimeMillis(), odometer, source)).takeLast(400)))
        }
        val f = done ?: return
        val writeId = "write-fuel-" + f.id
        IrisAsks.current(c)?.send(writeId, IrisEnvelope.fillupWrite(writeId, f, ZoneId.systemDefault()))
        runCatching {
            val moneta = dev.phonerecall.moneta.MonetaCore.get(c)
            val row = FuelStop.match(f, moneta.state.value.txns)
            when {
                row != null && row.category != dev.phonerecall.moneta.Category.FUEL -> moneta.edit(dev.phonerecall.moneta.MonetaOp.Recategorize(row.ref, dev.phonerecall.moneta.Category.FUEL))
                row == null && f.cents != null -> moneta.edit(dev.phonerecall.moneta.MonetaOp.Add("fuel:${f.id}", dev.phonerecall.moneta.TxnFields(f.at, f.cents, f.place, dev.phonerecall.moneta.Category.FUEL, dev.phonerecall.moneta.TxnKind.SPEND, f.card?.let { Regex("""\d{4}""").find(it)?.value })))
                else -> Unit
            }
        }.onFailure { Log.w(TAG, "Moneta: ${it.message}") }
        BrainCore.get(c).audit.log("The owner", "fill-up", "${f.place}: odometer logged")
        BrainCore.get(c).changed()
    }

    fun skip(c: Context, id: String) {
        OdysseyStore.update(c) { d -> d.copy(car = d.car.copy(fillups = d.car.fillups.map { if (it.id == id) it.copy(state = "skipped") else it })) }
        BrainCore.get(c).changed()
    }

    /** His edits on the Car tab: a field he changes is his, no longer "from Iris". */
    fun setLease(c: Context, l: Lease) {
        OdysseyStore.update(c) { d ->
            val old = d.car.lease
            val changed = listOf(
                "start" to (old.start != l.start), "termMonths" to (old.termMonths != l.termMonths), "allowance" to (old.allowance != l.allowance),
                "startOdometer" to (old.startOdometer != l.startOdometer), "overageCents" to (old.overageCents != l.overageCents), "lessor" to (old.lessor != l.lessor),
                "car" to (old.car != l.car), "signed" to (old.signed != l.signed), "delivered" to (old.delivered != l.delivered),
            ).filter { it.second }.map { it.first }.toSet()
            d.copy(car = d.car.copy(lease = l.copy(fromIris = old.fromIris - changed)))
        }
        BrainCore.get(c).changed()
    }

    /** "Ask Iris again" on the Car tab. */
    fun reaskLease(c: Context) {
        OdysseyStore.update(c) { d -> d.copy(car = d.car.copy(leaseAsked = 0, asks = d.car.asks.filterValues { it != "car_lease" })) }
        askLease(c)
    }

    /** "Snap dashboard": ML Kit text recognition on the phone (Play services' model); the photo never leaves it. */
    fun readOdometer(bmp: Bitmap, last: Int?, done: (Int?) -> Unit) {
        val rec = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t -> done(Odometer.parse(t.text, last)); rec.close() }
            .addOnFailureListener { done(null); rec.close() }
    }
}

/** A short job while Iris's answers are pending: every 30 seconds for up to 15 minutes; the 15-minute tick catches later ones. */
class AskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = dev.phonerecall.clio.Clio.work(this) { runWork() }
    private suspend fun runWork(): Result {
        repeat(30) {
            if (!runCatching { Garage.poll(applicationContext) }.getOrDefault(false)) return Result.success()
            delay(30_000L)
        }
        return Result.success()
    }
}

object AskWork {
    fun start(c: Context) {
        WorkManager.getInstance(c).enqueueUniqueWork(
            "zeus-iris-asks", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AskWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }
}
