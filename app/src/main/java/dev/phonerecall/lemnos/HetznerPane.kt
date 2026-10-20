package dev.phonerecall.lemnos

import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.phonerecall.brain.SealedSecret
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.FactRow
import dev.phonerecall.ui.Kicker
import dev.phonerecall.ui.Line
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.PrimaryAction
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.TextAction
import dev.phonerecall.ui.Tone
import dev.phonerecall.ui.Type
import dev.phonerecall.ui.UnderlineField
import dev.phonerecall.ui.topRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Hetzner token's slot in Calypso. */
fun hetznerToken(c: Context) = SealedSecret(c, "hetzner")

/**
 * Calypso's row for the Hetzner token (Calypso is already unlocked when this shows). Never shown in full:
 * the last four only. Lemnos's Hetzner tab reads it; nothing else does.
 */
@Composable
fun HetznerTokenRow() {
    val ctx = LocalContext.current
    val c = Athena.colors
    val scope = rememberCoroutineScope()
    var masked by remember { mutableStateOf<String?>(null) }
    var set by remember { mutableStateOf(false) }
    var entering by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val load = { scope.launch { val t = withContext(Dispatchers.IO) { hetznerToken(ctx).read() }; set = t != null; masked = t?.let { "•".repeat(12) + it.takeLast(4) } } }
    LaunchedEffect(Unit) { load() }
    LaunchedEffect(confirmDelete) { if (confirmDelete) { kotlinx.coroutines.delay(4000); confirmDelete = false } }
    Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Line("Hetzner", Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                Line("Reboot and power cycle from ${Place.VPS.myth}, straight to Hetzner. Only you can use it.", Type.small, c.ink3)
            }
            StatusWord(if (set) "Set" else "Not set", if (set) Tone.OK else Tone.QUIET)
        }
        if (set) FactRow("Token", masked ?: "Reading")
        if (entering) {
            UnderlineField(draft, { draft = it.trim() }, "Hetzner API token", Modifier.fillMaxWidth(), keyboard = KeyboardType.Password,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row {
                TextAction("Save", { if (draft.length >= 32) scope.launch { withContext(Dispatchers.IO) { hetznerToken(ctx).save(draft) }; draft = ""; entering = false; load() } }, emphasis = true, enabled = draft.length >= 32)
                TextAction("Cancel", { draft = ""; entering = false })
            }
        } else Row {
            TextAction(if (set) "Rotate" else "Add token", { entering = true }, emphasis = !set)
            if (set) TextAction(if (confirmDelete) "Tap again to delete" else "Delete", {
                if (confirmDelete) scope.launch { withContext(Dispatchers.IO) { hetznerToken(ctx).delete() }; confirmDelete = false; load() } else confirmDelete = true
            })
        }
    }
}

/** Strong biometric only: tier 4 never falls back to the PIN. */
private fun strongPrompt(c: Context, title: String, onOk: () -> Unit, onFail: (String) -> Unit) {
    BiometricPrompt.Builder(c).setTitle(title).setSubtitle("Tier 4: this restarts the whole server")
        .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG)
        .setNegativeButton("Cancel", c.mainExecutor) { _, _ -> onFail("Cancelled") }
        .build()
        .authenticate(CancellationSignal(), c.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        })
}

/**
 * Hetzner: the box's power, straight to Hetzner's API with the owner's token from Calypso, so it works when
 * Lemnos itself is down. Reboot and power cycle need the typed word and then his fingerprint.
 */
@Composable
fun HetznerTab(gutter: Dp, health: Health?, openVault: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hasToken by remember { mutableStateOf<Boolean?>(null) }
    var server by remember { mutableStateOf<Hetzner.Server?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val load: () -> Unit = {
        scope.launch {
            val token = withContext(Dispatchers.IO) { hetznerToken(ctx).read() }
            hasToken = token != null
            if (token != null) message = try { server = withContext(Dispatchers.IO) { Hetzner.box(token) }; if (server == null) "No server with ${Hetzner.BOX_IP} in this project." else null } catch (e: Exception) { e.message }
        }
    }
    LaunchedEffect(Unit) { load() }
    HetznerContent(gutter, hasToken, server, health, message, openVault, onRefresh = load) { action ->
        strongPrompt(ctx, "${action.label} the server", onOk = {
            scope.launch {
                message = try {
                    val token = withContext(Dispatchers.IO) { hetznerToken(ctx).read() } ?: error("No token")
                    "${action.label}: " + withContext(Dispatchers.IO) { Hetzner.act(token, server!!.id, action) }
                } catch (e: Exception) { "${action.label} failed: ${e.message}" }
                kotlinx.coroutines.delay(4000); load()
            }
        }, onFail = { message = it })
    }
}

@Composable
fun HetznerContent(
    gutter: Dp, hasToken: Boolean?, server: Hetzner.Server?, health: Health?, message: String?,
    openVault: () -> Unit, onRefresh: () -> Unit, onAct: (Hetzner.Action) -> Unit,
) {
    val c = Athena.colors
    var arming by remember { mutableStateOf<Hetzner.Action?>(null) }
    var typed by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = gutter).widthIn(max = 620.dp)) {
        if (hasToken == false) {
            Spacer(Modifier.height(Space.l))
            Line("Add your Hetzner API token in ${Place.VAULT.myth}.", Type.title, c.ink)
            Line("A project token with read and write. It stays sealed on this phone and goes only to api.hetzner.cloud.", Type.small, c.ink3)
            Spacer(Modifier.height(Space.l))
            PrimaryAction("Open ${Place.VAULT.myth}", openVault)
            return@Column
        }
        Kicker("The box", Modifier.padding(top = Space.m, bottom = Space.xs))
        if (server == null) Line(message ?: "Asking Hetzner", Type.small, if (message != null) c.alert else c.ink3)
        server?.let { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Line(s.name, Type.title, c.ink, Modifier.weight(1f))
                StatusWord(s.status, if (s.status == "running") Tone.OK else Tone.ALERT)
            }
            FactRow("Type", "${s.type}, ${s.datacenter}")
            FactRow("Address", s.ip)
            health?.let { h -> FactRow("Load", h.load.joinToString("  ") { "%.2f".format(it) } + "  on ${h.cpus} cores"); FactRow("Memory", "${Fmt.bytes(h.memAvailable)} free of ${Fmt.bytes(h.memTotal)}") }
            message?.let { Line(it, Type.small, c.ink2, Modifier.padding(vertical = Space.xs)) }
            Row { TextAction("Refresh", onRefresh) }

            Kicker("Power", Modifier.padding(top = Space.xl, bottom = Space.xs))
            val actions = if (s.status == "off") listOf(Hetzner.Action.POWER_ON) else listOf(Hetzner.Action.REBOOT, Hetzner.Action.RESET)
            for (a in actions) Column(Modifier.fillMaxWidth().topRule(c.rule).padding(vertical = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Line(a.label, Type.body.copy(fontWeight = Type.action.fontWeight), c.ink)
                        Line(when (a) {
                            Hetzner.Action.REBOOT -> "A clean restart. Iris, Atlas and every service stop for a minute or two."
                            Hetzner.Action.RESET -> "Pulls the power. Only when the box is frozen: unsaved work on it is lost."
                            Hetzner.Action.POWER_ON -> "Starts the box again."
                        }, Type.small, c.ink3)
                    }
                    if (arming != a) TextAction("Start", { arming = a; typed = "" })
                }
                if (arming == a) {
                    Line("Type “${a.word}”, then your fingerprint.", Type.small, c.ink2, Modifier.padding(top = Space.s))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UnderlineField(typed, { typed = it }, a.word, Modifier.weight(1f), keyboard = KeyboardType.Ascii)
                        TextAction("Cancel", { arming = null })
                        TextAction(a.label, { onAct(a); arming = null }, emphasis = true, enabled = typed.trim().equals(a.word, ignoreCase = true))
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.xxl))
    }
}
