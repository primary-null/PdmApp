package com.pdm.app.data.cache

import com.pdm.app.data.api.AppJson
import com.pdm.app.data.session.SessionIdentity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Offline copy of what PDM answered, one store per login.
 *
 * - **Filled** by every successful live read (write-through), so it is as fresh as the last time
 *   that data was on screen live. Only logins with a saved credential are written ([writableLogin]).
 * - **Read** when a live read cannot reach the server, or for a cache-only (offline) session.
 * - **Released** after [maxAgeMs] (7 days), oldest-first past [maxBytesPerLogin], and in full when
 *   the login's saved secret is forgotten, the saved connection is removed, the cache is switched
 *   off, or on Clean Slate.
 * - **Never holds** writes, tickets or login answers. Secret-looking JSON keys (passwords,
 *   `sshkeys`, tokens) are stripped before a body is sealed.
 *
 * Entries are sealed with [sealer] (Keystore AES-GCM on device). File mtime is the save time, or
 * the last time the server sent the same answer again: an unchanged body only touches the mtime,
 * so the 30 s refresh does not re-seal and rewrite identical data.
 */
class ResponseCache(
    private val root: File,
    private val sealer: BlobSealer,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 7 * 24 * 3_600_000L,
    private val maxBytesPerLogin: Long = 16L * 1024 * 1024,
    /** Where [putAsync] writes. One low-priority thread: a write never holds up a response. */
    private val writer: Executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "pdm-cache-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    },
) {
    class Entry(val key: String, val savedAt: Long, val body: ByteArray)

    data class Stats(val entries: Int, val bytes: Long, val newestAt: Long?, val oldestAt: Long?)

    /** The live login allowed to write; null stops all writes (reads still work). */
    @Volatile
    var writableLogin: String? = null

    // Files and the "served" bookkeeping have separate locks: the main thread reads the latter
    // on every fleet load and must never wait for disk.
    private val files = Any()
    /** Digest of the body last written per entry file, guarded by [files]. Lost on restart: then one rewrite. */
    private val written = HashMap<String, ByteArray>()
    private val servedLock = Any()
    private val served = ArrayDeque<Pair<Long, Long>>()
    private var servedSeq = 0L

    fun isWritable(login: String): Boolean = writableLogin == login

    /** Save off the request path; the interceptor returns the response at once. */
    fun putAsync(login: String, key: String, body: ByteArray) {
        if (!isWritable(login)) return
        writer.execute { runCatching { put(login, key, body) } }
    }

    fun put(login: String, key: String, body: ByteArray) {
        if (!isWritable(login)) return
        // Parse, scrub and seal outside the lock: CPU work that needs no shared state.
        val clean = scrub(body) ?: return
        val now = clock()
        val digest = MessageDigest.getInstance("SHA-256").digest(clean)
        val file = File(loginDir(login), hash(key) + SUFFIX)
        synchronized(files) {
            if (!isWritable(login)) return
            // Same answer as last time: record that the server confirmed it now, skip the Keystore and the flash.
            if (file.isFile && written[file.path]?.contentEquals(digest) == true) {
                file.setLastModified(now)
                return
            }
        }
        val plain = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).use {
                it.writeInt(FORMAT)
                it.writeLong(now)
                it.writeUTF(key)
                it.writeInt(clean.size)
                it.write(clean)
            }
        }.toByteArray()
        val sealed = sealer.seal(plain)
        synchronized(files) {
            // Released (secret forgotten, cache off) while this write was queued: drop it.
            if (!isWritable(login)) return
            val dir = loginDir(login).apply { mkdirs() }
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeBytes(sealed)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            file.setLastModified(now)
            written[file.path] = digest
            trim(dir)
        }
    }

    /** The saved answer for [key], or null when missing, expired, or unreadable (then deleted). */
    fun get(login: String, key: String): Entry? {
        val file = File(loginDir(login), hash(key) + SUFFIX)
        var confirmedAt = 0L
        val bytes = synchronized(files) {
            if (!file.isFile) return null
            confirmedAt = file.lastModified()
            if (clock() - confirmedAt > maxAgeMs) {
                file.delete()
                return null
            }
            file.readBytes()
        }
        return try {
            DataInputStream(ByteArrayInputStream(sealer.open(bytes))).use { input ->
                if (input.readInt() != FORMAT) return null
                val savedAt = input.readLong()
                val storedKey = input.readUTF()
                if (storedKey != key) return null
                val body = ByteArray(input.readInt()).also { input.readFully(it) }
                // An unchanged answer only moves the mtime; that is when the data was last confirmed.
                Entry(storedKey, maxOf(savedAt, confirmedAt), body)
            }
        } catch (_: Exception) {
            // Wrong key after a Keystore reset, a torn write: drop it rather than fail every read.
            synchronized(files) { file.delete() }
            null
        }
    }

    /** Remember that an answer saved at [savedAt] was shown instead of a live one. */
    fun noteServed(savedAt: Long) = synchronized(servedLock) {
        servedSeq++
        served.addLast(servedSeq to savedAt)
        while (served.size > 256) served.removeFirst()
    }

    /** A mark for [oldestServedSince]. */
    fun servedMark(): Long = synchronized(servedLock) { servedSeq }

    /** Oldest save time of cached answers shown after [mark]; null when everything was live. */
    fun oldestServedSince(mark: Long): Long? =
        synchronized(servedLock) { served.filter { it.first > mark }.minOfOrNull { it.second } }

    fun stats(login: String): Stats = synchronized(files) {
        val entries = entryFiles(loginDir(login)).filter { clock() - it.lastModified() <= maxAgeMs }
        Stats(
            entries = entries.size,
            bytes = entries.sumOf { it.length() },
            newestAt = entries.maxOfOrNull { it.lastModified() },
            oldestAt = entries.minOfOrNull { it.lastModified() },
        )
    }

    /** When [key] was last saved for [login], without decrypting it. */
    fun savedAt(login: String, key: String): Long? = synchronized(files) {
        File(loginDir(login), hash(key) + SUFFIX).takeIf { it.isFile && clock() - it.lastModified() <= maxAgeMs }
            ?.lastModified()
    }

    fun release(login: String) = synchronized(files) {
        if (writableLogin == login) writableLogin = null
        val dir = loginDir(login)
        written.keys.removeAll { it.startsWith(dir.path) }
        dir.deleteRecursively()
    }

    fun releaseAll() = synchronized(files) {
        writableLogin = null
        written.clear()
        root.deleteRecursively()
    }

    /** Drop expired entries everywhere and enforce the size cap. Cheap; runs at start. */
    fun prune() = synchronized(files) {
        root.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            entryFiles(dir).filter { clock() - it.lastModified() > maxAgeMs }.forEach { it.delete() }
            dir.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
            trim(dir)
            if (dir.listFiles().isNullOrEmpty()) dir.delete()
        }
    }

    private fun trim(dir: File) {
        val files = entryFiles(dir).sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (total > maxBytesPerLogin && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }

    private fun entryFiles(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(SUFFIX) }.orEmpty()

    private fun loginDir(login: String) = File(root, hash(login).take(32))

    companion object {
        private const val FORMAT = 1
        private const val SUFFIX = ".bin"

        /** One store per login; the same identity the session uses. */
        fun loginKey(identity: SessionIdentity): String =
            "${identity.endpoint}|${identity.authMode}|${identity.account}"

        private val SECRET_KEY = Regex(
            "(?i)^(.*password.*|.*secret.*|sshkeys|token|.*-token|api-?token|ticket|csrfpreventiontoken)$",
        )

        /** JSON with secret-looking keys removed, or null when [body] is not a JSON document (then not cached). */
        internal fun scrub(body: ByteArray): ByteArray? {
            val element = try {
                AppJson.parseToJsonElement(body.decodeToString())
            } catch (_: Exception) {
                return null
            }
            // AppJson is lenient: a bare word parses as a string. PDM always answers with an object.
            if (element !is JsonObject && element !is JsonArray) return null
            return AppJson.encodeToString(JsonElement.serializer(), strip(element)).encodeToByteArray()
        }

        private fun strip(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.filterKeys { !SECRET_KEY.matches(it) }.mapValues { strip(it.value) })
            is JsonArray -> JsonArray(element.map(::strip))
            else -> element
        }

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
