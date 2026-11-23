package com.pdm.app.data.shell

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.session.SessionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetsTest {
    @Test
    fun proxmoxCommandsOnlyWhereTheyExist() {
        val node = Snippets.builtins(ShellTarget.RemoteNode("DEMO-CLUSTER", "NODE-A")).map { it.command }
        val host = Snippets.builtins(ShellTarget.Host("pdm", upgrade = false)).map { it.command }
        assertTrue("qm list" in node && "df -h" in node)
        assertFalse("PDM host has no qm/pct/pvesm", host.any { it.startsWith("qm") || it.startsWith("pct") || it.startsWith("pve") })
        assertTrue("df -h" in host)
        val ids = (node + host).size
        assertEquals("built-in ids are unique", Snippets.builtins(ShellTarget.RemoteNode("r", "n")).map { it.id }.toSet().size,
            Snippets.builtins(ShellTarget.RemoteNode("r", "n")).size)
        assertTrue(ids > 0)
    }

    @Test
    fun nothingEverRunsByItself() {
        assertEquals("nvidia-smi", Snippets.typed("nvidia-smi\n"))
        assertEquals("cd /tmp ls -la", Snippets.typed("cd /tmp\r\n  ls -la  \r\n"))
        assertFalse(Snippets.typed("a\nb\r").contains('\n') || Snippets.typed("a\nb\r").contains('\r'))
    }

    @Test
    fun controlAndFormatCharactersBecomeOneLine() {
        listOf("\u000f", "\u0004", "\u001b[201~", "\u0003", "\u202e", "\u2028", "ls\t-la").forEach { raw ->
            val typed = Snippets.typed("echo $raw")
            assertFalse(typed.contains('\n') || typed.contains('\r'))
            assertTrue(typed.none { ch ->
                val kind = Character.getType(ch)
                kind == Character.CONTROL.toInt() ||
                    kind == Character.FORMAT.toInt() ||
                    kind == Character.LINE_SEPARATOR.toInt() ||
                    kind == Character.PARAGRAPH_SEPARATOR.toInt()
            })
            assertFalse(typed.contains("  "))
        }
        assertEquals("[201~", Snippets.typed("\u001b[201~"))
        assertEquals("ls -la", Snippets.typed("ls\t-la"))
        assertNull(Snippets.saved("x", "bad", "\u0003\u202e"))
    }

    @Test
    fun savingNeedsACommandAndNamesItselfWhenBlank() {
        assertNull(Snippets.saved("x", "GPU", "   \n "))
        val s = Snippets.saved("x", "  ", "nvidia-smi --query-gpu=utilization.gpu --format=csv\n")!!
        assertEquals("nvidia-smi --query-gpu=utilizati", s.title)
        assertEquals("nvidia-smi --query-gpu=utilization.gpu --format=csv", s.command)
    }

    @Test
    fun savedSnippetsSurviveTheStore() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        assertTrue(store.savedSnippets().isEmpty())
        val mine = listOf(Snippet("mine_1", "GPU", "nvidia-smi"))
        store.saveSnippets(mine)
        assertEquals(mine, store.savedSnippets())
    }
}
