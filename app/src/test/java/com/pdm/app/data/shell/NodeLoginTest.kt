package com.pdm.app.data.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeLoginTest {
    /** Feed output the way the session does: through the prompt watcher, chunk by chunk. */
    private class Tty(val login: NodeLogin) {
        private val watch = PromptWatch()
        val typed = mutableListOf<String>()
        fun out(text: String) {
            login.onOutput(text, watch.feed(text.toByteArray()))?.let { typed += it }
        }
    }

    @Test
    fun signsInAsRootThenStops() {
        val t = Tty(NodeLogin("root", "s3cret"))
        t.out("\r\nNODE-C login: ")
        assertEquals(listOf("root\r"), t.typed)
        t.out("root\r\n")
        t.out("Password: ")
        assertEquals(listOf("root\r", "s3cret\r"), t.typed)
        t.out("\r\nLinux NODE-C 6.17.2-1-pve\r\n")
        t.out("\u001b[01;31mroot@NODE-C\u001b[00m:~# ")
        assertEquals(NodeLogin.State.SIGNED_IN, t.login.state)
        assertEquals(2, t.typed.size)
    }

    @Test
    fun aPromptSplitOverTwoFramesIsAnsweredOnce() {
        val t = Tty(NodeLogin("root", "pw"))
        t.out("NODE-C login:")
        t.out(" ")
        assertEquals(listOf("root\r"), t.typed)
        t.out("root\r\nPassword:")
        t.out(" ")
        assertEquals(listOf("root\r", "pw\r"), t.typed)
        assertEquals(NodeLogin.State.SENT_PASSWORD, t.login.state)
    }

    @Test
    fun aWrongPasswordIsNeverRetried() {
        val t = Tty(NodeLogin("root", "wrong"))
        t.out("NODE-C login: ")
        t.out("root\r\nPassword: ")
        t.out("\r\n\r\nLogin incorrect\r\nNODE-C login: ")
        assertEquals(NodeLogin.State.REFUSED, t.login.state)
        t.out("\r\nNODE-C login: ")
        assertEquals("username and password typed once each, nothing after the refusal", 2, t.typed.size)
    }

    @Test
    fun anExistingRootShellNeedsNoLogin() {
        val t = Tty(NodeLogin("root", "pw"))
        t.out("root@NODE-C:~# ")
        assertEquals(NodeLogin.State.SIGNED_IN, t.login.state)
        assertEquals(emptyList<String>(), t.typed)
        // A later "Password:" (sudo, passwd) is not ours to answer.
        t.out("\r\nNew password: ")
        assertEquals(emptyList<String>(), t.typed)
    }

    @Test
    fun noPasswordPromptBeforeTheUserIsSent() {
        val login = NodeLogin("root", "pw")
        assertNull(login.onOutput("Password: ", PromptKind.PASSWORD))
        assertEquals(NodeLogin.State.WAITING, login.state)
    }
}
