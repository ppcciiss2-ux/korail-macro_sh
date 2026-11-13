package com.korailmacro.app.korail

import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class KorailTlsPolicyTest {
    private val host = "smart.letskorail.com"
    private val legacyPins = arrayOf(
        "sha256/+aKPhqKL0hOK1/1r5KYM4uKXDQ5kSOf5/2iSbcLtNms=",
        "sha256/hETpgVvaLC0bvcGG3t0cuqiHvr4XyP2MTwCiqhgRWwU=",
        "sha256/cGuxAXyFXFkWm61cF4HPWX8S0srS9j0aSqN0k4AP+4A="
    )

    @Test fun rotatedIntermediateAndLegacyPinsAreConfiguredForExactHost() {
        // Expected SPKI verified independently from trusted TLS, not from production configuration.
        val expected = CertificatePinner.Builder().add(host, *legacyPins,
            "sha256/nZ4QsWxivBcuuFkI8dXgfa0Pb2o1sjZ8hKx6h+729xw=").build()
        assertEquals(expected, KorailTlsPolicy.pinner)
    }

    @Test fun unrelatedSystemTrustedCertificateIsStillRejectedByPins() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val allowed = legacyPins.toSet() + "sha256/nZ4QsWxivBcuuFkI8dXgfa0Pb2o1sjZ8hKx6h+729xw="
        val unrelated = manager.acceptedIssuers.first { CertificatePinner.pin(it) !in allowed }
        assertThrows(SSLPeerUnverifiedException::class.java) {
            KorailTlsPolicy.pinner.check(host, listOf(unrelated))
        }
    }

    @Test fun emptyCertificateChainIsRejected() {
        assertThrows(SSLPeerUnverifiedException::class.java) {
            KorailTlsPolicy.pinner.check(host, emptyList())
        }
    }

    @Test fun liveChainPassesNewPinsAndFailsLegacyPinsWithoutOldCrossRoot() {
        assumeTrue(System.getenv("KORAIL_TLS_SMOKE") == "1")
        val client = OkHttpClient.Builder().certificatePinner(KorailTlsPolicy.pinner)
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
        client.newCall(Request.Builder().url("https://$host/").head().build()).execute().use {
            val handshake = it.handshake!!
            // Windows/JVM may append the old cross-signed root; Android screenshot did not have it.
            val androidShape = handshake.peerCertificates.filter { cert -> CertificatePinner.pin(cert) != legacyPins[2] }
            assertTrue(androidShape.isNotEmpty())
            KorailTlsPolicy.pinner.check(host, androidShape)
            val old = CertificatePinner.Builder().add(host, *legacyPins).build()
            assertThrows(SSLPeerUnverifiedException::class.java) { old.check(host, androidShape) }
            println("Trusted TLS and new pins passed; Android-shaped chain fails legacy pins; HTTP ${it.code}")
        }
    }
}
