package com.pdm.app.data.api

import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real loopback TLS handshakes through the app's trust manager and badge observer. No LAN or credentials. */
class TlsNegotiationTest {
    @Test
    fun badgeTracksServerNegotiationInBothDirections() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=localhost")
        val now = System.currentTimeMillis()
        val certificate = JcaX509CertificateConverter().getCertificate(
            JcaX509v3CertificateBuilder(
                name, BigInteger.ONE, Date(now - 60_000), Date(now + 3_600_000), name, keys.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private)),
        )
        val password = "test-only".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("server", keys.private, password, arrayOf(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, password)
        }
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        val pin = CertUtils.computeSha256Fingerprint(certificate)
        val trust = TofuTrustManager("localhost", allowUnconfirmed = false, pinLookup = { pin })
        val feed = ApiActivityFeed()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(createTofuSslSocketFactory(trust), trust)
            .hostnameVerifier(createTofuHostnameVerifier("localhost"))
            .followRedirects(false)
            .followSslRedirects(false)
            .readTimeout(5, TimeUnit.SECONDS)
            .addInterceptor(ApiActivityInterceptor(feed, 1) { true })
            .build()
        val worker = Executors.newSingleThreadExecutor()
        try {
            for (version in listOf("TLSv1.2", "TLSv1.3", "TLSv1.2")) {
                (serverContext.serverSocketFactory.createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket).use { server ->
                    server.enabledProtocols = arrayOf(version)
                    server.soTimeout = 5000
                    val negotiated = worker.submit<String> {
                        (server.accept() as SSLSocket).use { socket ->
                            socket.soTimeout = 5000
                            socket.startHandshake()
                            val reader = socket.inputStream.bufferedReader()
                            while (!reader.readLine().isNullOrEmpty()) { /* Read HTTP request headers. */ }
                            socket.outputStream.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                            socket.outputStream.flush()
                            socket.session.protocol
                        }
                    }
                    client.newCall(Request.Builder().url("https://localhost:${server.localPort}/").build()).execute().use { response ->
                        assertEquals("OK", response.body!!.string())
                        assertEquals(version, response.handshake!!.tlsVersion.javaName)
                        assertEquals(version, feed.tls.value)
                    }
                    assertEquals(version, negotiated.get(5, TimeUnit.SECONDS))
                }
            }
        } finally {
            worker.shutdownNow()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
