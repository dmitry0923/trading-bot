package com.trading.bot.infrastructure.news

import com.trading.bot.model.dto.NewsItem

/**
 * Источник данных по эмитентам для [com.trading.bot.agent.FundamentalAnalysisAgent].
 * Реализации: [RgRuNewsProvider] (rg.ru, платная подписка).
 *
 * Fail-closed: реализация должна возвращать пустой список при любом сбое —
 * агент в неопределённости выдаёт NEUTRAL-базу без новостей.
 */
interface IssuerDataProvider {
    /**
     * Новости эмитента за последние [hours] часов.
     * Максимальное количество элементов ограничивает реализация (`news.max-items`).
     */
    suspend fun newsFor(
        ticker: String,
        hours: Int,
    ): List<NewsItem>
}
