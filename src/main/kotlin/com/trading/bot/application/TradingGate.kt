package com.trading.bot.application

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.TradingConfig
import com.trading.bot.event.TradingHaltedEvent
import com.trading.bot.model.entity.TradingHaltRecord
import com.trading.bot.service.AdaptiveRiskService
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.DrawdownProtectionService
import com.trading.bot.service.RiskManagementService
import com.trading.bot.service.SettingsService
import com.trading.bot.service.TradingHaltService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Единая "точка отключения" торговли для акций и фьючерсов.
 *
 * Возвращает [TradingStatus] с причиной блокировки — вместо голого Boolean:
 * - вручную выключенный флаг (tradingEnabled);
 * - последняя глобальная остановка [TradingHaltedEvent] (DAILY_LOSS_LIMIT /
 *   LEVERAGE_DISABLED / STATE_DESYNC), персистится в trading_halt;
 * - Multi-Tier drawdown protection ([DrawdownProtectionService.isEntryBlocked]);
 * - торговые часы ([TradingHoursGuard]);
 * - per-ticker блокировки: устаревшие данные, ATR%-волатильность, адаптивная пауза.
 *
 * [isTradingEnabled] — быстрая синхронная проверка глобальных блоков (без БД и
 * без свечей): используется на горячем пути входов. [getStatus] — полная картина
 * с per-ticker диагностикой для логов/API/UI.
 */
@Component
class TradingGate(
    private val settingsService: SettingsService,
    private val tradingConfig: TradingConfig,
    private val tradingHoursGuard: TradingHoursGuard,
    private val drawdownProtection: DrawdownProtectionService,
    private val riskManagement: RiskManagementService,
    private val adaptiveRisk: AdaptiveRiskService,
    private val candleCache: CandleCacheService,
    private val marketDataGate: MarketDataGate,
    private val tradingHaltService: TradingHaltService,
    private val webSocketManager: WebSocketManager,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Разрешены ли новые входы прямо сейчас (только глобальные блоки, без БД).
     */
    fun isTradingEnabled(): Boolean = globalBlocks().isEmpty()

    /**
     * Полный статус торговли: глобальные блоки (по приоритету) + per-ticker блоки.
     */
    suspend fun getStatus(): TradingStatus {
        val blocks =
            buildList {
                addAll(globalBlocks())
                addAll(perTickerBlocks())
            }
        return TradingStatus(blocks = blocks)
    }

    /**
     * Глобальные блоки в порядке критичности:
     * ручное выключение → персистентный halt → drawdown protection → торговые часы.
     */
    private fun globalBlocks(): List<TradingBlock> =
        buildList {
            val settings = settingsService.getSettings()
            if (!settings.tradingEnabled) {
                add(
                    TradingBlock(
                        reason = TradingBlockReason.MANUAL_DISABLE,
                        source = TradingBlockSource.MANUAL,
                        detail = "tradingEnabled flag disabled via UI/API",
                    ),
                )
            }

            tradingHaltService.last()?.toBlock()?.let { add(it) }

            if (drawdownProtection.isEntryBlocked()) {
                add(
                    TradingBlock(
                        reason = TradingBlockReason.DRAWDOWN_PROTECTION,
                        source = TradingBlockSource.RISK_SYSTEM,
                        detail = drawdownProtection.entryBlockReason(),
                    ),
                )
            }

            if (!tradingHoursGuard.isTradingAllowed()) {
                add(
                    TradingBlock(
                        reason = TradingBlockReason.OUTSIDE_HOURS,
                        source = TradingBlockSource.TRADING_HOURS,
                        detail = "outside trading window ${settings.tradingHoursStart}–${settings.tradingHoursEnd} MSK",
                    ),
                )
            }

            if (!webSocketManager.isConnected(WsStream.QUOTES) && !webSocketManager.isConnected(WsStream.ORDERS)) {
                add(
                    TradingBlock(
                        reason = TradingBlockReason.WS_DISCONNECTED,
                        source = TradingBlockSource.MARKET_DATA,
                        detail = "WebSocket disconnected — waiting for reconnect",
                    ),
                )
            }
        }

    /**
     * Per-ticker блокировки (вход запрещён только по конкретному тикеру).
     */
    private suspend fun perTickerBlocks(): List<TradingBlock> {
        val blocks = mutableListOf<TradingBlock>()
        for (ticker in tradingConfig.tickers) {
            if (!marketDataGate.isPriceDataFresh(ticker)) {
                blocks +=
                    TradingBlock(
                        reason = TradingBlockReason.STALE_DATA,
                        source = TradingBlockSource.MARKET_DATA,
                        detail = "no fresh quote source (WS/REST) for entry",
                        ticker = ticker,
                    )
            }

            val atr = candleCache.calculateAtr(ticker, "MINUTE_10", 14)
            val price = candleCache.getRecentCandles(ticker, "MINUTE_10", 1).lastOrNull()?.closePrice
            if (price != null && riskManagement.isVolatilityTooHigh(atr, price)) {
                blocks +=
                    TradingBlock(
                        reason = TradingBlockReason.VOLATILITY,
                        source = TradingBlockSource.RISK_SYSTEM,
                        detail = "ATR% above maxVolatilityPercent",
                        ticker = ticker,
                    )
            }

            if (adaptiveRisk.shouldPauseTrading(ticker)) {
                blocks +=
                    TradingBlock(
                        reason = TradingBlockReason.TICKER_PAUSED,
                        source = TradingBlockSource.ADAPTIVE_RISK,
                        detail = "adaptive pause: losing streak / low profit factor",
                        ticker = ticker,
                    )
            }
        }
        return blocks
    }

    /**
     * TradingHaltedEvent → персистируем последнюю остановку (reason/source/timestamp).
     */
    @EventListener
    fun onTradingHalted(event: TradingHaltedEvent) {
        logger.warn { "Trading gate: halt event received reason=${event.reason}" }
        runBlocking {
            tradingHaltService.record(
                reason = event.reason,
                source = sourceOf(event.reason),
                haltedAt = event.timestamp,
            )
        }
    }

    /**
     * Маппинг сохранённого halt в доменный блок (неизвестные reason'ы пропускаем).
     */
    private fun TradingHaltRecord.toBlock(): TradingBlock? {
        val reason =
            when (reason) {
                "DAILY_LOSS_LIMIT" -> TradingBlockReason.DAILY_LOSS_LIMIT
                "LEVERAGE_DISABLED" -> TradingBlockReason.LEVERAGE_DISABLED
                "STATE_DESYNC" -> TradingBlockReason.STATE_DESYNC
                "EMERGENCY_STOP" -> TradingBlockReason.EMERGENCY_STOP
                "SL_PROTECTION_FAILED" -> TradingBlockReason.SL_PROTECTION_FAILED
                "MANUAL", "MANUAL_DISABLE" -> TradingBlockReason.MANUAL_DISABLE
                else -> return null
            }
        val source =
            when (reason) {
                TradingBlockReason.MANUAL_DISABLE -> TradingBlockSource.MANUAL
                TradingBlockReason.STATE_DESYNC -> TradingBlockSource.STATE_RECONCILIATION
                TradingBlockReason.EMERGENCY_STOP -> emergencySource(this.source)
                else -> TradingBlockSource.RISK_SYSTEM
            }
        return TradingBlock(
            reason = reason,
            source = source,
            detail = detail,
            timestamp = haltedAt,
        )
    }

    private fun emergencySource(recordedSource: String): TradingBlockSource =
        if (recordedSource == "AUTO") TradingBlockSource.RISK_SYSTEM else TradingBlockSource.MANUAL

    private fun sourceOf(reason: String): String =
        when (reason) {
            "MANUAL", "MANUAL_DISABLE" -> TradingBlockSource.MANUAL.name
            "EMERGENCY_STOP" -> TradingBlockSource.MANUAL.name
            "STATE_DESYNC" -> TradingBlockSource.STATE_RECONCILIATION.name
            else -> TradingBlockSource.RISK_SYSTEM.name
        }
}
