package com.trading.bot.config.secrets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.PublicKey
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

/**
 * Unit-тесты [LockboxSecretResolver]: разбор payload (text/binary/пустой), приоритет
 * источников IAM-токена и обработка ошибок облака.
 *
 * Пакет `config/secrets` имел 8,3 % покрытия ветвей, потому что `HttpClient` создавался
 * внутри резолвера и не подменялся; теперь клиент инжектируется (скан-19).
 */
class LockboxSecretResolverTest {
    private val http = Mockito.mock(HttpClient::class.java)

    @Suppress("UNCHECKED_CAST")
    private fun stubResponse(
        status: Int,
        body: String,
    ) {
        val response = Mockito.mock(HttpResponse::class.java) as HttpResponse<String>
        Mockito.`when`(response.statusCode()).thenReturn(status)
        Mockito.`when`(response.body()).thenReturn(body)
        Mockito
            .`when`(http.send(Mockito.any(HttpRequest::class.java), Mockito.any<HttpResponse.BodyHandler<String>>()))
            .thenReturn(response)
    }

    private fun resolver(
        secretId: String = "sec-1",
        iamToken: String = "iam-explicit",
        saKeyJson: String = "",
    ) = LockboxSecretResolver(secretId, iamToken, saKeyJson, http = http)

    @Test
    fun `blank secret id is rejected before any call`() {
        val ex = assertThrows<IllegalArgumentException> { resolver(secretId = " ").resolve() }

        assertTrue(ex.message!!.contains("lockbox.secret-id"), ex.message)
    }

    @Test
    fun `text binary and empty entries are decoded`() {
        stubResponse(
            200,
            """
            {"entries":[
              {"key":"ALOR_TOKEN","textValue":"t-123"},
              {"key":"BINARY","binaryValue":"${Base64.getEncoder().encodeToString("bin".toByteArray())}"},
              {"key":"EMPTY"},
              {"textValue":"no-key"}
            ]}
            """.trimIndent(),
        )

        val result = resolver().resolve()

        assertEquals("t-123", result["ALOR_TOKEN"])
        assertEquals("bin", result["BINARY"])
        assertEquals("", result["EMPTY"])
        assertEquals(3, result.size, "запись без key не должна попадать в результат: $result")
    }

    @Test
    fun `payload without entries is rejected`() {
        stubResponse(200, """{"version":1}""")

        val ex = assertThrows<IllegalStateException> { resolver().resolve() }

        assertTrue(ex.message!!.contains("has no entries"), ex.message)
    }

    @Test
    fun `non-2xx payload response is reported with status`() {
        stubResponse(403, """{"message":"denied"}""")

        val ex = assertThrows<IllegalStateException> { resolver().resolve() }

        assertTrue(ex.message!!.contains("403"), ex.message)
    }

    @Test
    fun `explicit iam token is used without exchange`() {
        stubResponse(200, """{"entries":[{"key":"A","textValue":"1"}]}""")

        resolver(iamToken = "explicit").resolve()

        Mockito.verify(http).send(Mockito.any(HttpRequest::class.java), Mockito.any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    fun `service account key without service_account_id is rejected`() {
        val ex =
            assertThrows<IllegalStateException> {
                resolver(iamToken = "", saKeyJson = """{"private_key":"x"}""").resolve()
            }

        assertTrue(ex.message!!.contains("service_account_id"), ex.message)
    }

    @Test
    fun `service account key without private_key is rejected`() {
        val ex =
            assertThrows<IllegalStateException> {
                resolver(iamToken = "", saKeyJson = """{"service_account_id":"sa"}""").resolve()
            }

        assertTrue(ex.message!!.contains("private_key"), ex.message)
    }

    @Test
    fun `metadata failure without iam token gives actionable error`() {
        stubResponse(500, "boom")

        val ex = assertThrows<IllegalStateException> { resolver(iamToken = "", saKeyJson = "").resolve() }

        assertTrue(ex.message!!.contains("LOCKBOX_IAM_TOKEN"), ex.message)
    }

    @Test
    fun `metadata success without access_token is rejected`() {
        stubResponse(200, """{"expires_in":3600}""")

        val ex = assertThrows<IllegalStateException> { resolver(iamToken = "", saKeyJson = "").resolve() }

        assertTrue(ex.message!!.contains("LOCKBOX_IAM_TOKEN"), ex.message)
    }

    @Test
    fun `service account key is exchanged for an iam token before the payload call`() {
        val requests = mutableListOf<String>()
        Mockito
            .`when`(http.send(Mockito.any(HttpRequest::class.java), Mockito.any<HttpResponse.BodyHandler<String>>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<HttpRequest>(0)
                requests += request.uri().toString()
                if (request.uri().toString().contains("iam")) {
                    httpResponse("""{"iamToken":"iam-from-sa"}""")
                } else {
                    httpResponse("""{"entries":[{"key":"K","textValue":"v"}]}""")
                }
            }

        val result = LockboxSecretResolver("sec-1", "", rsaKeyJson(), http = http).resolve()

        assertEquals("v", result["K"])
        assertEquals(2, requests.size, requests.toString())
        assertTrue(requests.first().contains("iam"), requests.toString())
        assertTrue(requests.last().contains("payload"), requests.toString())
    }

    @Test
    fun `metadata token is used when no explicit token or service account key is set`() {
        Mockito
            .`when`(http.send(Mockito.any(HttpRequest::class.java), Mockito.any<HttpResponse.BodyHandler<String>>()))
            .thenAnswer { invocation ->
                val request = invocation.getArgument<HttpRequest>(0)
                if (request.uri().toString().contains("169.254")) {
                    httpResponse("""{"access_token":"iam-meta"}""")
                } else {
                    httpResponse("""{"entries":[{"key":"K","textValue":"v"}]}""")
                }
            }

        val result = resolver(iamToken = "", saKeyJson = "").resolve()

        assertEquals("v", result["K"])
    }

    @Test
    fun `iam endpoint without a token is rejected`() {
        stubResponse(200, """{"ok":true}""")

        val ex =
            assertThrows<IllegalStateException> {
                LockboxSecretResolver("sec-1", "", rsaKeyJson(), http = http).resolve()
            }

        assertTrue(ex.message!!.contains("no iamToken"), ex.message)
    }

    @Test
    fun `service account key without authorized key id is rejected`() {
        val json = rsaKeyJson(keyId = null)

        val ex = assertThrows<IllegalStateException> { resolverForJwt().buildIamJwt(json) }

        assertTrue(ex.message!!.contains("authorized key id"), ex.message)
    }

    @Test
    fun `iam jwt uses ps256 with kid and verifies against the public key`() {
        val keyPair = rsaKeyPair()
        val json = rsaKeyJson(keyPair = keyPair, keyId = "key-42")

        val jwt = resolverForJwt().buildIamJwt(json, nowEpochSeconds = 1_700_000_000L)

        val parts = jwt.split(".")
        assertEquals(3, parts.size, jwt)
        val header = String(Base64.getUrlDecoder().decode(parts[0]))
        assertTrue(header.contains("\"alg\":\"PS256\""), header)
        assertTrue(header.contains("\"kid\":\"key-42\""), header)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]))
        assertTrue(payload.contains("\"iss\":\"sa-1\""), payload)
        assertTrue(payload.contains("\"iat\":1700000000"), payload)
        assertTrue(payload.contains("\"exp\":1700003600"), payload)
        assertTrue(payload.contains("https://iam.api.cloud.yandex.net/iam/v1/tokens"), payload)
        assertPs256Signature(jwt, keyPair.public)
    }

    @Test
    fun `pem prefix added by yc iam key create is stripped`() {
        val keyPair = rsaKeyPair()
        val prefixed =
            "PLEASE DO NOT REMOVE THIS LINE! Yandex.Cloud SA Key ID key-9\n" + pem(keyPair.private)
        val json =
            jacksonObjectMapper().writeValueAsString(
                linkedMapOf(
                    "id" to "key-9",
                    "service_account_id" to "sa-1",
                    "private_key" to prefixed,
                ),
            )

        val body = resolverForJwt().pemBase64(prefixed)
        assertEquals(Base64.getEncoder().encodeToString(keyPair.private.encoded), body)

        val jwt = resolverForJwt().buildIamJwt(json, nowEpochSeconds = 1_700_000_000L)
        assertPs256Signature(jwt, keyPair.public)
    }

    private fun rsaKeyJson(
        keyPair: KeyPair = rsaKeyPair(),
        keyId: String? = "key-id-1",
    ): String {
        val fields =
            linkedMapOf(
                "service_account_id" to "sa-1",
                "private_key" to pem(keyPair.private),
            )
        if (keyId != null) fields["id"] = keyId
        return jacksonObjectMapper().writeValueAsString(fields)
    }

    private fun rsaKeyPair(): KeyPair {
        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    private fun pem(privateKey: java.security.PrivateKey): String {
        val encoder = Base64.getMimeEncoder(64, "\n".toByteArray())
        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(encoder.encodeToString(privateKey.encoded))
            append("-----END PRIVATE KEY-----")
        }
    }

    private fun resolverForJwt() = LockboxSecretResolver("sec-1", "", "", http = http)

    private fun assertPs256Signature(
        jwt: String,
        publicKey: PublicKey,
    ) {
        val parts = jwt.split(".")
        assertEquals(3, parts.size, jwt)
        val verifier = Signature.getInstance("RSASSA-PSS")
        verifier.setParameter(PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1))
        verifier.initVerify(publicKey)
        verifier.update("${parts[0]}.${parts[1]}".toByteArray())
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])), "PS256 signature must verify")
    }

    @Suppress("UNCHECKED_CAST")
    private fun httpResponse(body: String): HttpResponse<String> {
        val response = Mockito.mock(HttpResponse::class.java) as HttpResponse<String>
        Mockito.`when`(response.statusCode()).thenReturn(200)
        Mockito.`when`(response.body()).thenReturn(body)
        return response
    }
}
