package com.pdm.app.data.api

import java.io.IOException
import javax.net.ssl.SSLException
import okhttp3.Interceptor
import okhttp3.Response

/**
 * One extra attempt for a read whose connection failed. Writes are never replayed: OkHttp's own
 * [okhttp3.OkHttpClient.retryOnConnectionFailure] is off, and this interceptor ignores them too.
 * A changed session, a cancelled call, or a TLS failure stays failed.
 */
class GetRetryInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val method = request.method.uppercase()
        if (method != "GET" && method != "HEAD") return chain.proceed(request)
        return try {
            chain.proceed(request)
        } catch (e: IOException) {
            if (!retryable(chain, e)) throw e
            chain.proceed(request)
        }
    }

    private fun retryable(chain: Interceptor.Chain, error: IOException): Boolean {
        if (error is SSLException) return false
        if (chain.call().isCanceled()) return false
        return generateSequence<Throwable>(error) { it.cause }
            .none { it.message.equals("Session changed", ignoreCase = true) }
    }
}
