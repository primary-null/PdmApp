package com.pdm.app.data.ops

import com.pdm.app.data.repo.PdmHttpException
import java.net.ConnectException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SubmissionTest {
    private fun http(code: Int) = HttpException(
        Response.error<String>(code, "{}".toResponseBody("application/json".toMediaType())),
    )

    @Test
    fun gatewayTimeoutsMayHaveRun() {
        listOf(408, 502, 503, 504).forEach { code ->
            assertEquals(code.toString(), SubmitFailure.Unknown, Submission.classify(http(code)))
            assertEquals(code.toString(), SubmitFailure.Unknown, Submission.classify(PdmHttpException(code, null, "timeout")))
        }
    }

    @Test
    fun otherClientErrorsAreRejected() {
        val rejected = Submission.classify(http(400))
        assertTrue(rejected is SubmitFailure.Rejected)
        val forbidden = Submission.classify(PdmHttpException(403, null, "no"))
        assertTrue(forbidden is SubmitFailure.Rejected)
    }

    @Test
    fun unauthorizedStaysFatal() {
        assertEquals(SubmitFailure.Fatal, Submission.classify(http(401)))
        assertEquals(SubmitFailure.Fatal, Submission.classify(PdmHttpException(401, null, "no")))
    }

    @Test
    fun neverConnectedIsNotSent() {
        assertTrue(Submission.classify(ConnectException("refused")) is SubmitFailure.NotSent)
    }
}
