package com.pdm.app.data.repo

open class PdmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class PdmHttpException(
    val code: Int,
    val body: String?,
    httpMessage: String?,
    cause: Throwable? = null,
) : PdmException(format(code, body, httpMessage), cause) {
    companion object {
        fun format(code: Int, body: String?, httpMessage: String?): String {
            val detail = redactSecrets(body)?.trim()?.take(240)
            return buildString {
                append("HTTP $code")
                if (!httpMessage.isNullOrBlank()) append(" $httpMessage")
                if (!detail.isNullOrBlank()) append(": $detail")
            }
        }
    }
}

fun redactSecrets(raw: String?): String? {
    if (raw.isNullOrBlank()) return raw
    var sanitized = raw
    sanitized = sanitized.replace(
        Regex(
            """"(?:ticket|ticket-info|ticket_info|CSRFPreventionToken|csrfprevention-token|password|secret|apiToken|vncticket)"\s*:\s*"[^"]*"""",
            RegexOption.IGNORE_CASE,
        ),
    ) { match ->
        val key = match.value.substringBefore(':')
        "$key:\"[REDACTED]\""
    }
    sanitized = sanitized.replace(Regex("""(?:PDM|PVE):[A-Za-z0-9@_.:+/%=-]+"""), "[REDACTED_TICKET]")
    sanitized = sanitized.replace(Regex("""(?:__Host-|__Secure-)?(?:PDM|PVE)AuthCookie=[^;\s]+""")) { match ->
        "${match.value.substringBefore('=')}=[REDACTED]"
    }
    sanitized = sanitized.replace(Regex("""(?:PDM|PVE)APIToken=[^\s"]+""")) { match ->
        "${match.value.substringBefore('=')}=[REDACTED]"
    }
    sanitized = sanitized.replace(
        Regex("""(CSRFPreventionToken=)[^\s;"]+""", RegexOption.IGNORE_CASE),
        "$1[REDACTED]",
    )
    sanitized = sanitized.replace(Regex("""(ticket=)[^;\s"]+""", RegexOption.IGNORE_CASE), "$1[REDACTED]")
    sanitized = sanitized.replace(Regex("""(password=)[^&\s"]+""", RegexOption.IGNORE_CASE), "$1[REDACTED]")
    return sanitized
}

fun Throwable.isAuthFailure(): Boolean {
    if (this is PdmHttpException && code == 401) return true
    if (this is retrofit2.HttpException && code() == 401) return true
    return false
}

fun Throwable.isSessionChanged(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause.message.equals("Session changed", ignoreCase = true)
    }

fun Throwable.isTlsFailure(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is java.security.cert.CertificateException ||
            cause is javax.net.ssl.SSLHandshakeException
    }

/**
 * The server could not be reached (no route, refused, timed out, DNS). Not TLS, not a session
 * change, not an HTTP answer: those mean the server was reached and said no.
 */
fun Throwable.isNetworkFailure(): Boolean {
    if (isTlsFailure() || isSessionChanged()) return false
    return generateSequence(this) { it.cause }.any { cause ->
        cause is java.io.IOException && cause !is javax.net.ssl.SSLException
    }
}

fun Throwable.rootMessage(): String {
    generateSequence(this) { it.cause }
        .mapNotNull { it.message }
        .firstOrNull { it.contains("MITM", ignoreCase = true) || it.contains("Certificate", ignoreCase = true) }
        ?.let { return redactSecrets(it) ?: it }
    return redactSecrets(message) ?: this::class.java.simpleName
}
