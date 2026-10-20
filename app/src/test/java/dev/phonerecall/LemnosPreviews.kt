package dev.phonerecall

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.phonerecall.lemnos.AgentStatus
import dev.phonerecall.lemnos.Alert
import dev.phonerecall.lemnos.CerberusContent
import dev.phonerecall.lemnos.ChatMsg
import dev.phonerecall.lemnos.ChatPane
import dev.phonerecall.lemnos.DeviceKeys
import dev.phonerecall.lemnos.Disk
import dev.phonerecall.lemnos.FileEntry
import dev.phonerecall.lemnos.FileSheet
import dev.phonerecall.lemnos.FilesContent
import dev.phonerecall.lemnos.Health
import dev.phonerecall.lemnos.Hetzner
import dev.phonerecall.lemnos.HetznerContent
import dev.phonerecall.lemnos.IrisTask
import dev.phonerecall.lemnos.LemnosState
import dev.phonerecall.lemnos.PairingPanel
import dev.phonerecall.lemnos.Preview
import dev.phonerecall.lemnos.ServiceUnit
import dev.phonerecall.lemnos.ServicesContent
import dev.phonerecall.lemnos.SlashCmd
import dev.phonerecall.lemnos.TaskActions
import dev.phonerecall.lemnos.TermActions
import dev.phonerecall.lemnos.TermSession
import dev.phonerecall.lemnos.TerminalContent
import dev.phonerecall.lemnos.TerminalPane
import dev.phonerecall.lemnos.TextTabs
import dev.phonerecall.lemnos.UnitContent
import dev.phonerecall.lemnos.UnitDetail
import dev.phonerecall.lemnos.WorkerJob
import dev.phonerecall.lemnos.liveItems
import dev.phonerecall.ui.Athena
import dev.phonerecall.ui.AthenaTheme
import dev.phonerecall.ui.Place
import dev.phonerecall.ui.ScreenHeader
import dev.phonerecall.ui.Space
import dev.phonerecall.ui.StatusWord
import dev.phonerecall.ui.Tone
import androidx.compose.ui.unit.Dp

/** Fixed Lemnos, Pantheon and Cerberus screens for the screenshot test. Made-up data, nothing real. */
object LemnosPreviews {
    private val now = 1_792_000_000.0

    private val sessions = listOf(
        TermSession("claude-auto", 0, 1, 0, "iris", "claude"),
        TermSession("codex", 0, 0, 0, "atlas", "codex"),
        TermSession("codex-bridge", 0, 0, 0, null, "other"),
        TermSession("shell-1", 0, 0, 0, null, "shell"),
    )
    private val slash = mapOf(
        "claude" to listOf("/compact", "/clear", "/model", "/cost", "/status", "/usage", "/resume").map { SlashCmd(it, "") },
        "codex" to listOf("/compact", "/new", "/model", "/status", "/diff").map { SlashCmd(it, "") },
    )
    private val screen = listOf(
        "● Bash(du -sh ~/projects/* | sort -h)",
        "  ⎿  0.9G desk-app",
        "     1.5G weather-app",
        "",
        "● The volume has 41 GB free. Nothing to move.",
        "",
        "✻ Thinking (12s, esc to interrupt)",
        "──────────────────────────────────────────",
        "❯ ",
    )
    private val health = Health("zeus-box", 12 * 86_400L + 3600, listOf(0.42, 0.51, 0.48), 2, 3_819L shl 20, 2_370L shl 20, 6_143L shl 20, 5_520L shl 20,
        listOf(Disk("home", "/home/agent", 38L shl 30, 24L shl 30, 12L shl 30, true)))

    private val agents = listOf(
        AgentStatus("iris", "Iris", "claude", "claude-auto", true, "not-enabled", false, null),
        AgentStatus("atlas", "Atlas", "codex", "codex", true, "tmux", true, "atlas"),
    )
    private val jobs = listOf(
        WorkerJob("j1", "iris", "iris.research", "Find the winter tire deals nearby", "sent", false, now - 600, now - 300, null),
        WorkerJob("j2", "atlas", "atlas.check", "Check why the backup timer ran late", "done", false, now - 7200, now - 5400, "Borg ran at 06:02; the delay was the storage box mount. All archives OK."),
    )
    private val tasks = listOf(
        IrisTask(1, "Renew the car registration", "pending", "high", "2026-10-12", "owner", true, "car", "Form MV-82 is in Documents", false),
        IrisTask(2, "Private task", "pending", "medium", "2026-10-30", "iris", false, null, "", true),
        IrisTask(3, "Send the lease renewal questions", "pending", "medium", null, "iris", false, "home", "", false),
    )
    private val chat = listOf(
        ChatMsg(1, "atlas", "owner", "Is the backup healthy?", now - 4000, "text", null, null),
        ChatMsg(2, "atlas", "atlas", "Yes. Borg ran at 06:02 and checked 42 archives, newest this morning. The storage box mount was slow, which made it 40 minutes late.", now - 3900, "text", 1, null),
        ChatMsg(3, "atlas", "athena", "Job: Check why the backup timer ran late (suggestion)", now - 3000, "job", null, "j2"),
        ChatMsg(4, "atlas", "owner", "Thanks. Keep an eye on the mount tonight", now - 120, "text", null, null),
    )
    private val state = LemnosState(pairing = DeviceKeys.State.PAIRED, unlocked = true, online = true, health = health, sessions = sessions, slash = slash, agents = agents, jobs = jobs, tasks = tasks, syncedAt = (now * 1000).toLong())

    private val alerts = listOf(
        Alert(9, now - 240, "ssh", "high", "SSH sign-in from an address you haven't used", "root from 203.0.113.7 (not a trusted address)", false),
        Alert(8, now - 3600, "cve", "medium", "2 new CVE alerts", "openssl 3.0.13: CVE-2026-1234 (high), fixed in 3.0.13-0ubuntu3.6", false),
        Alert(7, now - 7200, "scan", "medium", "Security scan: reboot pending", "Kernel update waiting for a reboot. Ports match the baseline.", false),
        Alert(6, now - 9000, "media", "low", "Plexbot: download finished", "Severance S02E04 added to the library", false),
        Alert(5, now - 20000, "backup", "low", "Backup OK", "borg-backup.service finished at 06:02", true),
    )

    @Composable private fun Page(dark: Boolean = true, content: @Composable () -> Unit) = AthenaTheme(dark) {
        Box(Modifier.fillMaxSize().background(Athena.colors.surface)) { content() }
    }

    @Composable private fun Lemnos(gutter: Dp, tab: Int, body: @Composable () -> Unit) = Column(Modifier.fillMaxSize()) {
        ScreenHeader(Place.VPS.myth, gutter, emblem = Place.VPS, subtitle = Place.VPS.motto) { StatusWord("Up 12 days", Tone.OK) }
        TextTabs(dev.phonerecall.lemnos.LEMNOS_TABS, tab, {}, gutter)
        Box(Modifier.weight(1f)) { body() }
    }

    @Composable fun Pairing() = Page {
        Column { ScreenHeader(Place.VPS.myth, Space.gutterNarrow, emblem = Place.VPS, subtitle = Place.VPS.motto); PairingPanel(DeviceKeys.State.WAITING, null, Space.gutterNarrow, {}, {}) }
    }

    @Composable fun TerminalCover() = Page {
        Lemnos(Space.gutterNarrow, 0) { TerminalContent(sessions, sessions[0], slash, "Live", false, Space.gutterNarrow, TermActions()) { m -> TerminalPane(null, m, screen) } }
    }

    @Composable fun TerminalInner() = Page {
        Lemnos(Space.gutterWide, 0) { TerminalContent(sessions, sessions[1], slash, "Live", true, Space.gutterWide, TermActions()) { m -> TerminalPane(null, m, screen) } }
    }

    private val desk = dev.phonerecall.lemnos.DesktopStatus("active", true, 412L shl 20, 1L shl 30, 1_792_000_000L, 1, 15)

    @Composable fun DesktopCover() = Page {
        Lemnos(Space.gutterNarrow, 1) {
            dev.phonerecall.lemnos.DesktopContent(desk, true, null, "", null, false, Space.gutterNarrow, dev.phonerecall.lemnos.DesktopActions(), size = "1600×1200") { m -> dev.phonerecall.lemnos.DesktopPreviewPane(m) }
        }
    }

    @Composable fun DesktopInner() = Page {
        Lemnos(Space.gutterWide, 1) {
            dev.phonerecall.lemnos.DesktopContent(desk, true, null, "", "copied", true, Space.gutterWide, dev.phonerecall.lemnos.DesktopActions(),
                setOf(dev.phonerecall.lemnos.Keysym.CONTROL_L), "1600×1200") { m -> dev.phonerecall.lemnos.DesktopPreviewPane(m) }
        }
    }

    @Composable fun DesktopOff() = Page {
        Lemnos(Space.gutterNarrow, 1) {
            dev.phonerecall.lemnos.DesktopContent(dev.phonerecall.lemnos.DesktopStatus("inactive", false, null, 1L shl 30, null, 0, 15), false, null, "", null, false, Space.gutterNarrow, dev.phonerecall.lemnos.DesktopActions()) {}
        }
    }

    @Composable fun Files() = Page {
        Lemnos(Space.gutterNarrow, 2) {
            FilesContent("home", "projects", listOf(
                FileEntry("desk-app", true, false, 4096, 1_791_900_000), FileEntry("weather-app", true, false, 4096, 1_791_000_000),
                FileEntry("notes.md", false, false, 2_340, 1_791_990_000), FileEntry("photos-export.zip", false, false, 48_200_000, 1_791_500_000),
                FileEntry("current", false, true, 12, 1_791_500_000),
            ), null, Space.gutterNarrow, {}, {}, {}, {}, {})
        }
    }

    @Composable fun FileOpen() = Page {
        Lemnos(Space.gutterNarrow, 2) {
            FileSheet("home", "projects/notes.md", FileEntry("notes.md", false, false, 2_340, 1_791_990_000),
                Preview.Text("# Notes\n\n- move hub-restart to the volume\n- check the borg prune schedule\n- renew the TLS certificate? (Caddy does it)\n"), Space.gutterNarrow, {}, {}, {}, { _, _ -> }, {})
        }
    }

    @Composable fun Services() = Page {
        Lemnos(Space.gutterNarrow, 3) {
            ServicesContent(listOf(
                ServiceUnit("iris-gateway.service", "service", "active", "running", "Iris Gateway daemon", false, null, null),
                ServiceUnit("zeus-gateway.service", "service", "active", "running", "Zeus gateway (Lemnos)", false, null, null),
                ServiceUnit("jellyfin.service", "service", "active", "running", "Jellyfin Media Server", true, null, null),
                ServiceUnit("filebrowser.service", "service", "active", "running", "FileBrowser - Iris Files web GUI", true, null, null),
                ServiceUnit("cve-watch.service", "service", "inactive", "dead", "Daily CVE watch", true, null, null),
                ServiceUnit("old-bot.service", "service", "failed", "failed", "A retired bot", false, null, null),
            ), Space.gutterNarrow) {}
        }
    }

    @Composable fun Unit() = Page {
        Lemnos(Space.gutterNarrow, 3) {
            UnitContent("jellyfin.service", UnitDetail(mapOf("ActiveState" to "active", "SubState" to "running", "Result" to "success", "ActiveEnterTimestamp" to "Sat 2026-10-17 04:12:09 UTC"),
                (1..14).joinToString("\n") { "2026-10-19T21:%02d:10+00:00 jellyfin[2002]: [INF] Playback stopped by owner on Fold".format(it) }, true), Space.gutterNarrow, {}, {})
        }
    }

    @Composable fun HetznerTab() = Page {
        Lemnos(Space.gutterNarrow, 4) { HetznerContent(Space.gutterNarrow, true, Hetzner.Server(1, "zeus-box", "running", "203.0.113.10", "dc-1", "cx22"), health, null, {}, {}, {}) }
    }

    @Composable fun PantheonCover() = Page {
        dev.phonerecall.mind.PantheonContent(Space.gutterNarrow, emptyMap(), dev.phonerecall.home.SampleData.workers, live = { liveItems(state, null, {}, TaskActions(), null) })
    }

    @Composable fun Chat() = Page { ChatPane("atlas", chat, agents, Space.gutterNarrow, onBack = {}) {} }

    @Composable fun ChatSlash() = Page {
        ChatPane("iris", chat.map { it.copy(room = "iris", sender = if (it.sender == "atlas") "iris" else it.sender) }, agents, Space.gutterNarrow, onBack = {},
            banner = { dev.phonerecall.lemnos.SlashBanner(dev.phonerecall.lemnos.ChatRoute.of("iris", "/compact", agents), null, {}, {}) }) {}
    }

    @Composable fun PantheonInner() = Page {
        Row(Modifier.fillMaxSize()) {
            dev.phonerecall.mind.PantheonContent(Space.gutterWide, emptyMap(), dev.phonerecall.home.SampleData.workers, Modifier.weight(1f), live = { liveItems(state, "atlas", {}, TaskActions(), null) })
            ChatPane("atlas", chat, agents, Space.gutterWide, Modifier.weight(1.25f), onBack = null) {}
        }
    }

    @Composable fun Cerberus() = Page { CerberusContent(alerts, true, (now * 1000).toLong(), Space.gutterNarrow, {}, {}) }
}
