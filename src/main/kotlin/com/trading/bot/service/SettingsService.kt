package com.trading.bot.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.TradingConfig
import com.trading.bot.model.BotSettings
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service

@Service
class SettingsService(
    private val tradingConfig: TradingConfig,
    private val riskConfig: RiskConfig,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val key = "bot:settings"

    fun getSettings(): BotSettings {
        val json = redisTemplate.opsForValue().get(key)
        return if (json != null) {
            objectMapper.readValue(json, BotSettings::class.java)
        } else {
            BotSettings(
                botIntervalMs = tradingConfig.botIntervalMs,
                strategyIntervalMs = tradingConfig.strategyIntervalMs,
                monitorIntervalMs = tradingConfig.monitorIntervalMs,
                maxOpenPositionsForNewEntry = tradingConfig.maxOpenPositionsForNewEntry,
                tradingMode = tradingConfig.mode,
                maxPositionRub = riskConfig.maxPositionRub,
                maxDailyLossRub = riskConfig.maxDailyLossRub,
                stopLossPercent = riskConfig.defaultStopLossPercent,
                takeProfitPercent = riskConfig.defaultTakeProfitPercent,
                trailingStopEnabled = riskConfig.trailingStopEnabled,
                trailingStopPercent = riskConfig.trailingStopPercent
            )
        }
    }

    fun updateSettings(settings: BotSettings) {
        redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(settings))
    }
}
