package com.pdm.app.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdmExceptionTest {
    @Test
    fun networkFailureMeansTheServerWasNotReached() {
        assertTrue(PdmException("x", java.net.ConnectException("refused")).isNetworkFailure())
        assertTrue(java.net.SocketTimeoutException("timeout").isNetworkFailure())
        assertFalse(PdmException("x", javax.net.ssl.SSLHandshakeException("cert")).isNetworkFailure())
        assertFalse(PdmHttpException(401, null, "Unauthorized").isNetworkFailure())
        assertFalse(java.io.IOException("Session changed").isNetworkFailure())
    }

    @Test
    fun redactSecretsCoversJsonTicketsCookiesAndTokens() {
        val json = """{"ticket":"TICKET123","CSRFPreventionToken":"CSRF456","password":"mypassword","secret":"topsecret"}"""
        val cleanJson = redactSecrets(json)!!
        assertFalse(cleanJson.contains("TICKET123"))
        assertFalse(cleanJson.contains("CSRF456"))
        assertFalse(cleanJson.contains("mypassword"))
        assertFalse(cleanJson.contains("topsecret"))

        val headers =
            "Cookie: PDMAuthCookie=PDM:root@pdm:SECRET_VAL __Host-PDMAuthCookie=HOSTSECRET PDMAPIToken=root@pdm!ops:uuid"
        val cleanHeaders = redactSecrets(headers)!!
        assertTrue(cleanHeaders.contains("PDMAuthCookie=[REDACTED]"))
        assertTrue(cleanHeaders.contains("__Host-PDMAuthCookie=[REDACTED]"))
        assertTrue(cleanHeaders.contains("PDMAPIToken=[REDACTED]"))
        assertFalse(cleanHeaders.contains("SECRET_VAL"))
        assertFalse(cleanHeaders.contains("HOSTSECRET"))
        assertFalse(cleanHeaders.contains("uuid"))

        val pve = "Cookie: PVEAuthCookie=PVE:root@pam:SECRET_VAL PVEAPIToken=root@pam!token=SECRET_API_KEY"
        val cleanPve = redactSecrets(pve)!!
        assertTrue(cleanPve.contains("PVEAuthCookie=[REDACTED]"))
        assertTrue(cleanPve.contains("PVEAPIToken=[REDACTED]"))
        assertFalse(cleanPve.contains("SECRET_VAL"))
        assertFalse(cleanPve.contains("SECRET_API_KEY"))
    }

    @Test
    fun httpExceptionFormatUsesRedactedBody() {
        val message = PdmHttpException.format(401, """{"ticket":"LEAKME"}""", "Unauthorized")
        assertEquals("HTTP 401 Unauthorized: {\"ticket\":\"[REDACTED]\"}", message)
        assertFalse(message.contains("LEAKME"))
    }
}
