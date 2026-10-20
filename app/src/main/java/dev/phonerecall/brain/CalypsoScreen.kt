package dev.phonerecall.brain

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.subtitle
import dev.phonerecall.ui.title
import dev.phonerecall.ui.topRule
import dev.phonerecall.vault.Shelf
import dev.phonerecall.vault.ShieldedItems
import dev.phonerecall.vault.VaultContent
import dev.phonerecall.vault.VaultCore
import dev.phonerecall.vault.VaultLogRow
import dev.phonerecall.vault.mergedLog
import dev.phonerecall.vault.rememberBiometric
import dev.phonerecall.vault.rememberShielded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Strong (Class 3) biometrics, or the device PIN/pattern as the fallback. */
private fun authenticate(context: Context, onOk: () -> Unit, onFail: (String) -> Unit) {
    val prompt = BiometricPrompt.Builder(context)
        .setTitle("Open ${Place.VAULT.myth}")
        .setSubtitle("Your keys are behind your fingerprint")
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
        .build()
    prompt.authenticate(
        CancellationSignal(), context.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        },
    )
}

/**
 * Calypso, one screen with two shelves. Each shelf keeps its own lock, as before the merge: Sealed opens
 * with the prompt below and each item with its own fingerprint; Shielded has its own gate. Screenshots
 * and the recents preview are blocked on both (FLAG_SECURE), and each locks again after 60 s in the
 * background or when the screen goes off. The brain uses the keys without any of this.
 */
@Composable
fun CalypsoScreen(hub: BrainHub, gutter: Dp, wide: Boolean, shelf: Shelf, onShelf: (Shelf) -> Unit) {
    val context = LocalContext.current
    val vault = remember { VaultCore.get(context) }
    val bio = rememberBiometric()
    val settings by hub.settings.collectAsStateWithLifecycle()
    var unlocked by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var stoppedAt by remember { mutableLongStateOf(0L) }
    var masked by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf(false) }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    // Anything that moved on either shelf: both reload, and so does the log.
    var changes by remember { mutableIntStateOf(0) }
    var sealedLog by remember { mutableStateOf<List<VaultLogRow>>(emptyList()) }
    val shielded = rememberShielded(changes)

    DisposableEffect(Unit) {
        val w = context.activity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    val unlock = {
        failure = null
        authenticate(context, onOk = { unlocked = true; hub.calypsoOpened() }, onFail = { failure = it })
    }
    // The shelf in view asks for its own fingerprint when it is locked.
    LaunchedEffect(shelf) {
        when (shelf) {
            Shelf.SEALED -> if (!unlocked) unlock()
            Shelf.SHIELDED -> if (!shielded.gate.unlocked) shielded.unlock(bio)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        stoppedAt = System.currentTimeMillis()
        // Screen off: lock now, not after 60 s.
        if (context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false) { unlocked = false; masked = null; revealed = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 60_000L) { unlocked = false; masked = null; revealed = false }
    }
    LaunchedEffect(unlocked, settings.keySet, revealed) {
        if (unlocked && settings.keySet) { hub.maskedKey(revealed) { masked = it }; hub.keySavedAt { savedAt = it } }
    }
    LaunchedEffect(unlocked, changes) {
        if (!unlocked) { sealedLog = emptyList(); return@LaunchedEffect }
        val l = withContext(Dispatchers.IO) { vault.calypso.log() }
        sealedLog = l
    }
    // A revealed key hides itself again after 30 s.
    LaunchedEffect(revealed) { if (revealed) { delay(30_000L); revealed = false } }

    val open = if (shelf == Shelf.SEALED) unlocked else shielded.gate.unlocked
    VaultContent(
        gutter, wide, shelf, onShelf,
        unlocked = open,
        failure = if (shelf == Shelf.SEALED) failure else shielded.gate.failure,
        onUnlock = { if (shelf == Shelf.SEALED) unlock() else shielded.unlock(bio) },
        log = mergedLog(sealedLog.takeIf { unlocked }, shielded.log.takeIf { shielded.gate.unlocked }),
        otherLocked = if (shelf == Shelf.SEALED) !shielded.gate.unlocked else !unlocked,
        locked = {
            if (shelf == Shelf.SEALED) dev.phonerecall.vault.CalypsoDrop()
            else Line("Athena and the workers can still use what you share with them. Every use is logged here.", Type.small, Athena.colors.ink3, Modifier.padding(top = Space.l))
        },
        items = {
            if (shelf == Shelf.SEALED) SealedShelf(
                settings.keySet, masked, savedAt, revealed,
                onReveal = { revealed = !revealed },
                onCopy = { hub.copyKey() },
                onSave = { hub.saveKey(it); revealed = false },
                onDelete = { hub.deleteKey(); masked = null },
                extra = { dev.phonerecall.lemnos.HetznerTokenRow(); dev.phonerecall.odyssey.RoadKeyRows(); dev.phonerecall.voice.GeminiKeyRow(); dev.phonerecall.prometheus.GitHubKeyRows() },
                sealed = { dev.phonerecall.vault.CalypsoSealed(changes, onLog = { sealedLog = it }, onChange = { changes++ }) },
            ) else ShieldedItems(shielded) { changes++ }
        },
    )
}

/** The Sealed shelf, unlocked, without the hub or the prompt (for the screenshot test): the drop box items, then the keys Zeus uses. */
@Composable
fun SealedShelf(
    keySet: Boolean,
    masked: String?,
    savedAt: Long?,
    revealed: Boolean,
    onReveal: () -> Unit,
    onCopy: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    /** More sealed keys (Hetzner, TomTom and 511NY, Gemini, the two GitHub tokens), below the Anthropic key. */
    extra: @Composable () -> Unit = {},
    /** The drop box's sealed items, above the keys. */
    sealed: @Composable () -> Unit = {},
) {
    val c = Athena.colors
    var entering by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { delay(4000); confirmDelete = false } }
    LaunchedEffect(copied) { if (copied) { delay(30_000L); copied = false } }

    Column(Modifier.fillMaxWidth()) {
        sealed()
        Kicker("Keys Zeus uses", Modifier.padding(top = Space.xl, bottom = Space.s))
        Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Line("Anthropic", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                    Line("Athena's brain uses it without asking. Seeing or changing it needs you.", Type.small, c.ink3)
                }
                StatusWord(if (keySet) "Set" else "Not set", if (keySet) Tone.OK else Tone.ACTIVE)
            }
            if (keySet) {
                Spacer(Modifier.height(Space.s))
                FactRow("Key", masked ?: "Reading")
                savedAt?.let { FactRow("Saved", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))) }
                if (copied) FactRow("Clipboard", "Copied. Cleared in 30 s.")
            }
            if (entering) {
                Spacer(Modifier.height(Space.s))
                UnderlineField(
                    draft, { draft = it.trim() }, "sk-ant-...", Modifier.fillMaxWidth(),
                    keyboard = KeyboardType.Password, visualTransformation = PasswordVisualTransformation(),
                )
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(Space.xs)) {
                    TextAction("Save", { if (draft.length >= 20) { onSave(draft); draft = ""; entering = false } }, emphasis = true, enabled = draft.length >= 20)
                    TextAction("Cancel", { draft = ""; entering = false })
                }
            } else {
                Row(Modifier.heightIn(min = 44.dp).padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    if (keySet) {
                        TextAction(if (revealed) "Hide" else "Reveal", onReveal)
                        TextAction("Copy", { onCopy(); copied = true })
                        TextAction("Rotate", { entering = true })
                        Spacer(Modifier.width(Space.xs))
                        TextAction(if (confirmDelete) "Tap again to delete" else "Delete", {
                            if (confirmDelete) { onDelete(); confirmDelete = false } else confirmDelete = true
                        })
                    } else {
                        TextAction("Add key", { entering = true }, emphasis = true)
                    }
                }
            }
        }
        extra()
        Line(
            "Stored encrypted with a key in Android Keystore that never leaves this phone. Never shown in full. Every open, reveal and copy is in the log.",
            Type.small, c.ink3, Modifier.padding(top = Space.l).widthIn(max = 560.dp),
        )
    }
}
