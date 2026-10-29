package com.trading.bot.config.secrets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
}
