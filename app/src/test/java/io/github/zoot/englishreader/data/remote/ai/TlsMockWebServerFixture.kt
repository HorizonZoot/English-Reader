package io.github.zoot.englishreader.data.remote.ai

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** 可信回环 TLS fixture，供那些在使用凭据前强制要求 HTTPS 的代码路径复用。 */
class TlsMockWebServerFixture {
    private val certificate = HeldCertificate.Builder()
        .commonName("localhost")
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("127.0.0.1")
        .build()
    private val serverCertificates = HandshakeCertificates.Builder()
        .heldCertificate(certificate)
        .build()
    private val clientCertificates = HandshakeCertificates.Builder()
        .addTrustedCertificate(certificate.certificate)
        .build()

    val server = MockWebServer()
    val client: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            clientCertificates.sslSocketFactory(),
            clientCertificates.trustManager
        )
        // MockWebServer 对外可能给出 127.0.0.1，而证书是签发给 localhost 的。
        .hostnameVerifier { _, _ -> true }
        .build()

    fun start() {
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
    }

    fun shutdown() = server.shutdown()
}
