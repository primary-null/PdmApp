package com.pdm.app.data.session

import com.pdm.app.data.api.PdmAuth
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import okhttp3.HttpUrl.Companion.toHttpUrl

data class SessionIdentity(val endpoint: String, val authMode: AuthMode, val account: String) {
    companion object {
        fun of(config: ServerConfig): SessionIdentity {
            val account = when (config.authMode) {
                AuthMode.PASSWORD -> PdmAuth.normalizeUsername(config.username, config.realm)
                AuthMode.API_TOKEN -> PdmAuth.normalizeApiToken(config.apiToken).substringBefore(':')
            }
            return SessionIdentity(config.baseUrl.toHttpUrl().toString(), config.authMode, account)
        }
    }
}

data class SessionSnapshot(val generation: Long, val state: SessionState)

data class ProbeAuth(val ticket: String, val csrf: String? = null, val cookieName: String = PdmAuth.COOKIE_NAME)
