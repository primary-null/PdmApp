package com.pdm.app.data.shell

/**
 * Signs in at a node's own `login:` prompt. PDM relays node shells with its API token, and PVE only
 * gives a direct root shell to `root@pam` itself, so through PDM the node always asks for a login.
 *
 * Answers each prompt once: [user] at `login:`, the password at `Password:`. An answer is only
 * given after fresh output (a new line since the last answer), so a prompt split over two frames is
 * not mistaken for a second prompt. A login prompt after the password means the node said no: the
 * machine stops ([State.REFUSED]) and leaves the terminal to the person. Never loops, never guesses.
 */
class NodeLogin(private val user: String, password: String) {
    enum class State { WAITING, SENT_USER, SENT_PASSWORD, SIGNED_IN, REFUSED }

    private var password: String? = password

    var state = State.WAITING
        private set

    private var freshLine = true

    /** Feed each chunk of output and the prompt it ends on; returns what to type, if anything. */
    fun onOutput(text: String, prompt: PromptKind?): String? {
        if ('\n' in text) freshLine = true
        if (prompt == null || state == State.SIGNED_IN || state == State.REFUSED) return null
        return when {
            // Already a shell (a root@pam link, or a node without a login step): nothing to do.
            prompt == PromptKind.SHELL -> { state = State.SIGNED_IN; forget(); null }
            !freshLine -> null
            state == State.WAITING && prompt == PromptKind.LOGIN -> answer(State.SENT_USER, user)
            state == State.SENT_USER && prompt == PromptKind.PASSWORD -> answer(State.SENT_PASSWORD, password)
            // "Login incorrect", then login: again. Or a login prompt that skipped the password.
            prompt == PromptKind.LOGIN && state != State.WAITING -> { state = State.REFUSED; forget(); null }
            else -> null
        }
    }

    private fun answer(next: State, text: String?): String? {
        text ?: return null
        state = next
        freshLine = false
        if (next == State.SENT_PASSWORD) forget()
        return "$text\r"
    }

    /** The password is typed once and then dropped from this object. */
    private fun forget() {
        password = null
    }
}
