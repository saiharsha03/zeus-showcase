package dev.phonerecall.rhythms

import android.content.Context
import dev.phonerecall.brain.KeystoreSealer
import dev.phonerecall.brain.Sealer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Rhythms' state on the phone: one JSON document sealed with AES-256-GCM under its own Aegis Keystore key
 * (no fingerprint, so the receivers and the worker can write it), in no-backup storage. Same shape as
 * OdysseyStore: one process-wide flow for the page, the receivers and the listener.
 */
object RhythmsStore {
    private const val FILE = "rhythms.sealed"
    private val _state = MutableStateFlow(RhythmsData())
    val state: StateFlow<RhythmsData> = _state.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()
    private val sealer: Sealer by lazy { KeystoreSealer("zeus.aegis.rhythms") }

    private fun file(c: Context) = File(c.noBackupFilesDir, FILE)

    fun load(c: Context): RhythmsData = synchronized(lock) {
        if (loaded) return _state.value
        val f = file(c)
        val d = if (f.exists()) runCatching { decode(sealer, f.readBytes()) }.getOrNull() ?: RhythmsData() else RhythmsData()
        _state.value = d
        loaded = true
        d
    }

    fun update(c: Context, change: (RhythmsData) -> RhythmsData): RhythmsData = synchronized(lock) {
        val next = change(load(c))
        _state.value = next
        val f = file(c)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeBytes(encode(sealer, next))
        tmp.renameTo(f)
        next
    }

    fun encode(s: Sealer, d: RhythmsData): ByteArray = s.sealBytes(dev.phonerecall.mind.stateJson.writeValueAsBytes(d))
    fun decode(s: Sealer, bytes: ByteArray): RhythmsData? = s.openBytes(bytes)?.let { dev.phonerecall.mind.stateJson.readValue(it, RhythmsData::class.java) }
}
