package com.pdm.app.data.shell

import com.pdm.app.data.model.Permissions
import com.pdm.app.data.model.canOpenHostShell
import com.pdm.app.data.model.canOpenNodeShell
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TermProtocolTest {
    @Test
    fun inputCountsUtf8BytesNotChars() {
        assertEquals("0:16:apt full-upgrade", TermProtocol.input("apt full-upgrade"))
        assertEquals("0:1:\r", TermProtocol.input("\r"))
        // "é" is two bytes; termproxy reads exactly the announced length.
        assertEquals("0:3:é\n", TermProtocol.input("é\n"))
    }

    @Test
    fun resizePingAndLoginMatchTheWebClient() {
        assertEquals("1:120:40:", TermProtocol.resize(120, 40))
        assertEquals("2", TermProtocol.PING)
        assertEquals("root@pam:PDMTERM::abc\n", TermProtocol.login("root@pam", "PDMTERM::abc"))
    }

    @Test
    fun handshakeStripsOkEvenWhenSplitOrGlued() {
        val split = TermHandshake()
        assertArrayEquals(ByteArray(0), split.feed("O".toByteArray()))
        assertFalse(split.accepted)
        assertArrayEquals("motd".toByteArray(), split.feed("Kmotd".toByteArray()))
        assertTrue(split.accepted)
        assertArrayEquals("OK again".toByteArray(), split.feed("OK again".toByteArray()))

        val glued = TermHandshake()
        assertArrayEquals("root@node-c:~# ".toByteArray(), glued.feed("OKroot@node-c:~# ".toByteArray()))
    }

    @Test
    fun handshakeShowsAnUnexpectedOpeningInsteadOfSwallowingIt() {
        val h = TermHandshake()
        assertArrayEquals("login: ".toByteArray(), h.feed("login: ".toByteArray()))
        assertTrue(h.accepted)
        val one = TermHandshake()
        assertArrayEquals("x".toByteArray(), one.feed("x".toByteArray()))
    }

    @Test
    fun bufferReplaysFromAnyOffsetAndDropsTheOldest() {
        val b = ShellBuffer(capacity = 8)
        b.append("hello".toByteArray())
        assertEquals("hello", String(b.readFrom(0).first))
        val (tail, next) = b.readFrom(3)
        assertEquals("lo", String(tail))
        assertEquals(5L, next)
        b.append("world".toByteArray())
        assertEquals(10L, b.end)
        // Only the last 8 bytes are kept; an older offset starts at the oldest kept byte.
        assertEquals("llowor" + "ld", String(b.readFrom(0).first))
        assertEquals(0, b.readFrom(10).first.size)
    }

    @Test
    fun promptWatchTellsLoginFromAShellPrompt() {
        val w = PromptWatch()
        // What NODE-C really sent through a non-root PDM link.
        assertEquals(PromptKind.LOGIN, w.feed("\r\nNODE-C login: ".toByteArray()))
        assertEquals(PromptKind.PASSWORD, w.feed("root\r\nPassword: ".toByteArray()))
        assertEquals(null, w.feed("\r\nLinux NODE-C 6.17.2-1-pve\r\n".toByteArray()))
        // Coloured bash prompt, split across frames.
        assertEquals(null, w.feed("\u001b]0;root@NODE-C: ~\u0007\u001b[01;31mroot@NODE-C".toByteArray()))
        assertEquals(PromptKind.SHELL, w.feed("\u001b[00m:~# ".toByteArray()))
        assertEquals(PromptKind.SHELL, PromptWatch().feed("user@host:~$ ".toByteArray()))
        assertEquals(null, PromptWatch().feed("Reading package lists... 50%\r".toByteArray()))
    }

    @Test
    fun aTimedOutSignInIsRecognised() {
        // What NODE-C printed when nobody signed in for a minute, with and without a trailing newline.
        val w = PromptWatch()
        assertEquals(PromptKind.LOGIN, w.feed("NODE-C login: ".toByteArray()))
        assertEquals(PromptKind.LOGIN_TIMED_OUT, w.feed("login: timed out after 60 seconds".toByteArray()))
        val w2 = PromptWatch()
        w2.feed("NODE-C login: ".toByteArray())
        assertEquals(PromptKind.LOGIN_TIMED_OUT, w2.feed("login: timed out after 60 seconds\r\n".toByteArray()))
        // A program merely talking about timeouts is not a dead terminal.
        assertEquals(PromptKind.SHELL, PromptWatch().feed("curl: (28) Connection timed out after 60001 ms\r\nroot@NODE-C:~# ".toByteArray()))
        assertEquals(null, PromptWatch().feed("request timed out after 60 seconds\r\n".toByteArray()))
    }

    @Test
    fun shellsNeedSysConsole() {
        val node = Permissions(mapOf("/resource/r/node/n1" to mapOf("Sys.Console" to false)))
        assertTrue(node.canOpenNodeShell("r", "n1"))
        assertFalse(node.canOpenNodeShell("r", "n2"))
        assertFalse(node.canOpenHostShell())
        val admin = Permissions(mapOf("/" to mapOf("Sys.Console" to true)))
        assertTrue(admin.canOpenNodeShell("r", "n2"))
        assertTrue(admin.canOpenHostShell())
        assertTrue((null as Permissions?).canOpenNodeShell("r", "n1"))
    }
}
