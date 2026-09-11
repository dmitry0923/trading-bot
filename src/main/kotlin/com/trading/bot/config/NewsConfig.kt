package com.trading.bot.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/**
 * Конфигурация новостного провайдера эмитентов (prefix = "news").
 *
 * LIVE-источник — платная подписка на новости (например rg.ru API):
 * - [enabled] — мастер-флаг; при false агент работает без новостей (NEUTRAL-база);
 * - [baseUrl] — URL-шаблон подписки; плейсхолдеры `{ticker}` и `{hours}` заменяются
 *   запросом. Пустой → провайдер возвращает пустой список (новостей нет, не ошибка);
 * - [apiKey] — ключ подписки (заголовок Authorization); при пустом — пустой список;
 * - [maxItems] — число новостей, которые отдаются агенту (лимит токенов промпта);
 * - [timeoutMs] — таймаут HTTP-запроса;
 * - [ttlMinutes] — TTL кэша новостей в Redis (новины не дёргаются каждый цикл).
 */
@Component
@ConfigurationProperties(prefix = "news")
class NewsConfig {
    var enabled: Boolean = false
    var baseUrl: String = ""
    var apiKey: String = ""
    var maxItems: Int = 5
    var timeoutMs: Long = 5_000L
    var ttlMinutes: Long = 15L
}
