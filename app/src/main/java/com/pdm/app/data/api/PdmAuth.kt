package com.pdm.app.data.api

import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.LastLoginForm
import com.pdm.app.data.model.ServerConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

data class ParsedAuthCookie(
    val name: String,
    val value: String,
)

data class HostPort(
    val host: String,
    val port: Int,
)

/**
 * PDM auth helpers. Token header uses a colon before the secret
 * (`PDMAPIToken=user@realm!name:secret`). Password tickets live in
 * `Set-Cookie` (`PDMAuthCookie` / `__Host-PDMAuthCookie`); JSON `ticket-info`
 * is not a session ticket.
 *
 * Saved secrets are bound to host+port+user+authMode. A blank secret field
 * never reuses a secret saved for a different identity.
 */
object PdmAuth {
    const val TOKEN_SCHEME = "PDMAPIToken"
    const val COOKIE_NAME = "PDMAuthCookie"
    const val DEFAULT_PORT = 8443
    const val DEFAULT_REALM = "pam"

    private val API_TOKEN_SHAPE =
        Regex("""^[^@\s]+@[^!\s]+![^:=\s]+[:|=].+$""")

    fun normalizeApiToken(raw: String): String {
        var token = raw.trim()
        token = token.removePrefix("Authorization:").trim()
        token = token.removePrefix("$TOKEN_SCHEME=").removePrefix("PVEAPIToken=").trim()
        val bang = token.indexOf('!')
        if (bang < 0) return token
        val after = token.substring(bang + 1)
        if (':' in after) return token
        val eq = after.lastIndexOf('=')
        if (eq < 0) return token
        return token.substring(0, bang + 1) + after.substring(0, eq) + ":" + after.substring(eq + 1)
    }

    fun tokenAuthorization(raw: String): String = "$TOKEN_SCHEME=${normalizeApiToken(raw)}"

    fun looksLikeApiToken(secret: String): Boolean {
        val trimmed = secret.trim()
            .removePrefix("Authorization:")
            .trim()
            .removePrefix("$TOKEN_SCHEME=")
            .removePrefix("PVEAPIToken=")
            .trim()
        return API_TOKEN_SHAPE.matches(trimmed)
    }

    fun parseSetCookieTicket(headers: Iterable<String>): ParsedAuthCookie? {
        for (header in headers) {
            val pair = header.substringBefore(';').trim()
            if (pair.isEmpty() || '=' !in pair) continue
            val rawName = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=').trim()
            if (value.isEmpty()) continue
            val stripped = rawName.removePrefix("__Host-").removePrefix("__Secure-")
            if (stripped.equals(COOKIE_NAME, ignoreCase = true) ||
                stripped.endsWith("AuthCookie", ignoreCase = true)
            ) {
                return ParsedAuthCookie(rawName, value)
            }
        }
        return null
    }

    fun cookieHeader(name: String, ticket: String): String = "$name=$ticket"

    fun normalizeUsername(username: String, realm: String = DEFAULT_REALM): String {
        val user = username.trim()
        return if ('@' in user) user else "$user@$realm"
    }

    /** True when the parsed host is the offline demo, including `https://demo` and `demo:8443`. */
    fun isDemoHost(raw: String): Boolean =
        parseHostPort(raw).host.equals("demo", ignoreCase = true)

    fun parseHostPort(raw: String, defaultPort: Int = DEFAULT_PORT): HostPort {
        var host = raw.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (host.startsWith('[')) {
            val end = host.indexOf(']')
            if (end > 0) {
                val name = host.substring(1, end)
                val rest = host.substring(end + 1)
                val port = rest.removePrefix(":").toIntOrNull() ?: defaultPort
                return HostPort(name, port)
            }
        }
        val colon = host.lastIndexOf(':')
        if (colon > 0) {
            val port = host.substring(colon + 1).toIntOrNull()
            if (port != null) {
                return HostPort(host.substring(0, colon), port)
            }
        }
        return HostPort(host, defaultPort)
    }

    fun splitUserRealm(raw: String, defaultRealm: String = DEFAULT_REALM): Pair<String, String> {
        val trimmed = raw.trim()
        val at = trimmed.lastIndexOf('@')
        return if (at > 0) {
            trimmed.substring(0, at) to trimmed.substring(at + 1)
        } else {
            trimmed to defaultRealm
        }
    }

    fun identityMatches(
        parsed: HostPort,
        username: String,
        last: LastLoginForm,
        authMode: AuthMode,
    ): Boolean {
        if (last.host.isBlank() || last.savedSecret.isBlank()) return false
        return parsed.host.equals(last.host, ignoreCase = true) &&
            parsed.port == last.port &&
            normalizeUsername(username) == normalizeUsername(last.username) &&
            authMode == last.authMode
    }

    fun configFromForm(
        hostRaw: String,
        username: String,
        secret: String,
        last: LastLoginForm? = null,
        authMode: AuthMode? = null,
        realm: String = DEFAULT_REALM,
        port: Int? = null,
    ): ServerConfig {
        val parsed = parseHostPort(hostRaw, port ?: DEFAULT_PORT)
        val effectivePort = port ?: parsed.port
        val hostPort = HostPort(parsed.host, effectivePort)
        val user = normalizeUsername(username, realm.ifBlank { DEFAULT_REALM })
        val mode = authMode ?: last?.authMode ?: if (looksLikeApiToken(secret)) {
            AuthMode.API_TOKEN
        } else {
            AuthMode.PASSWORD
        }
        val resolvedSecret = when {
            secret.isNotBlank() -> secret
            last != null && identityMatches(hostPort, user, last, mode) -> last.savedSecret
            else -> ""
        }
        val token = mode == AuthMode.API_TOKEN
        return ServerConfig(
            host = parsed.host,
            port = effectivePort,
            authMode = mode,
            username = user,
            password = if (token) "" else resolvedSecret,
            apiToken = if (token) normalizeApiToken(resolvedSecret) else "",
            realm = realm.ifBlank { DEFAULT_REALM },
        )
    }
}

internal fun HttpUrl.isWithinApi(config: ServerConfig): Boolean {
    val base = config.baseUrl.toHttpUrl()
    return scheme == base.scheme && host == base.host && port == base.port &&
        encodedPath.startsWith(base.encodedPath)
}
