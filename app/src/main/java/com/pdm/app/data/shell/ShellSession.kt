package com.pdm.app.data.shell

import com.pdm.app.data.api.PdmClientFactory
import com.pdm.app.data.model.AuthMode
import com.pdm.app.data.repo.PdmException
import com.pdm.app.data.session.SessionStore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** Where a shell opens: a node behind a remote, or the PDM machine itself. */
sealed interface ShellTarget {
    val title: String

    data class RemoteNode(val remoteId: String, val node: String) : ShellTarget {
        override val title get() = node
    }

    /** `upgrade` asks PDM to run `apt full-upgrade` before the login shell (root@pam only). */
    data class Host(val node: String, val upgrade: Boolean) : ShellTarget {
        override val title get() = node
    }
}

enum class ShellPhase { CONNECTING, OPEN, CLOSED }

data class ShellStatus(val phase: ShellPhase, val message: String? = null)

/**
 * One terminal over PDM's `vncwebsocket`. Output lands in [buffer]; [outputVersion] ticks so a view
 * can read what is new. Input and resizes go out in termproxy framing.
 */
class ShellSession internal constructor(
    val target: ShellTarget,
    private val staged: String? = null,
    private val login: NodeLogin? = null,
) {
    val buffer = ShellBuffer()
    private val handshake = TermHandshake()
    private val promptWatch = PromptWatch()
    private val _prompt = MutableStateFlow<PromptKind?>(null)
    /** The last prompt the terminal showed. */
    val prompt: StateFlow<PromptKind?> = _prompt.asStateFlow()
    private val _stagedTyped = MutableStateFlow(false)
    /** The staged command was typed (without Enter) at a shell prompt. */
    val stagedTyped: StateFlow<Boolean> = _stagedTyped.asStateFlow()
    private val _loginState = MutableStateFlow(login?.state)
    /** Where the automatic node login stands; null when the app is not signing in for you. */
    val loginState: StateFlow<NodeLogin.State?> = _loginState.asStateFlow()
    private val _status = MutableStateFlow(ShellStatus(ShellPhase.CONNECTING))
    val status: StateFlow<ShellStatus> = _status.asStateFlow()
    private val _outputVersion = MutableStateFlow(0L)
    val outputVersion: StateFlow<Long> = _outputVersion.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null
    private val closing = AtomicBoolean(false)
    private var lastSize: Pair<Int, Int>? = null

    internal fun listener(loginLine: String?) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            loginLine?.let { webSocket.send(it) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.toByteArray())

        override fun onMessage(webSocket: WebSocket, text: String) = receive(text.toByteArray())

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            finish(reason.takeIf { it.isNotBlank() } ?: "Terminal closed.")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
            finish(reason.takeIf { it.isNotBlank() } ?: "Terminal closed.")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val why = when (response?.code) {
                401 -> "Your PDM sign-in has expired. Sign in again and retry."
                403 -> "Your PDM account isn't allowed to open a terminal here."
                null -> t.message ?: t.javaClass.simpleName
                else -> "The terminal couldn't open (error ${response.code})."
            }
            finish(if (closing.get()) "Terminal closed." else why)
        }
    }

    private fun receive(bytes: ByteArray) {
        val wasAccepted = handshake.accepted
        val shown = handshake.feed(bytes)
        if (!wasAccepted && handshake.accepted) {
            _status.value = ShellStatus(ShellPhase.OPEN)
            lastSize?.let { (c, r) -> socket?.send(TermProtocol.resize(c, r)) }
        }
        if (shown.isNotEmpty()) {
            buffer.append(shown)
            _outputVersion.value = buffer.end
            val kind = promptWatch.feed(shown)
            // Sticky: typing a username after `login:` is still the login step.
            if (kind != null) _prompt.value = kind
            login?.let { l ->
                l.onOutput(String(shown, Charsets.UTF_8), kind)?.let(::send)
                _loginState.value = l.state
            }
            // Typed once, at the first real shell prompt, and never with Enter.
            if (kind == PromptKind.SHELL && staged != null && !_stagedTyped.value) {
                _stagedTyped.value = true
                send(staged)
            }
        }
    }

    private fun finish(message: String) {
        if (_status.value.phase == ShellPhase.CLOSED) return
        socket = null
        _status.value = ShellStatus(ShellPhase.CLOSED, message)
    }

    /** Dropped until the terminal accepted the session: nothing may jump ahead of the login line. */
    fun send(data: String) {
        if (data.isEmpty() || !handshake.accepted) return
        socket?.send(TermProtocol.input(data))
    }

    fun resize(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || lastSize == cols to rows) return
        lastSize = cols to rows
        if (handshake.accepted) socket?.send(TermProtocol.resize(cols, rows))
    }

    fun ping() {
        if (handshake.accepted) socket?.send(TermProtocol.PING)
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        socket?.close(1000, null) ?: finish("Terminal closed.")
    }

    internal fun fail(message: String) = finish(message)

    internal fun attach(webSocket: WebSocket) {
        socket = socket ?: webSocket
        if (closing.get()) webSocket.close(1000, null)
    }
}

/** Gets a terminal ticket from PDM and opens the websocket on the session's pinned client. */
class ShellConnector(
    private val sessionStore: SessionStore,
    private val clients: PdmClientFactory,
) {
    /** API tokens never get a shell from PDM; say so before asking. */
    fun shellAllowed(): Boolean =
        sessionStore.snapshot()?.state?.let { !it.cachedOnly && it.config.authMode == AuthMode.PASSWORD } ?: false

    suspend fun open(target: ShellTarget, staged: String? = null, login: NodeLogin? = null): ShellSession {
        val session = sessionStore.snapshot() ?: throw PdmException("Not signed in")
        if (session.state.config.authMode != AuthMode.PASSWORD) {
            throw PdmException("To open a terminal, sign in to PDM with a password instead of an API token.")
        }
        val api = clients.apiForSession(session)
        val base = session.state.config.baseUrl.toHttpUrl()
        val (ticket, path, loginLine) = when (target) {
            is ShellTarget.RemoteNode -> {
                val t = api.remoteShellTicket(target.remoteId, target.node).data
                    ?: throw PdmException("The terminal couldn't open. Try again.")
                // PDM logs in to the node itself; the client sends no login line.
                Triple(t, listOf("pve", "remotes", target.remoteId, "nodes", target.node, "vncwebsocket"), null)
            }
            is ShellTarget.Host -> {
                val t = api.hostShellTicket(target.node, if (target.upgrade) "upgrade" else "login").data
                    ?: throw PdmException("The terminal couldn't open. Try again.")
                // The ticket is bound to the full user id (root@pam). PDM's answer names the bare user
                // ("root"), which termproxy rejects; the web client also sends the full id.
                val user = session.state.username?.takeIf { '@' in it }
                    ?: t.user?.takeIf { '@' in it }
                    ?: throw PdmException("The terminal couldn't open. Try again.")
                Triple(t, listOf("nodes", target.node, "vncwebsocket"), TermProtocol.login(user, t.ticket))
            }
        }
        val url = base.newBuilder().apply {
            path.forEach { addPathSegment(it) }
            addQueryParameter("port", ticket.port.toString())
            addQueryParameter("vncticket", ticket.ticket)
        }.build()
        val shell = ShellSession(target, staged, login)
        val socket = clients.shellClient(session).newWebSocket(Request.Builder().url(url).build(), shell.listener(loginLine))
        shell.attach(socket)
        return shell
    }
}
