package dev.phonerecall.lemnos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Slash commands in Pantheon chat go to the agent's terminal (after a confirm), never over the channel. */
class ChatRouteTest {
    private val agents = listOf(AgentStatus("iris", "Iris", "claude", "claude-auto", true, "connected", false, null))

    @Test fun plainTextIsAChatMessage() {
        assertEquals(ChatRoute.Chat("what's on tomorrow?"), ChatRoute.of("iris", " what's on tomorrow? ", agents))
        assertEquals(ChatRoute.Chat("a/b is fine"), ChatRoute.of("group", "a/b is fine"))
    }

    @Test fun slashInOneToOneTargetsThatAgentsSession() {
        assertEquals(ChatRoute.Terminal("iris", "claude-auto", "/compact"), ChatRoute.of("iris", "/compact", agents))
        // Before /api/agents answers, the known session names are used.
        assertEquals(ChatRoute.Terminal("atlas", "codex", "/model gpt"), ChatRoute.of("atlas", "/model gpt"))
        // Only the first line is typed: a newline would press Enter early.
        assertEquals("/status", (ChatRoute.of("atlas", "/status\nand then rm -rf") as ChatRoute.Terminal).command)
    }

    @Test fun slashInTheGroupIsRefused() {
        val r = ChatRoute.of("group", "/clear", agents)
        assertTrue(r is ChatRoute.Refused && r.hint.contains("Open"))
        assertTrue(ChatRoute.of("echo", "/help") is ChatRoute.Refused)
    }
}
