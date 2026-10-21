package dev.phonerecall

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Unit tests run on the JVM's regex engine; the phone runs Android's ICU one. ICU rejects the (?U) inline flag
 * (UNICODE_CHARACTER_CLASS), so a pattern using it passes every test here and then fails to load on the phone
 * (Agora's lookalike rule did, 2026-10-20). No main source may use it.
 */
class AndroidRegexTest {
    @Test fun noRegexFlagIcuRejects() {
        val main = File(System.getProperty("user.dir"), "src/main/java")
        val bad = Regex("""\(\?[a-zA-Z]*U[a-zA-Z]*[):]|UNICODE_CHARACTER_CLASS""")
        val hits = main.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l)) "${f.name}:${i + 1}" else null } }
            .toList()
        assertEquals(emptyList<String>(), hits)
    }
}
