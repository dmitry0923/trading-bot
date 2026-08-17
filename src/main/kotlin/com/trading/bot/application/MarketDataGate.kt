package com.trading.bot.application

import com.trading.bot.client.WebSocketManager
import com.trading.bot.client.WsStream
import com.trading.bot.config.TradingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Gate свежести рыночных данных (защита от торговли на «мёртвых» данных).
 *
 * Вход в позицию разрешён только если по тикеру есть СВЕЖИЙ источник цены:
 * 1. [WebSocketManager.lastQuoteReceivedAt] — последний ПРИНЯТЫЙ WS-тик не старше
 *    [TradingConfig.marketDataMaxAgeMs]; или
 * 2. QUOTES-поток разорван, но последний успешный REST-fallback-поллинг
 *    ([recordRestPollSuccess], вызывается из TradingBotService.pollMarketData)
 *    не старше того же порога.
 *
 * Если QUOTES подключён, но по тикеру ещё не было тиков (старт приложения / окно
 * сразу после реконнекта) — консервативно блокируем вход: торговать без свежего
 * тика нельзя. Это закрывает 45-секундное окно watchdog ([WebSocketManager.watchdog]),
 * в течение которого статус формально CONNECTED, а данные уже устарели.
 *
 * Дополнительно (Stage 2 microstructure):
 * - Если OBI (Order Book Imbalance) противоречит направлению входа силаьнее порога
 *   TradingConfig.obiEntryThreshold — вход блокируется (защита от Anti-trend entries).
 *   BUY заблокирован при obi < -threshold, SELL — при obi > threshold.
 * - Последние OBI-значения кэшируются из WS-тиков (внутри TradingBotService).
 *
 * Мониторинг ОТКРЫТЫХ позиций этим gate не блокируется: SL/TP-защита продолжает
 * работать на best-effort данных (WS + fallback REST).
 *
 * Метрики:
 * - market.data.age_ms{ticker} — возраст последнего принятого источника цены
 * - bot.entry.rejected{ticker, reason=STALE_DATA} — блокировка входа
 * - bot.entry.rejected{ticker, reason=OBI_OPPosing} — блокировка по OBI
 * - market.data.obi{ticker} — последний известный OBI
 */
@Component
class MarketDataGate(
    private val webSocketManager: WebSocketManager,
    private val tradingConfig: TradingConfig,
    private val meterRegistry: MeterRegistry,
) {
    private val logger = KotlinLogging.logger {}

    private val lastRestPollAt = ConcurrentHashMap<String, AtomicLong>()
    private val ageGauges = ConcurrentHashMap<String, AtomicLong>()
    private val lastObi = ConcurrentHashMap<String, AtomicLong>()
    private val spreadGauges = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Фиксирует успешный REST-поллинг цены по тикеру (fallback при разрыве WS).
     */
    fun recordRestPollSuccess(ticker: String) {
        lastRestPollAt.computeIfAbsent(ticker.uppercase()) { AtomicLong() }.set(System.currentTimeMillis())
    }

    /**
     * Обновляет последнее известное OBI-значение по тикеру (вызывается из
     * [TradingBotService] при получении WS-тика с bidSize/askSize).
     *
     * @param ticker тикер (регистр не важен)
     * @param obi значение OBI в диапазоне [-1.0, +1.0]
     */
    fun updateObi(ticker: String, obi: BigDecimal) {
        val scaled = obi.multiply(BigDecimal("10000")).toLong()
        lastObi.computeIfAbsent(ticker.uppercase()) { t ->
            val ref = AtomicLong(0)
            meterRegistry.gauge("market.data.obi", Tags.of("ticker", t), ref) { it.get().toDouble() / 10000.0 }
            ref
        }.set(scaled)
    }

    /**
     * Обновляет spread-метрику для тикера (вызывается из TradingBotService при получении снэпшота).
     * Spread считается как (ask - bid) / mid * 100 в процентах.
     */
    fun recordSpread(ticker: String, bid: BigDecimal, ask: BigDecimal) {
        if (bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO || bid >= ask) return
        val mid = bid.add(ask).divide(BigDecimal("2"), 8, java.math.RoundingMode.HALF_UP)
        val spreadPercent = ask.subtract(bid).divide(mid, 8, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
        val scaled = spreadPercent.multiply(BigDecimal("1000")).toLong()
        spreadGauges
            .computeIfAbsent(ticker.uppercase()) { t ->
                val ref = AtomicLong(0)
                meterRegistry.gauge("market.data.spread_percent", Tags.of("ticker", t), ref) { it.get().toDouble() / 1000.0 }
                ref
            }.set(scaled)
    }

    /**
     * Свежи ли данные по тикеру для разрешения НОВОГО входа.
     */
    fun isPriceDataFresh(ticker: String): Boolean {
        val key = ticker.uppercase()
        val maxAgeMs = tradingConfig.marketDataMaxAgeMs

        val wsAgeMs = webSocketManager.lastQuoteReceivedAt(key)?.let { Duration.between(it, Instant.now()).toMillis() }
        if (wsAgeMs != null && wsAgeMs <= maxAgeMs) {
            updateAgeGauge(key, wsAgeMs)
            return true
        }

        if (!webSocketManager.isConnected(WsStream.QUOTES)) {
            val restAgeMs =
                lastRestPollAt[key]
                    ?.get()
                    ?.takeIf { it > 0 }
                    ?.let { Instant.ofEpochMilli(it) }
                    ?.let { Duration.between(it, Instant.now()).toMillis() }
            if (restAgeMs != null && restAgeMs <= maxAgeMs) {
                updateAgeGauge(key, restAgeMs)
                return true
            }
            logger.warn {
                "Market data STALE for $key: wsAge=${wsAgeMs ?: "none"}ms, " +
                    "restAge=${restAgeMs ?: "none"}ms (max=$maxAgeMs ms) — new entries blocked"
            }
            updateAgeGauge(key, restAgeMs ?: wsAgeMs ?: Long.MAX_VALUE)
            return false
        }

        // QUOTES подключён, но тика по тикеру ещё не было (старт / реконнект) — блокируем.
        logger.warn {
            "Market data STALE for $key: QUOTES connected but no tick yet " +
                "(wsAge=${wsAgeMs ?: "none"}ms) — new entries blocked"
        }
        updateAgeGauge(key, wsAgeMs ?: Long.MAX_VALUE)
        return false
    }

    private fun updateAgeGauge(
        ticker: String,
        ageMs: Long,
    ) {
        ageGauges
            .computeIfAbsent(ticker) { t ->
                val ref = AtomicLong(-1)
                meterRegistry.gauge("market.data.age_ms", Tags.of("ticker", t), ref) { it.get().toDouble() }
                ref
            }.set(ageMs)
    }
}
