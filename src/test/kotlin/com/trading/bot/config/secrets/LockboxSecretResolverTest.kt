package com.trading.bot.config.secrets

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.util.Base64

/**
 * Unit-тесты [LockboxSecretResolver] и [SecretsEnvironmentPostProcessor] без облака:
 * обязательность `secret-id`, обёртка ошибки загрузки, ранний выход при
 * `lockbox.enabled=false`, разрешение placeholder-ов и криптографические хелперы
 * (PKCS8-ключ → SHA256withRSA → base64url), вызываемые рефлексией.
 */
class LockboxSecretResolverTest {
    private val postProcessor = SecretsEnvironmentPostProcessor()

    @Test
    fun `environment post processor skips everything when disabled`() {
        val environment = MockEnvironment().withProperty("lockbox.enabled", "false")

        postProcessor.postProcessEnvironment(environment, SpringApplication())

        assertFalse(environment.propertySources.contains("lockbox-secrets"))
    }

    @Test
    fun `environment post processor fails fast and resolves enabled placeholder`() {
        val environment =
            MockEnvironment()
                .withProperty("lockbox.enabled", "\${FEATURE_LOCKBOX}")
                .withProperty("FEATURE_LOCKBOX", "true")

        val error =
            assertThrows(IllegalStateException::class.java) {
                postProcessor.postProcessEnvironment(environment, SpringApplication())
            }

        assertTrue(error.message!!.contains("Failed to load secrets"))
        assertTrue(error.cause is IllegalArgumentException)
        assertTrue(error.cause!!.message!!.contains("lockbox.secret-id"))
    }

    @Test
    fun `resolve requires non blank secret id before any network call`() {
        val resolver = LockboxSecretResolver(secretId = " ", iamToken = "iat", saKeyJson = "")

        val error = assertThrows(IllegalArgumentException::class.java) { resolver.resolve() }

        assertTrue(error.message!!.contains("lockbox.secret-id"))
    }

    @Test
    fun `pkcs8 key parsing and rsa signature helpers are consistent`() {
        val resolver = LockboxSecretResolver(secretId = "s", iamToken = "iat", saKeyJson = "")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded) +
                "\n-----END PRIVATE KEY-----"

        val parsed = invokePrivate("parsePrivateKey", listOf(String::class.java), resolver, pem) as PrivateKey
        assertEquals(keyPair.private.algorithm, parsed.algorithm)

        val signature =
            invokePrivate(
                "signRsa256",
                listOf(String::class.java, PrivateKey::class.java),
                resolver,
                "header.payload",
                parsed,
            ) as String
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(keyPair.public) }
        verifier.update("header.payload".toByteArray())
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(signature)))

        val encoded = invokePrivate("base64url", listOf(String::class.java), resolver, "jwt") as String
        assertEquals("jwt", String(Base64.getUrlDecoder().decode(encoded)))
    }

    private fun invokePrivate(
        name: String,
        parameterTypes: List<Class<*>>,
        target: LockboxSecretResolver,
        vararg args: Any?,
    ): Any? =
        LockboxSecretResolver::class.java
            .getDeclaredMethod(name, *parameterTypes.toTypedArray())
            .apply { isAccessible = true }
            .invoke(target, *args)
}
