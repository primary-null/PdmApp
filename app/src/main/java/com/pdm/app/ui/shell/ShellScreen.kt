package com.pdm.app.ui.shell

import android.annotation.SuppressLint
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pdm.app.data.shell.NodeLogin
import com.pdm.app.data.shell.PromptKind
import com.pdm.app.data.shell.ShellPhase
import com.pdm.app.data.shell.ShellSession
import com.pdm.app.data.shell.ShellTarget
import com.pdm.app.ui.FleetViewModel
import com.pdm.app.ui.ShellView
import com.pdm.app.ui.components.TechActionPlate
import com.pdm.app.ui.components.TechColors
import com.pdm.app.ui.components.TechConfirmDialog
import kotlinx.coroutines.flow.combine

/** Keys a touch keyboard lacks or hides, and the answers apt and its pager ask for. */
private val EXTRA_KEYS = listOf(
    "Enter" to "\r", "y" to "y", "n" to "n", "Tab" to "\t", "Esc" to "\u001b", "Ctrl-C" to "\u0003",
    "↑" to "\u001b[A", "↓" to "\u001b[B", "q" to "q", "Space" to " ",
)

/** Full-screen terminal over PDM's shell relay. Closing a live shell asks first: it ends apt too. */
@Composable
fun ShellScreen(view: ShellView, vm: FleetViewModel, savedSnippets: List<com.pdm.app.data.shell.Snippet> = emptyList()) {
    var snippetsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val session = vm.activeShell()?.takeIf { it.target == view.target }
    val light = TechColors.IsLight
    // A theme flip (System mode at dusk) rebuilds the page; the new one replays the whole buffer.
    val pump = remember(session, light) { TerminalPump() }
    // A timed-out sign-in leaves nothing running: closing needs no warning, and it can be reopened.
    val ended = view.status.phase == ShellPhase.CLOSED || view.prompt == PromptKind.LOGIN_TIMED_OUT
    val live = !ended
    var confirmClose by remember { mutableStateOf(false) }
    // The keep-alive service's notice needs this on Android 13+. Asked once per shell; Android itself
    // stops asking after two refusals, and the service runs either way.
    val context = androidx.compose.ui.platform.LocalContext.current
    val askNotify = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            askNotify.launch(perm)
        }
    }
    BackHandler { if (live) confirmClose = true else vm.closeShell() }
    if (confirmClose) {
        TechConfirmDialog(
            title = "CLOSE THE TERMINAL?",
            body = "If the upgrade is still running, closing now stops it part way and can leave " +
                "${view.target.title} needing repair. Wait until it has finished.",
            confirmLabel = "Close anyway",
            onConfirm = {
                confirmClose = false
                vm.closeShell()
            },
            onDismiss = { confirmClose = false },
        )
    }
    Column(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
            .background(TechColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "TERMINAL · ${view.target.title}",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = TechColors.Amber,
                )
                Text(
                    statusLine(view),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ended) MaterialTheme.colorScheme.error else TechColors.Mute,
                )
            }
            if (ended) {
                TechActionPlate(label = "Reopen", onClick = vm::reopenShell, emphasized = true)
            }
            TechActionPlate(label = "Close", onClick = {
                if (live) confirmClose = true else vm.closeShell()
            })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (session != null) {
                val background = TechColors.Background
                key(light) { Terminal(session, vm, pump, light, background) }
            } else if (live) {
                Text(
                    "Opening terminal…",
                    Modifier.align(Alignment.Center),
                    fontFamily = FontFamily.Monospace,
                    color = TechColors.Mute,
                )
            }
        }
        if (live && snippetsOpen) {
            SnippetPanel(
                builtins = com.pdm.app.data.shell.Snippets.builtins(view.target),
                saved = savedSnippets,
                // Only at a shell prompt: a snippet typed into "login:" would become a username.
                ready = view.prompt == PromptKind.SHELL,
                onInsert = vm::insertSnippet,
                onSave = vm::saveSnippet,
                onDelete = vm::deleteSnippet,
            )
        }
        // Keys and snippets only while there is something to type into.
        if (live && view.status.phase == ShellPhase.OPEN) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TechActionPlate(label = "Snippets", onClick = { snippetsOpen = !snippetsOpen }, emphasized = snippetsOpen)
                TechActionPlate(label = "Keyboard", onClick = { pump.web?.let(::showKeyboard) })
                EXTRA_KEYS.forEach { (label, seq) ->
                    TechActionPlate(label = label, onClick = { vm.shellInput(seq) }, emphasized = label == "Enter")
                }
            }
        }
    }
}

private fun statusLine(view: ShellView): String = when (view.status.phase) {
    ShellPhase.CONNECTING -> "Connecting…"
    ShellPhase.OPEN -> when {
        view.prompt == PromptKind.LOGIN_TIMED_OUT -> "The sign-in timed out. Reopen to try again."
        view.login == NodeLogin.State.REFUSED ->
            "The root password wasn't accepted" +
                (if (view.forgotSaved) " and has been removed from this device" else "") +
                ". Sign in yourself below" +
                (if (view.staged != null) "; the upgrade will be ready once you're in." else ".")
        view.login == NodeLogin.State.WAITING || view.login == NodeLogin.State.SENT_USER ||
            view.login == NodeLogin.State.SENT_PASSWORD -> "Signing in…"
        view.prompt == PromptKind.LOGIN || view.prompt == PromptKind.PASSWORD ->
            "Sign in as root below" +
                (if (view.staged != null) "; the upgrade will be ready once you're in." else ".")
        view.staged != null && view.stagedTyped ->
            "Ready. Press Enter to start the upgrade, then answer any questions."
        view.staged != null -> "Getting ready…"
        // Once the shell prompt is back, the upgrade has finished (or was declined).
        view.target is ShellTarget.Host && view.target.upgrade && view.prompt != PromptKind.SHELL ->
            "Upgrading. Answer any questions below."
        else -> "Connected"
    }
    ShellPhase.CLOSED -> view.status.message ?: "Terminal closed."
}

/** WebView with the bundled xterm.js. Every byte the session kept is written in order, so a new view replays the screen. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Terminal(session: ShellSession, vm: FleetViewModel, pump: TerminalPump, light: Boolean, background: Color) {
    LaunchedEffect(pump) {
        combine(pump.ready, session.outputVersion) { ready, _ -> ready }.collect { ready ->
            val web = pump.web ?: return@collect
            if (!ready) return@collect
            while (true) {
                val (bytes, next) = session.buffer.readFrom(pump.offset)
                if (bytes.isEmpty()) break
                val chunk = bytes.copyOf(minOf(bytes.size, CHUNK))
                pump.offset += chunk.size
                web.evaluateJavascript("pdmWrite('${Base64.encodeToString(chunk, Base64.NO_WRAP)}')", null)
                if (pump.offset >= next) break
            }
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                keepScreenOn = true
                setBackgroundColor(background.toArgb())
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                webViewClient = object : WebViewClient() {
                    // The page is the terminal and nothing else; links in output never navigate.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun input(data: String) = vm.shellInput(data)

                        @JavascriptInterface
                        fun resize(cols: Int, rows: Int) = vm.shellResize(cols, rows)

                        @JavascriptInterface
                        fun ready() {
                            post { pump.ready.value = true }
                        }
                    },
                    "PdmTerm",
                )
                // xterm takes focus at load, without a gesture, so Android never raises the keyboard
                // by itself. A tap asks for it; the WebView still gets the touch (selection, scroll).
                setOnTouchListener { v, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_UP) showKeyboard(v as WebView)
                    false
                }
                addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or, ob ->
                    if (r - l != or - ol || b - t != ob - ot) (v as WebView).evaluateJavascript("window.pdmFit && pdmFit()", null)
                }
                pump.web = this
                loadUrl("file:///android_asset/xterm/terminal.html" + if (light) "?theme=light" else "")
            }
        },
        onRelease = { web ->
            pump.web = null
            web.destroy()
        },
    )
}

private fun showKeyboard(web: WebView) {
    web.requestFocus()
    web.evaluateJavascript("pdmFocus()", null)
    web.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        ?.showSoftInput(web, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
}

private class TerminalPump {
    var web: WebView? = null
    var offset = 0L
    val ready = kotlinx.coroutines.flow.MutableStateFlow(false)
}

private const val CHUNK = 48 * 1024
