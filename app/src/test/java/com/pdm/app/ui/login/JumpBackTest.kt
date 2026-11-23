package com.pdm.app.ui.login

import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class JumpBackTest {
    @Test
    fun captionNamesHostAndAccountOnly() {
        val session = SessionState(
            config = ServerConfig(host = "192.0.2.37", port = 8443, username = "root@pam", password = "secret"),
            username = "root@pam",
            ticket = "must-not-appear",
        )
        val caption = jumpBackCaption(session, demo = false)
        assertEquals("192.0.2.37:8443 · root@pam", caption)
        assertFalse(caption.contains("secret"))
        assertFalse(caption.contains("must-not-appear"))
        assertEquals("Demo fleet", jumpBackCaption(session, demo = true))
    }

    @Test
    fun noteDistinguishesVaultFromMemory() {
        assertFalse(jumpBackNote(secretSaved = true).contains("only in memory"))
        assertFalse(jumpBackNote(secretSaved = false).contains("stays in the device vault"))
    }
}
