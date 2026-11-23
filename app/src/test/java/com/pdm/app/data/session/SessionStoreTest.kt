package com.pdm.app.data.session

import com.pdm.app.data.FakeSharedPreferences
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {
    @Test
    fun purgeAllClearsSessionFormAndPins() {
        val prefs = FakeSharedPreferences()
        val store = SessionStore(injectedPrefs = prefs)
        store.saveLastForm(
            ServerConfig(
                host = "192.0.2.37",
                port = 8443,
                authMode = AuthMode.PASSWORD,
                username = "root@pam",
                password = "unit-test",
            ),
            saveSecret = true,
        )
        store.saveCertPin("192.0.2.37", "AA:BB:CC:DD")
        store.setSession(
            SessionState(
                config = ServerConfig(host = "192.0.2.37", username = "root@pam"),
                ticket = "PDM:test",
            ),
        )
        assertEquals("unit-test", store.loadLastForm().savedSecret)
        assertEquals(1, store.listCertPins().size)

        store.purgeAll()

        assertNull(store.session.value)
        assertEquals("", store.loadLastForm().host)
        assertEquals("", store.loadLastForm().savedSecret)
        assertTrue(store.listCertPins().isEmpty())
    }

    @Test
    fun renewedTicketKeepsGenerationAndInFlightSnapshots() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val config = ServerConfig(host = "192.0.2.37", username = "root@pam")
        store.setSession(SessionState(config = config, ticket = "PDM:old"))
        val inFlight = store.snapshot()!!

        assertTrue(store.replaceTicketIfCurrent(inFlight, inFlight.state.copy(ticket = "PDM:new")))
        assertTrue(store.isCurrent(inFlight))
        assertEquals("PDM:new", store.currentSession(inFlight)?.ticket)

        val other = SessionState(config = config.copy(username = "ops@pdm"), ticket = "PDM:x")
        assertEquals(false, store.replaceTicketIfCurrent(inFlight, other))

        store.clearSession()
        assertEquals(false, store.replaceTicketIfCurrent(inFlight, inFlight.state))
    }

    @Test
    fun listCertPinsIgnoresBlankAndSorts() {
        val prefs = FakeSharedPreferences()
        val store = SessionStore(injectedPrefs = prefs)
        store.saveCertPin("b.example", "BB")
        store.saveCertPin("a.example", "AA")
        val pins = store.listCertPins()
        assertEquals(listOf("a.example", "b.example"), pins.map { it.host })
    }

    @Test
    fun twoServersStaySeparateThroughSelectForgetAndRemove() {
        val prefs = FakeSharedPreferences()
        val store = SessionStore(injectedPrefs = prefs)
        val a = ServerConfig(host = "a.example", username = "ops@pam", password = "test-a")
        val b = a.copy(host = "b.example", password = "test-b")
        store.saveLastForm(a, true)
        store.saveLastForm(b, true)
        assertEquals(2, store.savedConnections().size)
        val aid = store.savedConnections().first { it.host == a.host }.id
        val bid = store.savedConnections().first { it.host == b.host }.id
        assertEquals("test-a", store.selectConnection(aid)!!.savedSecret)
        // Using a row only fills the form: the last successful login (b) stays the last login.
        assertEquals(b.host, store.loadLastForm().host)
        store.clearSavedSecret(SessionIdentity.of(a))
        assertEquals("", store.selectConnection(aid)!!.savedSecret)
        assertEquals("test-b", store.loadLastForm().savedSecret)
        assertEquals("test-b", store.selectConnection(bid)!!.savedSecret)
        store.removeConnection(aid)
        assertEquals(listOf(b.host), store.savedConnections().map { it.host })
        assertEquals("test-b", store.loadLastForm().savedSecret)
        store.purgeAll()
        assertTrue(store.savedConnections().isEmpty())
    }

    @Test
    fun usingASavedLoginKeepsAutoConnectOnTheLastOne() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val a = ServerConfig(host = "a.example", username = "ops@pam", password = "test-a")
        val b = a.copy(host = "b.example", password = "test-b")
        store.saveLastForm(a, true)
        store.saveLastForm(b, true)
        store.saveAutoConnect(true)
        val aid = store.savedConnections().first { it.host == a.host }.id
        val bid = store.savedConnections().first { it.host == b.host }.id
        assertEquals(false, store.selectConnection(aid)!!.autoConnect)
        assertEquals(true, store.loadLastForm().autoConnect)
        assertEquals(true, store.selectConnection(bid)!!.autoConnect)
        assertEquals("test-a", store.savedFormFor(SessionIdentity.of(a))!!.savedSecret)
    }

    @Test
    fun damagedConnectionListFallsBackInsteadOfCrashing() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("connections", "{not json").putString("last_host", "c.example")
            .putString("last_secret", "test-c").commit()
        val store = SessionStore(injectedPrefs = prefs)
        assertEquals(listOf("c.example"), store.savedConnections().map { it.host })
    }

    @Test
    fun legacyLoginIsMigratedWithoutLosingItsSecret() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("last_host", "old.example").putString("last_username", "root@pam")
            .putString("last_secret", "legacy-test").commit()
        val store = SessionStore(injectedPrefs = prefs)
        val id = store.savedConnections().single().id
        store.saveLastForm(ServerConfig(host = "new.example", username = "root@pam", password = "new-test"), true)
        assertEquals("legacy-test", store.selectConnection(id)!!.savedSecret)
    }

    @Test
    fun sameServerDifferentPortAndAccountHaveIndependentSecretsAndPins() {
        val store = SessionStore(injectedPrefs = FakeSharedPreferences())
        val config = ServerConfig(host = "one.example", username = "root@pam", password = "first-test")
        store.saveLastForm(config, true)
        store.saveLastForm(config.copy(port = 9443, password = "second-test"), true)
        store.saveLastForm(config.copy(username = "ops@pdm", password = "third-test"), true)
        assertEquals(3, store.savedConnections().size)
        store.saveCertPin("one.example", "LEGACY")
        assertNull(store.getEndpointPin("one.example", 9443))
        assertEquals("LEGACY", store.getEndpointPin("one.example", 8443))
        store.saveCertPin("one.example:9443", "OTHER")
        assertEquals("OTHER", store.getEndpointPin("one.example", 9443))
    }
}
