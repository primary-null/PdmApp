package com.pdm.app.data.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pdm.app.data.api.AppJson
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.model.LastLoginForm
import com.pdm.app.data.model.ServerConfig
import com.pdm.app.data.model.SessionState
import com.pdm.app.data.model.OperationReceipt
import com.pdm.app.data.model.OperationSubmissionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live session + TOFU pins + last form.
 * Password tickets stay in memory. Secrets go only in EncryptedSharedPreferences.
 */
class SessionStore(
    context: Context? = null,
    injectedPrefs: SharedPreferences? = null,
) {
    private val prefs: SharedPreferences = injectedPrefs ?: SecurePrefs.open(context!!.applicationContext)

    private val receiptLock = Any()
    private val _session = MutableStateFlow<SessionState?>(null)
    val session: StateFlow<SessionState?> = _session.asStateFlow()

    private var generation = 0L

    @Synchronized
    fun beginLogin(): Long = ++generation

    @Synchronized
    fun publishIfGeneration(expected: Long, publish: () -> Unit): Boolean {
        if (generation != expected) return false
        publish()
        return true
    }

    @Synchronized
    fun snapshot(): SessionSnapshot? = _session.value?.let { SessionSnapshot(generation, it) }

    @Synchronized
    fun isCurrent(expected: SessionSnapshot): Boolean =
        generation == expected.generation &&
            _session.value?.let { SessionIdentity.of(it.config) == SessionIdentity.of(expected.state.config) } == true

    @Synchronized
    fun currentSession(expected: SessionSnapshot): SessionState? =
        if (isCurrent(expected)) _session.value else null

    @Synchronized
    fun setSession(state: SessionState) {
        generation++
        _session.value = state
    }

    /**
     * Swap in a renewed ticket for the same identity without bumping the generation, so
     * requests already in flight are not treated as a session change.
     */
    @Synchronized
    fun replaceTicketIfCurrent(expected: SessionSnapshot, renewed: SessionState): Boolean {
        if (!isCurrent(expected)) return false
        if (SessionIdentity.of(renewed.config) != SessionIdentity.of(expected.state.config)) return false
        _session.value = renewed
        return true
    }

    @Synchronized
    fun clearSession() {
        generation++
        _session.value = null
    }

    fun isAuthenticated(): Boolean {
        val s = _session.value ?: return false
        return when (s.config.authMode) {
            AuthMode.API_TOKEN -> s.config.apiToken.isNotBlank()
            AuthMode.PASSWORD -> !s.ticket.isNullOrBlank()
        }
    }

    fun getCertPin(host: String): String? = prefs.getString(certPinPref(host), null)

    fun getEndpointPin(host: String, port: Int): String? =
        getCertPin("$host:$port") ?: if (port == 8443) getCertPin(host) else null

    fun saveEndpointPin(host: String, port: Int, fingerprint: String) {
        prefs.edit {
            putString(certPinPref("$host:$port"), fingerprint)
            if (port == 8443) remove(certPinPref(host))
        }
    }

    fun saveCertPin(host: String, fingerprint: String) {
        prefs.edit { putString(certPinPref(host), fingerprint) }
    }

    fun removeCertPin(host: String) {
        prefs.edit { remove(certPinPref(host)) }
    }

    /**
     * A node's root password, kept only when the user ticked "Remember" in the Upgrade dialog.
     * Same encrypted store as saved PDM secrets; scoped to the PDM login, remote and node.
     */
    fun nodePassword(identity: SessionIdentity, remote: String, node: String): String? =
        prefs.getString(nodePasswordPref(identity, remote, node), null)?.takeIf { it.isNotEmpty() }

    fun saveNodePassword(identity: SessionIdentity, remote: String, node: String, password: String) {
        prefs.edit { putString(nodePasswordPref(identity, remote, node), password) }
    }

    fun forgetNodePassword(identity: SessionIdentity, remote: String, node: String) {
        prefs.edit { remove(nodePasswordPref(identity, remote, node)) }
    }

    /** Terminal snippets the user saved. Encrypted like everything here: a command can hold a secret. */
    fun savedSnippets(): List<com.pdm.app.data.shell.Snippet> =
        prefs.getString(KEY_SNIPPETS, null)
            ?.let { runCatching { AppJson.decodeFromString<List<com.pdm.app.data.shell.Snippet>>(it) }.getOrNull() }
            .orEmpty()

    fun saveSnippets(snippets: List<com.pdm.app.data.shell.Snippet>) {
        prefs.edit { putString(KEY_SNIPPETS, AppJson.encodeToString(snippets)) }
    }

    fun savedNodePasswordCount(): Int = prefs.all.keys.count { it.startsWith(NODE_PW_PREFIX) }

    fun forgetAllNodePasswords() {
        val keys = prefs.all.keys.filter { it.startsWith(NODE_PW_PREFIX) }
        prefs.edit { keys.forEach { remove(it) } }
    }

    private fun nodePasswordPref(identity: SessionIdentity, remote: String, node: String) =
        "$NODE_PW_PREFIX${identity.endpoint}|${identity.account}|$remote|$node"

    fun listCertPins(): List<CertPin> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(CERT_PIN_PREFIX) && value is String && value.isNotBlank()) {
                CertPin(host = key.removePrefix(CERT_PIN_PREFIX), fingerprint = value)
            } else {
                null
            }
        }.sortedBy { it.host }

    /**
     * Wipe session, saved secret, TOFU pins, and the encrypted store.
     * With a [Context], also deletes the vault files and the Android Keystore master key.
     */
    fun getOperationReceipts(identity: SessionIdentity): List<OperationReceipt> = synchronized(receiptLock) {
        val key = receiptKey(identity)
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching { AppJson.decodeFromString<List<OperationReceipt>>(raw) }.getOrDefault(emptyList())
    }

    fun saveOperationReceipt(identity: SessionIdentity, receipt: OperationReceipt) = synchronized(receiptLock) {
        val current = getOperationReceipts(identity).filterNot { it.id == receipt.id }
        val updated = pruneReceipts(current + receipt)
        prefs.edit { putString(receiptKey(identity), AppJson.encodeToString(updated)) }
    }

    fun removeOperationReceipt(identity: SessionIdentity, receiptId: String) = synchronized(receiptLock) {
        val current = getOperationReceipts(identity)
        val updated = current.filterNot { it.id == receiptId }
        prefs.edit { putString(receiptKey(identity), AppJson.encodeToString(updated)) }
    }

    fun updateOperationReceipt(
        identity: SessionIdentity,
        receiptId: String,
        transform: (OperationReceipt) -> OperationReceipt,
    ) = synchronized(receiptLock) {
        val current = getOperationReceipts(identity)
        val updated = current.map { if (it.id == receiptId) transform(it) else it }
        val pruned = pruneReceipts(updated)
        prefs.edit { putString(receiptKey(identity), AppJson.encodeToString(pruned)) }
    }

    /**
     * Receipt retention: every unresolved receipt is kept (newest [MAX_UNRESOLVED_RECEIPTS], which is
     * far beyond normal use), plus the newest [MAX_TERMINAL_RECEIPTS] finished ones. Clean Slate
     * removes all of them; signing out removes none.
     */
    private fun pruneReceipts(list: List<OperationReceipt>): List<OperationReceipt> {
        val unresolved = list.filterNot { it.isTerminal }
            .sortedByDescending { it.submittedAt }
            .take(MAX_UNRESOLVED_RECEIPTS)
        val terminal = list.filter { it.isTerminal }
            .sortedByDescending { it.submittedAt }
            .take(MAX_TERMINAL_RECEIPTS)
        return unresolved + terminal
    }

    private fun receiptKey(identity: SessionIdentity): String =
        "operation_receipts_${identity.endpoint}|${identity.authMode.name}|${identity.account}"

    fun purgeAll(context: Context? = null) {
        clearSession()
        prefs.edit().clear().commit()
        if (context != null) {
            runCatching { SecurePrefs.open(context).edit().clear().commit() }
            runCatching { context.deleteSharedPreferences(SecurePrefs.SECURE_PREFS_NAME) }
            runCatching {
                val prefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                java.io.File(prefsDir, "${SecurePrefs.SECURE_PREFS_NAME}.xml").delete()
                java.io.File(prefsDir, "${SecurePrefs.SECURE_PREFS_NAME}.xml.bak").delete()
            }
            runCatching {
                val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                keyStore.deleteEntry("_androidx_security_master_key_")
            }
        }
    }

    private fun certPinPref(host: String) = "$CERT_PIN_PREFIX${host.trim().lowercase()}"

    private fun profileId(form: LastLoginForm): String =
        listOf(form.host.trim().lowercase(), form.port.toString(), form.username, form.authMode.name).joinToString("|")

    private fun profiles(): List<LastLoginForm> {
        val stored = prefs.getString("connections", null)
        // A damaged entry must not crash start-up (Clean Slate lives behind the login).
        if (stored != null) runCatching { AppJson.decodeFromString<List<LastLoginForm>>(stored) }.getOrNull()?.let { return it }
        return listOf(loadLastForm()).filter { it.host.isNotBlank() }
    }

    /** The config a saved login signs in with, secret included. Never leaves this process. */
    fun configFor(form: LastLoginForm): ServerConfig {
        val (user, realm) = com.pdm.app.data.api.PdmAuth.splitUserRealm(form.username)
        return com.pdm.app.data.api.PdmAuth.configFromForm(
            hostRaw = form.host,
            username = user,
            secret = form.savedSecret,
            authMode = form.authMode,
            realm = realm,
            port = form.port,
        )
    }

    /** The saved login (with a secret) for [identity], whichever was used last. */
    fun savedFormFor(identity: SessionIdentity): LastLoginForm? =
        profiles().firstOrNull { it.savedSecret.isNotBlank() && SessionIdentity.of(configFor(it)) == identity }

    /** Every saved login, secrets included. For matching a typed form; never put in UI state. */
    fun savedForms(): List<LastLoginForm> = profiles()

    /** The saved login a picker row refers to. */
    fun connection(id: String): LastLoginForm? = profiles().firstOrNull { profileId(it) == id }

    /**
     * Public metadata only; credentials never enter the picker state. [cachedAt] tells the picker
     * when this login last saved offline data (null: none).
     */
    fun savedConnections(cachedAt: (SessionIdentity) -> Long? = { null }): List<SavedConnection> = profiles().map {
        val cached = if (it.savedSecret.isNotBlank()) cachedAt(SessionIdentity.of(configFor(it))) else null
        SavedConnection(profileId(it), it.host, it.port, it.username, it.authMode, it.savedSecret.isNotBlank(), cached)
    }

    /**
     * Fill the form from a saved login. Nothing is written: "last login" and auto-connect keep
     * pointing at the last login that actually succeeded until this one does.
     */
    fun selectConnection(id: String): LastLoginForm? {
        val form = connection(id) ?: return null
        val last = loadLastForm()
        val isLast = profileId(last) == id
        return form.copy(autoConnect = isLast && last.autoConnect)
    }

    fun removeConnection(id: String) {
        val remaining = profiles().filterNot { profileId(it) == id }
        prefs.edit { putString("connections", AppJson.encodeToString(remaining)) }
        if (profileId(loadLastForm()) == id) {
            prefs.edit { listOf(KEY_HOST, KEY_PORT, KEY_USERNAME, KEY_TRUST, KEY_AUTH_MODE, KEY_SECRET, KEY_AUTO).forEach { remove(it) } }
        }
    }

    fun saveLastForm(config: ServerConfig, saveSecret: Boolean) {
        if (config.host.equals("demo", ignoreCase = true)) return
        val previous = profiles()
        val form = LastLoginForm(host = config.host, port = config.port,
            username = com.pdm.app.data.api.PdmAuth.normalizeUsername(config.username, config.realm),
            authMode = config.authMode,
            savedSecret = if (!saveSecret) "" else if (config.authMode == AuthMode.PASSWORD) config.password else config.apiToken)
        prefs.edit {
            putString("connections", AppJson.encodeToString(listOf(form) + previous.filterNot { profileId(it) == profileId(form) }))
            putString(KEY_HOST, config.host)
            putInt(KEY_PORT, config.port)
            putString(KEY_USERNAME, form.username)
            putString(KEY_AUTH_MODE, config.authMode.name)
            if (saveSecret) {
                val secret = when (config.authMode) {
                    AuthMode.API_TOKEN -> config.apiToken
                    AuthMode.PASSWORD -> config.password
                }
                putString(KEY_SECRET, secret)
            } else {
                remove(KEY_SECRET)
            }
        }
    }

    /**
     * Forget the saved secret of [identity] (default: the last login). When that is the last
     * login, its secret and auto-connect go too. Returns the forgotten login, if there was one.
     */
    fun clearSavedSecret(identity: SessionIdentity? = null): LastLoginForm? {
        val last = loadLastForm()
        val target = if (identity == null) last.takeIf { it.host.isNotBlank() } else savedFormFor(identity)
        target ?: return null
        val id = profileId(target)
        val updated = profiles().map { if (profileId(it) == id) it.copy(savedSecret = "", autoConnect = false) else it }
        prefs.edit {
            if (profileId(last) == id) {
                remove(KEY_SECRET)
                putBoolean(KEY_AUTO, false)
            }
            putString("connections", AppJson.encodeToString(updated))
        }
        return target
    }

    fun saveAutoConnect(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO, enabled) }
    }

    fun loadLastForm(): LastLoginForm {
        val host = prefs.getString(KEY_HOST, "") ?: ""
        if (host.equals("demo", ignoreCase = true)) return LastLoginForm()
        return LastLoginForm(
            host = host,
            port = prefs.getInt(KEY_PORT, 8443),
            username = prefs.getString(KEY_USERNAME, "root@pam") ?: "root@pam",
            authMode = prefs.getString(KEY_AUTH_MODE, AuthMode.PASSWORD.name)
                ?.let { runCatching { AuthMode.valueOf(it) }.getOrDefault(AuthMode.PASSWORD) }
                ?: AuthMode.PASSWORD,
            savedSecret = prefs.getString(KEY_SECRET, "") ?: "",
            autoConnect = prefs.getBoolean(KEY_AUTO, false),
        )
    }

    companion object {
        const val MAX_UNRESOLVED_RECEIPTS = 50
        const val MAX_TERMINAL_RECEIPTS = 20
        private const val NODE_PW_PREFIX = "node_root_pw|"
        private const val KEY_SNIPPETS = "terminal_snippets_v1"
        private const val KEY_HOST = "last_host"
        private const val KEY_PORT = "last_port"
        private const val KEY_USERNAME = "last_username"
        /** Legacy "trust self-signed" switch; only removed now. */
        private const val KEY_TRUST = "last_trust"
        private const val KEY_AUTH_MODE = "last_auth_mode"
        private const val KEY_SECRET = "last_secret"
        private const val KEY_AUTO = "last_auto_connect"
        private const val CERT_PIN_PREFIX = "certpin_"
    }
}

data class CertPin(
    val host: String,
    val fingerprint: String,
)

/** Never contains a password, token, cookie or ticket. */
data class SavedConnection(val id: String, val host: String, val port: Int, val username: String,
    val authMode: AuthMode, val secretSaved: Boolean, val cachedAt: Long? = null)
