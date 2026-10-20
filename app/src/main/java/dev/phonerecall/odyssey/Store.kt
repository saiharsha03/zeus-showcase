package dev.phonerecall.odyssey

import android.content.Context
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.Sealer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Odyssey's state on the phone: one JSON document, sealed with AES-256-GCM under an Aegis Keystore key
 * (no fingerprint needed, so the receivers and the drive service can write it in the background, but
 * nothing is ever on disk in the clear). In no-backup storage, so it never leaves in a backup either.
 * One process-wide flow, so the screen, the receivers and the service see the same thing.
 */
object OdysseyStore {
    private const val FILE = "odyssey.sealed"
    private val _state = MutableStateFlow(OdysseyData())
    val state: StateFlow<OdysseyData> = _state.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    internal val sealer: Sealer by lazy { KeystoreSealer("zeus.aegis.odyssey") }

    private fun file(c: Context) = File(c.noBackupFilesDir, FILE)

    fun load(c: Context): OdysseyData = synchronized(lock) {
        if (loaded) return _state.value
        val f = file(c)
        val d = if (f.exists()) runCatching { decode(sealer, f.readBytes()) }.getOrNull() ?: OdysseyData() else OdysseyData()
        _state.value = d
        loaded = true
        d
    }

    fun update(c: Context, change: (OdysseyData) -> OdysseyData): OdysseyData = synchronized(lock) {
        val next = change(load(c))
        _state.value = next
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(encode(sealer, next))
        tmp.renameTo(f)
        next
    }.also { runCatching { dev.phonerecall.widgets.WidgetBus.section(c, dev.phonerecall.widgets.WKind.DRIVE) } }

    /** "Forget everything": the file is deleted, the key stays (it seals nothing else). */
    fun wipe(c: Context) = synchronized(lock) {
        file(c).delete()
        _state.value = OdysseyData(settings = _state.value.settings.copy(on = false))
        update(c) { it }
    }

    fun encode(s: Sealer, d: OdysseyData): ByteArray = s.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(d))
    fun decode(s: Sealer, bytes: ByteArray): OdysseyData? = s.openBytes(bytes)?.let { dev.phonerecall.mind.stateJson.readValue(it, OdysseyData::class.java) }
}
