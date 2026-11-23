package com.pdm.app.data.shell

/**
 * Proxmox termproxy framing, the same the web UI's xterm.js client speaks:
 * `0:<utf8 length>:<data>` for keystrokes, `1:<cols>:<rows>:` for a resize, `2` to keep the line up.
 * The terminal answers `OK` once it accepted the login line, then streams raw output.
 */
object TermProtocol {
    fun input(data: String): String = "0:${data.toByteArray(Charsets.UTF_8).size}:$data"

    fun resize(cols: Int, rows: Int): String = "1:$cols:$rows:"

    const val PING = "2"

    /** The login line a local termproxy (the PDM host) reads first. Remote shells get theirs from PDM. */
    fun login(user: String, ticket: String): String = "$user:$ticket\n"
}

/**
 * Strips the `OK` that opens a termproxy stream, even when it arrives split or glued to the first
 * output. Anything else passes through untouched: the terminal is better shown than swallowed.
 */
class TermHandshake {
    private var pending = ByteArray(0)

    var accepted = false
        private set

    /** Bytes to show; empty while the first two bytes are still incomplete. */
    fun feed(bytes: ByteArray): ByteArray {
        if (accepted) return bytes
        pending += bytes
        if (pending.size < OK.size) {
            if (OK.copyOf(pending.size).contentEquals(pending)) return ByteArray(0)
        }
        accepted = true
        val out = if (pending.size >= OK.size && pending.copyOf(OK.size).contentEquals(OK)) {
            pending.copyOfRange(OK.size, pending.size)
        } else {
            pending
        }
        pending = ByteArray(0)
        return out
    }

    private companion object {
        val OK = "OK".toByteArray()
    }
}

/**
 * Terminal output kept for replay: a recreated view (rotation, DeX resize) writes it again and lands
 * on the same screen. Bounded; the oldest bytes go first. Offsets count every byte ever appended.
 */
class ShellBuffer(private val capacity: Int = 1 shl 20) {
    private val bytes = java.io.ByteArrayOutputStream()
    private var start = 0L

    @get:Synchronized
    var end = 0L
        private set

    @Synchronized
    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        bytes.write(chunk)
        end += chunk.size
        val over = bytes.size() - capacity
        if (over > 0) {
            val kept = bytes.toByteArray().copyOfRange(over, bytes.size())
            bytes.reset()
            bytes.write(kept)
            start += over
        }
    }

    /** Everything from [offset] on (or from the oldest byte still kept), and the offset to ask for next. */
    @Synchronized
    fun readFrom(offset: Long): Pair<ByteArray, Long> {
        val from = maxOf(offset, start)
        if (from >= end) return ByteArray(0) to end
        val all = bytes.toByteArray()
        return all.copyOfRange((from - start).toInt(), all.size) to end
    }
}

/** What the terminal is waiting for, read off the end of its output. */
enum class PromptKind {
    LOGIN,
    PASSWORD,
    SHELL,

    /** The node's login gave up waiting ("login: timed out after 60 seconds"); the terminal is dead. */
    LOGIN_TIMED_OUT,
}

/**
 * Reads the last screen line without colour codes. A node reached through a non-root PDM link
 * shows `login:` first; staged commands wait for a real `#`/`$` prompt so they never become a username.
 */
class PromptWatch {
    private var tail = ""

    fun feed(bytes: ByteArray): PromptKind? {
        tail = (tail + String(bytes, Charsets.UTF_8)).takeLast(512)
        val clean = tail.replace(ANSI, "")
        val line = clean.substringAfterLast('\n').substringAfterLast('\r').trimEnd()
        // The timeout notice may end with a line break; it is the last thing the dead terminal says.
        val lastText = clean.split('\n', '\r').lastOrNull { it.isNotBlank() }.orEmpty()
        return when {
            line.isEmpty() && LOGIN_TIMEOUT.containsMatchIn(lastText) -> PromptKind.LOGIN_TIMED_OUT
            LOGIN_TIMEOUT.containsMatchIn(line) -> PromptKind.LOGIN_TIMED_OUT
            line.endsWith("login:", ignoreCase = true) -> PromptKind.LOGIN
            line.endsWith("password:", ignoreCase = true) -> PromptKind.PASSWORD
            line.endsWith("#") || line.endsWith("$") -> PromptKind.SHELL
            else -> null
        }
    }

    private companion object {
        /** util-linux login's own words when nobody signs in in time; nothing else prints this. */
        val LOGIN_TIMEOUT = Regex("""login: timed out after \d+ seconds""", RegexOption.IGNORE_CASE)
        val ANSI = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)|\u001b[()][0-9A-Za-z]")
    }
}
