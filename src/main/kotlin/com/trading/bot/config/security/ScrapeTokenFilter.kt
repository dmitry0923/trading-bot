package com.trading.bot.config.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections
import java.util.Enumeration

/**
 * Разрешает Prometheus-скрейпинг `/actuator/prometheus` по отдельному
 * долгоживущему токену (METRICS_SCRAPE_TOKEN), не давая доступа к остальному API.
 *
 * - Путь `/actuator/prometheus` объявлен `permitAll` в SecurityConfig (см.
 *   [com.trading.bot.config.SecurityConfig]): этот фильтр сам является единственной проверкой доступа.
 * - При валидном токене `Authorization` маскируется в обёртке запроса, чтобы
 *   [org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter]
 *   не пытался распарсить scrape-токен как JWT (иначе — 500/401 независимо от токена).
 * - При отсутствующем/невалидном токене фильтр отвечает 401 и обрывает цепочку.
 */
class ScrapeTokenFilter(
    private val scrapeToken: String,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        scrapeToken.isBlank() || !request.requestURI.startsWith("/actuator/prometheus")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header == "Bearer $scrapeToken") {
            filterChain.doFilter(StripAuthHeaderRequest(request), response)
        } else {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = "application/json"
            response.writer.write("""{"error":"invalid_metrics_token"}""")
        }
    }

    private class StripAuthHeaderRequest(
        request: HttpServletRequest,
    ) : HttpServletRequestWrapper(request) {
        override fun getHeader(name: String): String? =
            if (name.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true)) null else super.getHeader(name)

        override fun getHeaders(name: String): Enumeration<String> =
            if (name.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true)) Collections.emptyEnumeration() else super.getHeaders(name)

        override fun getHeaderNames(): Enumeration<String> {
            val names = super.getHeaderNames()
            val filtered =
                names
                    .asSequence()
                    .filterNot { it.equals(HttpHeaders.AUTHORIZATION, ignoreCase = true) }
                    .toList()
            return Collections.enumeration(filtered)
        }
    }
}
