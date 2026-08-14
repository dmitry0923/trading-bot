package com.trading.bot.application

import com.trading.bot.application.decision.FuturesEntryProfile
import com.trading.bot.application.risk.FuturesPositionSizer
import com.trading.bot.application.risk.FuturesRiskEngine
import com.trading.bot.backtest.BacktestEngine
import com.trading.bot.backtest.BacktestSignalGenerator
import com.trading.bot.config.InstrumentsConfig
import com.trading.bot.config.LeverageConfig
import com.trading.bot.config.RiskConfig
import com.trading.bot.config.toFuturesAtrStopPolicy
import com.trading.bot.domain.order.OrderParams
import com.trading.bot.domain.risk.EntryRequest
import com.trading.bot.domain.risk.FuturesStopResolver
import com.trading.bot.domain.risk.PositionSizeResult
import com.trading.bot.infrastructure.alor.AlorFuturesClient
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.StrategyAction
import com.trading.bot.model.entity.Candle
import com.trading.bot.repository.CandleRepository
import com.trading.bot.service.CandleCacheService
import com.trading.bot.service.TradingAccountService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify
import org.springframework.r2dbc.core.DatabaseClient
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Единый конвейер ATR-стопа: live (FuturesEntryProfile) и backtest (BacktestEngine)
 * обязаны решать дистанцию стопа в пунктах идентично через [FuturesStopResolver].
 * Один и тот же ATR и priceStep дают одинаковые пункты в обоих контурах —
 * regression-контракт против расхождения логики.
 */
class FuturesStopWiringTest {
    private val config = RiskConfig()
    private val atr = BigDecimal("0.20")

    @Test
    fun `live profile and backtest engine resolve identical stop points`() {
        // ATR 0.20 ₽ при priceStep 0.01 -> 20 пунктов × 2 = 40 (не дефолт 50, не кламп).
        val expected = FuturesStopResolver().resolve(atr, BigDecimal("0.01"), config.toFuturesAtrStopPolicy())
        assertEquals(40, expected)

        assertEquals(expected, liveStopPoints())
        assertEquals(expected, backtestStopPoints())
    }

    private fun liveStopPoints(): Int {
        val captured = IntArray(1) { -1 }
        val orderBuilder = Mockito.mock(OrderBuilder::class.java)
        Mockito
            .`when`(
                orderBuilder.buildFuturesOrderParams(
                    Mockito.anyString(),
                    anyDirection(),
                    anyBigDecimal(),
                    anyBigDecimal(),
                    anyPositionSizeResult(),
                    anyBigDecimal(),
                    Mockito.anyInt(),
                ),
            ).thenAnswer { inv ->
                captured[0] = inv.getArgument<Int>(6)
                OrderParams(direction = PositionDirection.LONG, quantity = 0)
            }
        val candleCache = Mockito.mock(CandleCacheService::class.java)
        Mockito
            .`when`(candleCache.calculateAtr("Si", "MINUTE_10", config.futuresAtrStopPeriod))
            .thenReturn(atr)
        val leverageConfig = Mockito.mock(LeverageConfig::class.java)
        Mockito.`when`(leverageConfig.effective()).thenReturn(BigDecimal("2.0"))
        val profile =
            FuturesEntryProfile(
                futuresRiskEngine = Mockito.mock(FuturesRiskEngine::class.java),
                futuresPositionSizer = Mockito.mock(FuturesPositionSizer::class.java),
                orderBuilder = orderBuilder,
                alorFuturesClient = Mockito.mock(AlorFuturesClient::class.java),
                riskConfig = config,
                leverageConfig = leverageConfig,
                instrumentsConfig = InstrumentsConfig(),
                meterRegistry = SimpleMeterRegistry(),
                tradingAccountService = Mockito.mock(TradingAccountService::class.java),
                candleCache = candleCache,
                futuresStopResolver = FuturesStopResolver(),
            )

        profile.buildOrderParams(
            ticker = "Si",
            direction = PositionDirection.LONG,
            entryPrice = BigDecimal("92000"),
            size =
                PositionSizeResult(
                    quantity = 1,
                    marginRequired = BigDecimal("1000"),
                    riskAmount = BigDecimal("500"),
                    liquidationPrice = null,
                    reason = null,
                ),
            request =
                EntryRequest(
                    ticker = "Si",
                    action = StrategyAction.BUY,
                    entryPrice = BigDecimal("92000"),
                    direction = PositionDirection.LONG,
                    portfolioMoney = BigDecimal("100000"),
                    currentGo = BigDecimal("1000"),
                    openPositions = emptyList(),
                    accountId = null,
                ),
        )
        return captured[0]
    }

    private fun backtestStopPoints(): Int {
        val sizer = Mockito.mock(com.trading.bot.domain.risk.PositionSizer::class.java)
        val recorded = IntArray(1) { -1 }
        Mockito
            .`when`(
                sizer.calculateContracts(
                    any(),
                    any(),
                    Mockito.anyInt(),
                    any(),
                    anyOrNull(),
                    anyOrNull(),
                ),
            ).thenAnswer { inv ->
                recorded[0] = inv.getArgument<Int>(2)
                PositionSizeResult(
                    quantity = 1,
                    marginRequired = BigDecimal("1000"),
                    riskAmount = BigDecimal("500"),
                    liquidationPrice = null,
                    reason = null,
                )
            }
        val engine =
            BacktestEngine(
                CandleRepository(Mockito.mock(DatabaseClient::class.java)),
                instrumentsConfig = InstrumentsConfig(),
                positionSizer = sizer,
                riskConfig = config,
                signalGenerator = DelayThenBuySignalGenerator(delayBars = 15),
            )
        val result =
            runBlocking { engine.simulate("Si", atrCandles(), minBarsForSignal = 15) }
        assertEquals(1, result.totalTrades, "delay-then-buy fixture must produce a single entry")
        verify(sizer, Mockito.atLeastOnce())
            .calculateContracts(any(), any(), Mockito.anyInt(), any(), anyOrNull(), anyOrNull())
        return recorded[0]
    }

    /** Свечи с постоянным TR = 0.20 ₽: ATR(14) = 0.20 -> стоп 40 пунктов. */
    private fun atrCandles(): List<Candle> =
        (0 until 30).map { i ->
            Candle(
                ticker = "Si",
                timeframe = "MINUTE_10",
                openPrice = BigDecimal("92000"),
                highPrice = BigDecimal("92000.20"),
                lowPrice = BigDecimal("92000"),
                closePrice = BigDecimal("92000.10"),
                volume = 1000L,
                time = LocalDateTime.now().plusMinutes(10L * i),
            )
        }

    private fun anyBigDecimal(): BigDecimal {
        Mockito.any(BigDecimal::class.java)
        return BigDecimal.ZERO
    }

    private fun anyDirection(): PositionDirection {
        Mockito.any(PositionDirection::class.java)
        return PositionDirection.LONG
    }

    private fun anyPositionSizeResult(): PositionSizeResult {
        Mockito.any(PositionSizeResult::class.java)
        return PositionSizeResult(
            quantity = 1,
            marginRequired = BigDecimal.ZERO,
            riskAmount = BigDecimal.ZERO,
            liquidationPrice = BigDecimal.ZERO,
            reason = null,
        )
    }

    private class DelayThenBuySignalGenerator(
        private val delayBars: Int,
    ) : BacktestSignalGenerator {
        override suspend fun signal(
            ticker: String,
            candles: List<Candle>,
            index: Int,
            minBars: Int,
            cycleId: String,
        ): StrategyAction = if (index + 1 >= delayBars) StrategyAction.BUY else StrategyAction.HOLD
    }
}
