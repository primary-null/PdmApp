package com.pdm.app.data.repo

import com.pdm.app.data.HostProbe
import java.io.IOException
import java.net.ConnectException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class ProbeHostMappingTest {
    private fun http(code: Int) = HttpException(
        Response.error<String>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun authTlsAndOtherAnswersMapSeparately() {
        assertEquals(HostProbe.SignInNeeded, hostProbeFor(http(401)))
        assertEquals(HostProbe.SignInNeeded, hostProbeFor(PdmHttpException(401, null, "no")))
        assertEquals(HostProbe.CertificateRefused, hostProbeFor(SSLHandshakeException("changed")))
        assertEquals(HostProbe.CertificateRefused, hostProbeFor(CertificateException("pin")))
        assertEquals(HostProbe.Answering, hostProbeFor(http(500)))
        assertEquals(HostProbe.Answering, hostProbeFor(PdmHttpException(403, null, "no")))
        assertEquals(HostProbe.Silent, hostProbeFor(ConnectException("refused")))
        assertEquals(HostProbe.Silent, hostProbeFor(IOException("timeout")))
    }

    @Test
    fun sessionChangeAndSignedOutCancelTheWatch() {
        try {
            hostProbeFor(IOException("Session changed"))
            fail("session change must cancel the watch")
        } catch (e: IOException) {
            assertEquals("Session changed", e.message)
        }
        try {
            hostProbeFor(PdmException("Not signed in"))
            fail("a missing session must cancel the watch")
        } catch (e: PdmException) {
            assertEquals("Not signed in", e.message)
        }
    }
}
