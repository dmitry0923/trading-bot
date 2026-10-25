package com.trading.bot.config.security

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Гейт доступа к `/actuator/prometheus`.
 *
 * Ключевой регресс: при **пустом** `METRICS_SCRAPE_TOKEN` фильтр раньше выключал
 * сам себя (`shouldNotFilter = token.isBlank() || ...`), а `SecurityConfig`
 * объявляет путь `permitAll` — метрики становились публичными. Теперь пустой
 * токен означает 401 (fail-closed), а не «открыто».
 */
class ScrapeTokenFilterTest {
    @Test
    fun `blank scrape token fails closed instead of exposing metrics`() {
        val filter = ScrapeTokenFilter("")
        val request = prometheusRequest()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertTrue(response.contentAsString.contains("metrics_scrape_token_not_configured"))
        assertNull(chain.request, "controller chain must not run without a token")
    }

    @Test
    fun `valid token passes and strips authorization header`() {
        val filter = ScrapeTokenFilter("secret")
        val request = prometheusRequest("Bearer secret")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        val seen = chain.request as jakarta.servlet.http.HttpServletRequest
        assertNull(seen.getHeader(HttpHeaders.AUTHORIZATION))
    }

    @Test
    fun `wrong token is rejected`() {
        val filter = ScrapeTokenFilter("secret")
        val request = prometheusRequest("Bearer wrong")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(401, response.status)
        assertNull(chain.request)
    }

    @Test
    fun `other paths are not filtered`() {
        val filter = ScrapeTokenFilter("")
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        assertEquals(200, response.status)
        assertNotNull(chain.request)
    }

    private fun prometheusRequest(authorization: String? = null): MockHttpServletRequest {
        val request = MockHttpServletRequest("GET", "/actuator/prometheus")
        if (authorization != null) request.addHeader(HttpHeaders.AUTHORIZATION, authorization)
        return request
    }
}
