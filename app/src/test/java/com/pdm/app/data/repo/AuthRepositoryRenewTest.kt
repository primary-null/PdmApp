package com.pdm.app.data.repo

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.api.PdmClientFactory
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.session.SessionStore
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryRenewTest {
    @Test
    fun only401And403RejectTheTicket() {
        assertFalse(decideRenewHttp(401, "ticket", "csrf"))
        assertFalse(decideRenewHttp(403, "ticket", "csrf"))
        assertTrue(decideRenewHttp(408, null, null))
        assertTrue(decideRenewHttp(500, null, null))
        assertTrue(decideRenewHttp(502, null, null))
        assertTrue(decideRenewHttp(200, "ticket", "csrf"))
        assertFalse(decideRenewHttp(200, null, "csrf"))
        assertFalse(decideRenewHttp(200, "ticket", null))
        assertFalse(decideRenewHttp(200, "ticket", "invalid"))
    }

    @Test
    fun thrown401And403RejectAndOtherErrorsDoNot() {
        assertTrue(renewTicketRejected(PdmHttpException(401, null, "no")))
        assertTrue(renewTicketRejected(PdmHttpException(403, null, "no")))
        assertFalse(renewTicketRejected(PdmHttpException(500, null, "down")))
        assertFalse(renewTicketRejected(PdmHttpException(502, null, "down")))
        assertFalse(renewTicketRejected(IOException("blip")))
    }

    @Test
    fun freshDemoAndTokenSessionsAreLeftAlone() = runTest {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val auth = AuthRepository(store, PdmClientFactory(store))
        store.setSession(
            SessionState(
                config = ServerConfig(host = "demo", username = "root@pdm"),
                ticket = "PDM:demo",
                csrf = "demo-csrf",
                ticketIssuedAtMs = 0L,
            ),
        )
        assertTrue(auth.renewTicketIfStale(maxAgeMs = 0))
        assertEquals("PDM:demo", store.snapshot()?.state?.ticket)

        store.setSession(
            SessionState(
                config = ServerConfig(host = "192.0.2.10", authMode = AuthMode.API_TOKEN, apiToken = "root@pdm!ops:secret"),
            ),
        )
        assertTrue(auth.renewTicketIfStale(maxAgeMs = 0))

        store.setSession(
            SessionState(
                config = ServerConfig(host = "192.0.2.10", username = "root@pam"),
                ticket = "PDM:live",
                csrf = "csrf",
                ticketIssuedAtMs = System.currentTimeMillis(),
            ),
        )
        assertTrue(auth.renewTicketIfStale())
        assertEquals("PDM:live", store.snapshot()?.state?.ticket)
    }
}
