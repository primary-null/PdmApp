package com.pdm.app.ui.login

import com.pdm.app.data.model.SessionState

/** Account line for a parked session. Never includes a secret or ticket. */
internal fun jumpBackCaption(session: SessionState, demo: Boolean): String {
    if (demo || session.config.host.equals("demo", ignoreCase = true)) return "Demo fleet"
    val who = session.username?.takeIf { it.isNotBlank() } ?: session.config.username
    return "${session.config.displayHost} · $who"
}

internal fun jumpBackNote(secretSaved: Boolean): String = if (secretSaved) {
    "Saved secret stays in the device vault. Jump back uses the session still in memory."
} else {
    "This return is only in memory. Save credentials on the next connect to keep the secret in the device vault."
}
