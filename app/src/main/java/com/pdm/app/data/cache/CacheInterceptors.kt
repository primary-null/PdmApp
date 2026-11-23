package com.pdm.app.data.cache

import java.io.IOException
import javax.net.ssl.SSLException
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Set on answers that came from the offline cache; the value is the save time (epoch ms). */
const val CACHE_HEADER = "X-PDM-Cached-At"

/**
 * The same save time, written into the JSON envelope of a cached answer, so a read decoded to
 * [com.pdm.app.data.model.PdmResponse] knows it is not live without access to the headers.
 */
const val CACHED_AT_FIELD = "x-pdm-app-cached-at"

private val stampJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/** The cached body with [CACHED_AT_FIELD] added; anything that isn't a JSON object is left as is. */
internal fun stampCached(body: ByteArray, savedAt: Long): ByteArray = try {
    val root = stampJson.parseToJsonElement(body.toString(Charsets.UTF_8))
    if (root is kotlinx.serialization.json.JsonObject) {
        kotlinx.serialization.json.JsonObject(root + (CACHED_AT_FIELD to kotlinx.serialization.json.JsonPrimitive(savedAt)))
            .toString().toByteArray(Charsets.UTF_8)
    } else {
        body
    }
} catch (e: kotlinx.serialization.SerializationException) {
    body
} catch (e: IllegalArgumentException) {
    body
}

private const val MAX_BODY_BYTES = 4L * 1024 * 1024
private val JSON = "application/json".toMediaType()

/** `path?query` under `/api2/json/`; the same request always maps to the same entry. */
internal fun cacheKey(request: Request): String {
    val path = request.url.encodedPath.substringAfter("/api2/json/", request.url.encodedPath)
    return request.url.encodedQuery?.let { "$path?$it" } ?: path
}

private fun cachedResponse(request: Request, entry: ResponseCache.Entry): Response =
    Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK (offline cache)")
        .header("Content-Type", JSON.toString())
        .header(CACHE_HEADER, entry.savedAt.toString())
        .body(stampCached(entry.body, entry.savedAt).toResponseBody(JSON))
        .build()

/**
 * Live session. A successful GET is copied into the cache (when this login may write). A GET that
 * cannot reach the server is answered from the cache instead, so every screen keeps working on
 * the last data it saw. Writes, TLS failures (a changed certificate must stay loud) and cancelled
 * calls are never answered from the cache.
 */
/** A request carrying this header is never answered from the cache: a failure stays a failure. */
const val LIVE_ONLY_HEADER = "X-Pdm-Live-Only"

internal class CachingInterceptor(
    private val cache: ResponseCache,
    private val login: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        if (original.method != "GET") return chain.proceed(original)
        if (original.header(LIVE_ONLY_HEADER) != null) {
            return chain.proceed(original.newBuilder().removeHeader(LIVE_ONLY_HEADER).build())
        }
        val request = original
        val key = cacheKey(request)
        val response = try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (e is SSLException || chain.call().isCanceled()) throw e
            val entry = cache.get(login, key) ?: throw e
            cache.noteServed(entry.savedAt)
            return cachedResponse(request, entry)
        }
        if (response.code == 200 && cache.isWritable(login)) {
            val length = response.body?.contentLength() ?: -1L
            if (length in 0..MAX_BODY_BYTES || length == -1L) {
                val peeked = response.peekBody(MAX_BODY_BYTES + 1).bytes()
                if (peeked.size <= MAX_BODY_BYTES) cache.putAsync(login, key, peeked)
            }
        }
        return response
    }
}

/** Offline session: answers GETs from the cache and never touches the network. */
internal class CacheOnlyInterceptor(
    private val cache: ResponseCache,
    private val login: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET") throw IOException(OFFLINE_WRITE)
        if (request.header(LIVE_ONLY_HEADER) != null) throw IOException(OFFLINE_LIVE)
        val entry = cache.get(login, cacheKey(request)) ?: throw IOException("Not in the offline cache")
        cache.noteServed(entry.savedAt)
        return cachedResponse(request, entry)
    }

    companion object {
        const val OFFLINE_WRITE = "Offline: showing cached data. Changes need a live connection."
        const val OFFLINE_LIVE = "Offline: this read needs a live connection."
    }
}
