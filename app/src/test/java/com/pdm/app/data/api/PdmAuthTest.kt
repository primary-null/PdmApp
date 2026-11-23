package com.pdm.app.data.api

import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.LastLoginForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdmAuthTest {
    @Test
    fun tokenHeaderUsesColonBeforeSecret() {
        val header = PdmAuth.tokenAuthorization("root@pdm!ops=uuid-secret")
        assertEquals("PDMAPIToken=root@pdm!ops:uuid-secret", header)
        assertFalse(header.contains("!ops="))
        assertTrue(header.contains("!ops:"))
    }

    @Test
    fun tokenHeaderKeepsPdmColonForm() {
        val header = PdmAuth.tokenAuthorization("PDMAPIToken=root@pdm!ops:uuid-secret")
        assertEquals("PDMAPIToken=root@pdm!ops:uuid-secret", header)
    }

    @Test
    fun cookieParserEchoesHostPrefixedName() {
        val parsed = PdmAuth.parseSetCookieTicket(
            listOf(
                "__Host-PDMAuthCookie=TICKETVALUE; HttpOnly; Secure; Path=/; SameSite=Lax",
            ),
        )
        assertEquals("__Host-PDMAuthCookie", parsed?.name)
        assertEquals("TICKETVALUE", parsed?.value)
    }

    @Test
    fun cookieParserReadsBarePdmAuthCookie() {
        val parsed = PdmAuth.parseSetCookieTicket(listOf("PDMAuthCookie=TICKETVALUE; HttpOnly"))
        assertEquals("PDMAuthCookie", parsed?.name)
        assertEquals("TICKETVALUE", parsed?.value)
    }

    @Test
    fun cookieParserIgnoresTicketInfoOnly() {
        assertNull(PdmAuth.parseSetCookieTicket(listOf("unrelated=1; Path=/")))
    }

    @Test
    fun demoHostMatchesTheParsedNameOnly() {
        assertTrue(PdmAuth.isDemoHost("demo"))
        assertTrue(PdmAuth.isDemoHost("DEMO"))
        assertTrue(PdmAuth.isDemoHost(" demo "))
        assertTrue(PdmAuth.isDemoHost("https://demo"))
        assertTrue(PdmAuth.isDemoHost("http://demo/"))
        assertTrue(PdmAuth.isDemoHost("demo:8443"))
        assertTrue(PdmAuth.isDemoHost("https://demo:8443/api2"))
        assertFalse(PdmAuth.isDemoHost("demo.example"))
        assertFalse(PdmAuth.isDemoHost("192.0.2.10"))
        assertFalse(PdmAuth.isDemoHost("not-demo"))
        assertFalse(PdmAuth.isDemoHost(""))
    }

    @Test
    fun hostDefaultsTo8443() {
        val parsed = PdmAuth.parseHostPort("example.com")
        assertEquals("example.com", parsed.host)
        assertEquals(8443, parsed.port)
        val withPort = PdmAuth.parseHostPort("192.0.2.35:8443")
        assertEquals("192.0.2.35", withPort.host)
        assertEquals(8443, withPort.port)
    }

    @Test
    fun usernameGetsPamRealm() {
        assertEquals("root@pam", PdmAuth.normalizeUsername("root"))
        assertEquals("ops@pdm", PdmAuth.normalizeUsername("ops@pdm"))
        assertEquals("root@pam", PdmAuth.normalizeUsername("root@pam"))
    }

    @Test
    fun looksLikeApiTokenRequiresUserRealmTokenIdAndSecret() {
        assertTrue(PdmAuth.looksLikeApiToken("root@pdm!ops:uuid-secret"))
        assertTrue(PdmAuth.looksLikeApiToken("root@pdm!ops=uuid-secret"))
        assertTrue(PdmAuth.looksLikeApiToken("PDMAPIToken=root@pdm!ops:uuid-secret"))
        assertFalse(PdmAuth.looksLikeApiToken("P@ssw0rd!lab"))
        assertFalse(PdmAuth.looksLikeApiToken("hello!world=foo"))
        assertFalse(PdmAuth.looksLikeApiToken("root@pdm"))
    }

    @Test
    fun blankSecretReusesSavedSecretOnlyForMatchingHostPortUser() {
        val last = LastLoginForm(
            host = "pdm.example",
            port = 8443,
            username = "root@pam",
            savedSecret = "bound-secret",
        )
        val same = PdmAuth.configFromForm(
            hostRaw = "pdm.example",
            username = "root",
            secret = "",
            last = last,
        )
        assertEquals(AuthMode.PASSWORD, same.authMode)
        assertEquals("bound-secret", same.password)

        val otherHost = PdmAuth.configFromForm(
            hostRaw = "other.example",
            username = "root@pdm",
            secret = "",
            last = last,
        )
        assertEquals("", otherHost.password)
        assertEquals("", otherHost.apiToken)

        val otherUser = PdmAuth.configFromForm(
            hostRaw = "pdm.example",
            username = "ops@pdm",
            secret = "",
            last = last,
        )
        assertEquals("", otherUser.password)
    }

    @Test
    fun passwordModeKeepsBangPassword() {
        val config = PdmAuth.configFromForm(
            hostRaw = "pdm.example",
            username = "root",
            secret = "P@ssw0rd!lab",
            authMode = AuthMode.PASSWORD,
            realm = "pdm",
        )
        assertEquals(AuthMode.PASSWORD, config.authMode)
        assertEquals("P@ssw0rd!lab", config.password)
        assertEquals("", config.apiToken)
        assertEquals("root@pdm", config.username)
    }

    @Test
    fun tokenModeUsesExplicitApiToken() {
        val config = PdmAuth.configFromForm(
            hostRaw = "pdm.example",
            username = "root",
            secret = "root@pdm!ops:uuid-secret",
            authMode = AuthMode.API_TOKEN,
            realm = "pdm",
        )
        assertEquals(AuthMode.API_TOKEN, config.authMode)
        assertEquals("root@pdm!ops:uuid-secret", config.apiToken)
        assertEquals("", config.password)
    }

    @Test
    fun savedSecretDoesNotCrossAuthMode() {
        val last = LastLoginForm(
            host = "pdm.example",
            port = 8443,
            username = "root@pdm",
            authMode = AuthMode.PASSWORD,
            savedSecret = "bound-secret",
        )
        val asToken = PdmAuth.configFromForm(
            hostRaw = "pdm.example",
            username = "root@pdm",
            secret = "",
            last = last,
            authMode = AuthMode.API_TOKEN,
        )
        assertEquals("", asToken.apiToken)
        assertEquals("", asToken.password)
    }

    @Test
    fun splitUserRealmReadsAtRealm() {
        assertEquals("root" to "pdm", PdmAuth.splitUserRealm("root@pdm"))
        assertEquals("ops" to "pam", PdmAuth.splitUserRealm("ops@pam"))
        assertEquals("root" to "pam", PdmAuth.splitUserRealm("root"))
    }
}
