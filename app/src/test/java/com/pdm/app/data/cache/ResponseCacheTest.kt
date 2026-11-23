package com.pdm.app.data.cache

import java.io.File
import java.io.IOException
import java.nio.file.Files
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Reversible stand-in for the Keystore sealer, so files are not plain JSON even in tests. */
private object FlipSealer : BlobSealer {
    override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }
    override fun open(sealed: ByteArray) = seal(sealed)
}

class ResponseCacheTest {
    private var now = 1_000_000L
    private val root: File = Files.createTempDirectory("pdm-cache").toFile()
    // Writes run inline here so a test can read right after the response.
    private fun cache(maxBytes: Long = 16L * 1024 * 1024) =
        ResponseCache(root, FlipSealer, clock = { now }, maxBytesPerLogin = maxBytes, writer = { it.run() })

    private val body = """{"data":[{"id":"edge-a","type":"pve"}]}""".encodeToByteArray()

    @Test
    fun onlyTheWritableLoginIsStoredAndReadBack() {
        val cache = cache()
        cache.put("a", "remotes/remote", body)
        assertNull("not writable yet", cache.get("a", "remotes/remote"))
        cache.writableLogin = "a"
        cache.put("a", "remotes/remote", body)
        val entry = cache.get("a", "remotes/remote")!!
        assertEquals(now, entry.savedAt)
        assertEquals(body.decodeToString(), entry.body.decodeToString())
        assertNull("logins never share entries", cache.get("b", "remotes/remote"))
        assertFalse("sealed at rest", root.walk().filter { it.isFile }.any { it.readText().contains("edge-a") })
    }

    @Test
    fun anUnchangedAnswerIsNotResealedButCountsAsConfirmed() {
        var seals = 0
        val counting = object : BlobSealer {
            override fun seal(plain: ByteArray): ByteArray { seals++; return FlipSealer.seal(plain) }
            override fun open(sealed: ByteArray) = FlipSealer.open(sealed)
        }
        val cache = ResponseCache(root, counting, clock = { now }, writer = { it.run() })
        cache.writableLogin = "a"
        cache.put("a", "remotes/remote", body)
        val first = now
        now += 30_000
        cache.put("a", "remotes/remote", body)
        assertEquals("same body: no second seal", 1, seals)
        assertEquals("but the server confirmed it again", now, cache.get("a", "remotes/remote")!!.savedAt)
        assertEquals(now, cache.stats("a").newestAt)

        now += 30_000
        val changed = """{"data":[{"id":"edge-b","type":"pve"}]}""".encodeToByteArray()
        cache.put("a", "remotes/remote", changed)
        assertEquals(2, seals)
        assertEquals(changed.decodeToString(), cache.get("a", "remotes/remote")!!.body.decodeToString())
        assertTrue(first < cache.get("a", "remotes/remote")!!.savedAt)

        // After a release the next write is a real one again.
        cache.release("a")
        cache.writableLogin = "a"
        cache.put("a", "remotes/remote", changed)
        assertEquals(3, seals)
    }

    @Test
    fun secretsAreStrippedBeforeSealing() {
        val cache = cache().apply { writableLogin = "a" }
        val config = """{"data":{"cores":2,"cipassword":"hunter2","sshkeys":"ssh-ed25519 AAA","name":"web",""" +
            """"nested":{"password":"x","api-token":"y","keep":"z"}}}"""
        cache.put("a", "pve/remotes/r/qemu/100/config?state=active", config.encodeToByteArray())
        val stored = cache.get("a", "pve/remotes/r/qemu/100/config?state=active")!!.body.decodeToString()
        assertFalse(stored.contains("hunter2"))
        assertFalse(stored.contains("ssh-ed25519"))
        assertFalse(stored.contains("\"x\""))
        assertFalse(stored.contains("\"y\""))
        assertTrue(stored.contains("\"cores\":2"))
        assertTrue(stored.contains("\"keep\":\"z\""))
    }

    @Test
    fun nonJsonIsNotCached() {
        val cache = cache().apply { writableLogin = "a" }
        cache.put("a", "k", "<html>".encodeToByteArray())
        assertNull(cache.get("a", "k"))
    }

    @Test
    fun entriesExpireAfterSevenDays() {
        val cache = cache().apply { writableLogin = "a" }
        cache.put("a", "k", body)
        now += 7 * 24 * 3_600_000L - 1
        assertNotNull(cache.get("a", "k"))
        now += 2
        assertNull(cache.get("a", "k"))
    }

    @Test
    fun sizeCapEvictsOldestFirst() {
        val cache = cache(maxBytes = body.size * 2L + 10).apply { writableLogin = "a" }
        cache.put("a", "one", body)
        now += 1000
        cache.put("a", "two", body)
        now += 1000
        cache.put("a", "three", body)
        assertNull(cache.get("a", "one"))
        assertNotNull(cache.get("a", "three"))
    }

    @Test
    fun releaseDropsOneLoginAndStopsItsWrites() {
        val cache = cache().apply { writableLogin = "a" }
        cache.put("a", "k", body)
        cache.release("a")
        assertNull(cache.get("a", "k"))
        assertNull(cache.writableLogin)
        assertEquals(0, cache.stats("a").entries)
    }

    @Test
    fun unreadableEntryIsDroppedNotThrown() {
        val cache = cache().apply { writableLogin = "a" }
        cache.put("a", "k", body)
        root.walk().filter { it.isFile }.forEach { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(cache.get("a", "k"))
        assertEquals(0, cache.stats("a").entries)
    }

    @Test
    fun servedMarksReportTheOldestCachedAnswer() {
        val cache = cache()
        val mark = cache.servedMark()
        assertNull(cache.oldestServedSince(mark))
        cache.noteServed(500)
        cache.noteServed(300)
        assertEquals(300L, cache.oldestServedSince(mark))
        assertNull(cache.oldestServedSince(cache.servedMark()))
    }

    // --- interceptors, through a real OkHttp client with a fake network at the end of the chain ---

    private val url = "https://pdm.example:8443/api2/json/resources/list?max-age=30"

    private fun client(cache: ResponseCache, network: Interceptor, offline: Boolean = false) =
        OkHttpClient.Builder()
            .addInterceptor(if (offline) CacheOnlyInterceptor(cache, "a") else CachingInterceptor(cache, "a"))
            .addInterceptor(network)
            .build()

    private fun ok(json: String) = Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(json.toResponseBody("application/json".toMediaType())).build()
    }

    private val down = Interceptor { throw java.net.ConnectException("Failed to connect") }

    @Test
    fun liveReadIsSavedAndUsedWhenTheServerIsGone() {
        val cache = cache().apply { writableLogin = "a" }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().use {
            assertNull(it.header(CACHE_HEADER))
            assertEquals("""{"data":[1]}""", it.body!!.string())
        }
        assertEquals(1, cache.stats("a").entries)
        val mark = cache.servedMark()
        client(cache, down).newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals(now.toString(), it.header(CACHE_HEADER))
            // The saved data, with the save time stamped into the envelope for the reader.
            assertEquals("""{"data":[1],"$CACHED_AT_FIELD":$now}""", it.body!!.string())
        }
        assertEquals(now, cache.oldestServedSince(mark))
    }

    @Test
    fun aLiveOnlyReadIsNeverAnsweredFromTheCache() {
        val cache = cache().apply { writableLogin = "a" }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().close()
        assertEquals(1, cache.stats("a").entries)
        // Is the machine up right now? A saved answer must not say yes while it is down.
        try {
            client(cache, down).newCall(Request.Builder().url(url).header(LIVE_ONLY_HEADER, "1").build()).execute().close()
            fail("a live-only read must fail when the server is down")
        } catch (_: IOException) {
        }
        // The marker never reaches the server.
        var sent: String? = "unset"
        val spy = Interceptor { chain ->
            sent = chain.request().header(LIVE_ONLY_HEADER)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[2]}""".toResponseBody("application/json".toMediaType())).build()
        }
        client(cache, spy).newCall(Request.Builder().url(url).header(LIVE_ONLY_HEADER, "1").build()).execute().close()
        assertNull(sent)
    }

    @Test
    fun missingEntryStillFailsAndWritesNeverFallBack() {
        val cache = cache().apply { writableLogin = "a" }
        try {
            client(cache, down).newCall(Request.Builder().url(url).build()).execute()
            fail("no cache entry: the network error must surface")
        } catch (_: IOException) {
        }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().close()
        val post = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        try {
            client(cache, down).newCall(post).execute()
            fail("a write must never be answered from the cache")
        } catch (_: IOException) {
        }
    }

    @Test
    fun tlsFailureIsNeverHiddenByTheCache() {
        val cache = cache().apply { writableLogin = "a" }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().close()
        val badCert = Interceptor { throw javax.net.ssl.SSLHandshakeException("certificate changed") }
        try {
            client(cache, badCert).newCall(Request.Builder().url(url).build()).execute()
            fail("TLS failure must surface")
        } catch (e: IOException) {
            assertTrue(e is javax.net.ssl.SSLException)
        }
    }

    @Test
    fun offlineSessionReadsCacheAndRefusesWritesWithoutNetwork() {
        val cache = cache().apply { writableLogin = "a" }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().close()
        val network = Interceptor { fail("offline must not reach the network"); throw IOException() }
        client(cache, network, offline = true).newCall(Request.Builder().url(url).build()).execute().use {
            assertEquals("""{"data":[1],"$CACHED_AT_FIELD":$now}""", it.body!!.string())
        }
        val post = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).build()
        try {
            client(cache, network, offline = true).newCall(post).execute()
            fail("offline writes are refused")
        } catch (e: IOException) {
            assertEquals(CacheOnlyInterceptor.OFFLINE_WRITE, e.message)
        }
    }

    @Test
    fun offlineLiveOnlyReadFailsClosed() {
        val cache = cache().apply { writableLogin = "a" }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().close()
        val network = Interceptor { fail("live-only must not be served from the offline cache"); throw IOException() }
        try {
            client(cache, network, offline = true)
                .newCall(Request.Builder().url(url).header(LIVE_ONLY_HEADER, "1").build())
                .execute()
            fail("a live-only read must not be answered offline")
        } catch (e: IOException) {
            assertEquals(CacheOnlyInterceptor.OFFLINE_LIVE, e.message)
        }
    }

    @Test
    fun aLiveAnswerIsNeverStampedAndANonObjectIsLeftAlone() {
        val cache = cache().apply { writableLogin = "a" }
        client(cache, ok("""{"data":[1]}""")).newCall(Request.Builder().url(url).build()).execute().use {
            assertFalse(it.body!!.string().contains(CACHED_AT_FIELD))
        }
        assertEquals("[1,2]", String(stampCached("[1,2]".toByteArray(), 5L)))
        assertEquals("not json", String(stampCached("not json".toByteArray(), 5L)))
    }
}
