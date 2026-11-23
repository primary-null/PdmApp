package com.pdm.app.data.ops

import com.pdm.app.data.repo.PdmHttpException
import com.pdm.app.data.repo.isAuthFailure
import com.pdm.app.data.repo.isNetworkFailure
import com.pdm.app.data.repo.isSessionChanged
import com.pdm.app.data.repo.isTlsFailure
import com.pdm.app.data.repo.rootMessage

/** What a failed write request means for the operation it carried. */
sealed interface SubmitFailure {
    /** The server answered with an error: nothing ran. */
    data class Rejected(val reason: String) : SubmitFailure

    /** No connection was ever made, so the request never left the device. */
    data class NotSent(val reason: String) : SubmitFailure

    /** The request may have arrived; the answer did not. Watch for it, never resend it. */
    data object Unknown : SubmitFailure

    /** Sign-in, certificate or session trouble: handled by the normal sign-in path. */
    data object Fatal : SubmitFailure
}

object Submission {
    fun classify(e: Throwable): SubmitFailure = when {
        e.isAuthFailure() || e.isTlsFailure() || e.isSessionChanged() -> SubmitFailure.Fatal
        e.httpStatus() in MAY_HAVE_RUN -> SubmitFailure.Unknown
        e.causes().any { it is retrofit2.HttpException || it is PdmHttpException } -> SubmitFailure.Rejected(e.rootMessage())
        // Never connected: the request never left the device.
        e.causes().any {
            it is java.net.ConnectException || it is java.net.UnknownHostException || it is java.net.NoRouteToHostException
        } -> SubmitFailure.NotSent(e.rootMessage())
        // Sent, then the connection broke or timed out: the server may have acted on it.
        e.isNetworkFailure() -> SubmitFailure.Unknown
        // An answer arrived but could not be read: the server most likely accepted it.
        else -> SubmitFailure.Unknown
    }

    private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

    private fun Throwable.httpStatus(): Int? = causes().firstNotNullOfOrNull { cause ->
        when (cause) {
            is retrofit2.HttpException -> cause.code()
            is PdmHttpException -> cause.code
            else -> null
        }
    }

    /** The request left the device and the server may have started the task. */
    private val MAY_HAVE_RUN = setOf(408, 502, 503, 504)
}
