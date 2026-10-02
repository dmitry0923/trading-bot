package com.trading.bot.service

import com.trading.bot.client.AlorTokenProvider
import com.trading.bot.client.QuoteTick
import com.trading.bot.config.AlorConfig
import com.trading.bot.config.MicrostructureConfig
import com.trading.bot.config.TradingConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

/**
 * Сбор L1-стакана Alor через REST-поллинг (`/md/v2/orderbooks/{exchange}/{ticker}`).
 *
 * ## Зачем он нужен рядом с WS
 * Боевая подписка [com.trading.bot.client.AlorWebSocketClient.subscribeToQuotes]
 * отдаёт котировки без объёмов в используемом формате, поэтому OBI/microprice по
 * ней не вычислимы. REST-эндпоинт стакана возвращает **цены и объёмы** и уже
 * проверен на живом токене, поэтому forward-пилот собирается им.
 *
 * Назначение то же — [MicrostructureRecorder]: наблюдательный слой, который не
 * читается торговыми решениями и включается только вручную
 * ([MicrostructureConfig.restPollingEnabled], по умолчанию выключено).
 *
 * ## Честность измерения
 * REST-поллинг дискретен (по умолчанию 1 Гц на тикер), поэтому он **не заменяет**
 * WS-L1 и не даёт той же частоты наблюдений. Документируется как ограничение
 * вывода: вперёд смотрящие признаки не вычисляются, отбор инструментов и
 * длительность наблюдения фиксируются в research-документе.
 *
 * Fail-soft: пустой/неполный стакан и ошибка запроса пропускаются (счётчик),
 * сбор не прерывается — отсутствие наблюдения лучше падения цикла.
 */
@Service
class MicrostructureRestPoller(
    private val alorConfig: AlorConfig,
    private val microstructureConfig: MicrostructureConfig,
    private val tradingConfig: TradingConfig,
    private val tokenProvider: AlorTokenProvider,
    private val recorder: MicrostructureRecorder,
    private val meterRegistry: MeterRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val logger = KotlinLogging.logger {}
    private val webClient = WebClient.create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val depth: Int = microstructureConfig.restDepth.coerceIn(1, 20)
    private val timeout: Duration = Duration.ofMillis(microstructureConfig.restTimeoutMs.coerceAtLeast(500L))

    /** Список инструментов сбора; пустой список отключает поллинг. */
    private val tickers: List<String> =
        tradingConfig.tickers
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @PostConstruct
    fun start() {
        if (!microstructureConfig.restPollingEnabled) return
        if (tickers.isEmpty()) {
            logger.warn { "microstructure REST polling enabled, but ticker list is empty" }
            return
        }
        if (job != null) return
        job =
            scope.launch {
                logger.info {
                    "microstructure REST polling started: tickers=$tickers depth=$depth interval=${microstructureConfig.restPollIntervalMs}ms"
                }
                while (isActive) {
                    tickers.forEach { pollOne(it) }
                    delay(microstructureConfig.restPollIntervalMs.coerceAtLeast(200L))
                }
            }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
    }

    /**
     * Один снимок L1 по тикеру. Неполный стакан (нет bid или ask) не пишется:
     * OBI/microprice без двух сторон бессмысленны.
     */
    suspend fun pollOne(ticker: String) {
        val raw: String =
            try {
                webClient
                    .get()
                    .uri("${alorConfig.apiUrl}/md/v2/orderbooks/${alorConfig.exchange}/$ticker?depth=$depth")
                    .header("Authorization", "Bearer ${tokenProvider.actualToken()}")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .timeout(timeout)
                    .awaitSingle()
            } catch (e: Exception) {
                meterRegistry.counter("microstructure.rest.poll", Tags.of("ticker", ticker, "status", "error")).increment()
                logger.debug(e) { "microstructure REST poll failed for $ticker" }
                return
            }

        val tick =
            try {
                parse(ticker, objectMapper.readTree(raw))
            } catch (e: Exception) {
                meterRegistry.counter("microstructure.rest.poll", Tags.of("ticker", ticker, "status", "invalid")).increment()
                logger.debug(e) { "microstructure REST payload is not parsable for $ticker" }
                null
            }

        if (tick == null) {
            meterRegistry.counter("microstructure.rest.bbo", Tags.of("ticker", ticker, "reason", "incomplete")).increment()
            return
        }
        recorder.record(tick)
        meterRegistry.counter("microstructure.rest.poll", Tags.of("ticker", ticker, "status", "ok")).increment()
    }

    /**
     * Разбор ответа `/md/v2/orderbooks`: `bids`/`asks` — массивы уровней,
     * берётся лучший уровень каждой стороны и его объём.
     */
    internal fun parse(
        ticker: String,
        node: JsonNode,
    ): QuoteTick? {
        if (node.path("existing").asBoolean(true).not() && node.path("bids").isEmpty && node.path("asks").isEmpty) return null
        val bid = bestSide(node.path("bids"), higherIsBetter = true) ?: return null
        val ask = bestSide(node.path("asks"), higherIsBetter = false) ?: return null
        val mid = bid.first.add(ask.first).divide(BigDecimal(2), PRICE_SCALE, RoundingMode.HALF_UP)
        val exchangeTime =
            node
                .path("ms_timestamp")
                .asLong(0L)
                .takeIf { it > 0L }
                ?.let { Instant.ofEpochMilli(it) }
        return QuoteTick(
            ticker = ticker,
            price = mid,
            bid = bid.first,
            ask = ask.first,
            bidSize = bid.second,
            askSize = ask.second,
            receivedAt = Instant.now(),
            exchangeTime = exchangeTime,
        )
    }

    /**
     * Лучший уровень стороны: (цена, объём).
     *
     * Направление принципиально: лучший bid — **максимальная** цена, лучший ask —
     * **минимальная**. Ошибка здесь невидима при `depth=1` (по уровню на сторону),
     * но при глубине >1 молча завышала бы спред и ломала microprice, поэтому
     * направление передаётся явно, а не выводится из знака.
     *
     * Объём 0 допускается — это реальный уровень, а не пропуск.
     */
    private fun bestSide(
        side: JsonNode,
        higherIsBetter: Boolean,
    ): Pair<BigDecimal, Long>? {
        if (!side.isArray || side.isEmpty) return null
        var bestPrice: BigDecimal? = null
        var bestVolume = 0L
        side.forEach { level ->
            val price = level.path("price").asString("").toBigDecimalOrNull() ?: return@forEach
            val better = bestPrice == null || if (higherIsBetter) price > bestPrice else price < bestPrice
            if (better) {
                bestPrice = price
                bestVolume = level.path("volume").asLong(0L)
            }
        }
        return bestPrice?.let { it to bestVolume }
    }

    private companion object {
        const val PRICE_SCALE = 8
    }
}
