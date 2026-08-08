package com.trading.bot.integration

import com.trading.bot.application.TradingBlockReason
import com.trading.bot.application.TradingGate
import com.trading.bot.client.AlorClient
import com.trading.bot.model.InstrumentType
import com.trading.bot.model.PositionDirection
import com.trading.bot.model.PositionStatus
import com.trading.bot.model.entity.BotSettings
import com.trading.bot.model.entity.Position
import com.trading.bot.repository.InvestorRepository
import com.trading.bot.repository.PositionRepository
import com.trading.bot.repository.SettingsRepository
import com.trading.bot.service.ClearingService
import com.trading.bot.service.InvestorService
import com.trading.bot.service.ProfitForecastService
import com.trading.bot.service.SettingsService
import com.trading.bot.service.TradingControlService
import com.trading.bot.service.TradingHaltService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * Интеграционный тест инвестиционных модулей против реальной Postgres:
 *  - InvestorService (создание, депозиты, вывод, статистика пула);
 *  - ProfitForecastService (прогноз на реальных закрытых сделках);
 *  - ClearingService (клиринг: доля + атрибутированный P&L + прогнозная компонента);
 *  - SettingsRepository/SettingsService (персистентные настройки);
 *  - TradingControlService (единый флаг торговли + принудительное закрытие).
 *
 * AlorClient замокан — детерминированные цены без обращения к брокеру.
 */
class InvestorClearingIntegrationTest : AbstractTestContainerTest() {
    @Autowired
    lateinit var investorService: InvestorService

    @Autowired
    lateinit var clearingService: ClearingService

    @Autowired
    lateinit var profitForecastService: ProfitForecastService

    @Autowired
    lateinit var settingsService: SettingsService

    @Autowired
    lateinit var settingsRepository: SettingsRepository

    @Autowired
    lateinit var tradingControlService: TradingControlService

    @Autowired
    lateinit var tradingGate: TradingGate

    @Autowired
    lateinit var investorRepository: InvestorRepository

    @Autowired
    lateinit var positionRepository: PositionRepository

    @Autowired
    lateinit var tradingHaltService: TradingHaltService

    @MockitoBean
    lateinit var alorClient: AlorClient

    @BeforeEach
    fun setup() {
        runBlocking {
            positionRepository.deleteAll()
            investorRepository.deleteAllData()
            tradingHaltService.clear()
            settingsService.updateSettings(BotSettings())
        }
        runBlocking {
            Mockito.`when`(alorClient.getLastPrice("SBER")).thenReturn(BigDecimal("300"))
            Mockito.`when`(alorClient.getLastPrice("GAZP")).thenReturn(BigDecimal("200"))
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-close-${System.nanoTime()}")
            Mockito
                .`when`(
                    alorClient.verifyOrder(
                        Mockito.anyString(),
                        Mockito.any(BigDecimal::class.java),
                    ),
                ).thenAnswer { inv -> AlorClient.OrderExecution("FILLED", 1000, inv.getArgument<BigDecimal>(1)) }
        }
    }

    @AfterEach
    fun cleanup() {
        runBlocking {
            positionRepository.deleteAll()
            investorRepository.deleteAllData()
        }
    }

    @Test
    fun `investor lifecycle - create deposit withdraw and pool stats`() {
        val view = runBlocking { investorService.createInvestor("Иван Петров", "ivan@example.com", BigDecimal("100000")) }
        assertNotNull(view.investor.id)
        assertEquals(0, BigDecimal("100000").compareTo(view.account.balance))
        assertEquals(0, BigDecimal("100000").compareTo(view.account.totalDeposited))

        runBlocking { investorService.deposit(view.investor.id, BigDecimal("50000")) }
        runBlocking { investorService.withdraw(view.investor.id, BigDecimal("30000"), "Тестовый вывод") }

        val after = runBlocking { investorService.getInvestor(view.investor.id) }
        assertEquals(0, BigDecimal("120000").compareTo(after.account.balance))

        val contributed = runBlocking { investorService.poolContributed() }
        assertEquals(0, BigDecimal("150000").compareTo(contributed))
    }

    @Test
    fun `withdrawal above balance is rejected`() {
        val view = runBlocking { investorService.createInvestor("Алиса", null, BigDecimal("1000")) }
        val ex = runCatching { runBlocking { investorService.withdraw(view.investor.id, BigDecimal("5000")) } }
        assertTrue(ex.isFailure)
    }

    @Test
    fun `profit forecast reflects real closed trades`() {
        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("110"), daysAgo = 2)
        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("115"), daysAgo = 2)
        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("90"), daysAgo = 1)
        saveClosedPosition("GAZP", BigDecimal("200"), BigDecimal("230"), daysAgo = 1)
        saveClosedPosition("GAZP", BigDecimal("200"), BigDecimal("210"), daysAgo = 0)

        val forecast = runBlocking { profitForecastService.forecast(horizonDays = 30, capitalBase = BigDecimal("1000000")) }

        assertEquals(5, forecast.tradesAnalyzed)
        assertTrue(forecast.expectedReturnPercent > 0)
        assertTrue(forecast.expectedReturnAnnualPercent > 0)
        assertTrue(forecast.dailyMeanReturnPercent > 0)
        assertTrue(forecast.confidenceLowPercent < forecast.confidenceHighPercent)
    }

    @Test
    fun `profit forecast returns zeros when no closed trades`() {
        val forecast = runBlocking { profitForecastService.forecast(horizonDays = 30) }
        assertEquals(0, forecast.tradesAnalyzed)
        assertEquals(0.0, forecast.expectedReturnPercent)
        assertEquals(0.0, forecast.expectedReturnAnnualPercent)
    }

    @Test
    fun `clearing quote splits realized pnl proportionally to share`() {
        val first = runBlocking { investorService.createInvestor("Первый", null, BigDecimal("750000")) }
        runBlocking { investorService.createInvestor("Второй", null, BigDecimal("250000")) }

        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("110"))
        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("115"))

        val quote = runBlocking { clearingService.calculateWithdrawal(first.investor.id, LocalDateTime.now().plusDays(0)) }

        // доля 750000/1000000 = 0.75; атрибутированный P&L = 0.75 * реализованный
        assertEquals(0, BigDecimal("0.75").setScale(6).compareTo(quote.sharesAtTime))
        assertEquals(0, BigDecimal("1000000").compareTo(quote.poolContributed))
        assertTrue(quote.attributedPnL > BigDecimal.ZERO)
        assertTrue(quote.estimatedWithdrawalAmount > BigDecimal.ZERO)
        assertNotNull(quote.breakdown["balance"])
    }

    @Test
    fun `clearing settle writes CLEARING transaction and reduces balance`() {
        val view = runBlocking { investorService.createInvestor("Вкладчик", null, BigDecimal("1000000")) }
        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("110"))
        saveClosedPosition("SBER", BigDecimal("100"), BigDecimal("115"))

        val quote = runBlocking { clearingService.settleWithdrawal(view.investor.id) }
        assertTrue(quote.estimatedWithdrawalAmount > BigDecimal.ZERO)

        val after = runBlocking { investorService.getInvestor(view.investor.id) }
        val txns = runBlocking { investorService.transactions(view.investor.id) }
        assertTrue(after.account.balance < view.account.balance)
        assertTrue(txns.any { it.type == "CLEARING" })
        assertEquals(0, quote.estimatedWithdrawalAmount.compareTo(after.account.totalWithdrawn))
    }

    @Test
    fun `settings roundtrip persists to database`() {
        val custom =
            BotSettings(
                tradingEnabled = false,
                maxPositionRub = 123456,
                llmProvider = "DEEPSEEK",
                llmModel = "deepseek-chat",
                forceCloseEnabled = true,
                forceCloseTime = "18:00",
            )
        runBlocking { settingsRepository.saveSettings(custom) }

        val loaded = runBlocking { settingsRepository.loadSettings() }
        assertNotNull(loaded)
        assertEquals(custom.llmProvider, loaded!!.llmProvider)
        assertEquals(custom.llmModel, loaded.llmModel)
        assertEquals("18:00", loaded.forceCloseTime)
    }

    @Test
    fun `settings service update applies immediately in memory`() {
        settingsService.updateSettings(BotSettings().copy(tradingEnabled = false))
        assertFalse(tradingGate.isTradingEnabled())

        val loaded = runBlocking { settingsRepository.loadSettings() }
        assertFalse(loaded!!.tradingEnabled)
    }

    @Test
    fun `trading control toggles single flag`() {
        tradingControlService.setTradingEnabled(false)
        assertFalse(tradingControlService.isTradingEnabled())

        tradingControlService.setTradingEnabled(true)
        // Ручная блокировка снята — MANUAL_DISABLE не должен попадать в статус.
        val status = runBlocking { tradingGate.getStatus() }
        assertFalse(status.blocks.any { it.reason == TradingBlockReason.MANUAL_DISABLE })
    }

    @Test
    fun `force close closes all open stock positions`() {
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = BigDecimal("290"),
                    currentPrice = BigDecimal("300"),
                    status = PositionStatus.OPEN,
                    alorOrderId = "open-${System.nanoTime()}",
                ),
            )
            positionRepository.save(
                Position(
                    ticker = "GAZP",
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = BigDecimal("190"),
                    currentPrice = BigDecimal("200"),
                    status = PositionStatus.OPEN,
                    alorOrderId = "open-${System.nanoTime()}",
                ),
            )
        }

        val closed = runBlocking { tradingControlService.forceCloseNow("FORCE_CLOSE") }
        assertEquals(2, closed)
        assertEquals(0, runBlocking { positionRepository.findOpenCount() })
    }

    @Test
    fun `force close closes stocks and futures`() {
        runBlocking {
            Mockito
                .`when`(
                    alorClient.placeMarketOrder(
                        Mockito.anyString(),
                        Mockito.anyString(),
                        Mockito.anyInt(),
                        Mockito.anyString(),
                    ),
                ).thenReturn("ord-si-${System.nanoTime()}")
        }
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = "SBER",
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = BigDecimal("290"),
                    status = PositionStatus.OPEN,
                    alorOrderId = "open-${System.nanoTime()}",
                ),
            )
            positionRepository.save(
                Position(
                    ticker = "Si",
                    direction = PositionDirection.LONG,
                    quantity = 1,
                    entryPrice = BigDecimal("92000"),
                    instrumentType = InstrumentType.FUTURES,
                    status = PositionStatus.OPEN,
                    alorOrderId = "open-${System.nanoTime()}",
                ),
            )
        }

        val closed = runBlocking { tradingControlService.forceCloseNow("FORCE_CLOSE") }
        assertEquals(2, closed)
        assertEquals(0, runBlocking { positionRepository.findOpenCount() })
    }

    private fun saveClosedPosition(
        ticker: String,
        entry: BigDecimal,
        close: BigDecimal,
        daysAgo: Long = 1,
    ) {
        val pnl = close.subtract(entry).multiply(BigDecimal(10))
        runBlocking {
            positionRepository.save(
                Position(
                    ticker = ticker,
                    direction = PositionDirection.LONG,
                    quantity = 10,
                    entryPrice = entry,
                    currentPrice = close,
                    closePrice = close,
                    pnl = pnl,
                    status = PositionStatus.CLOSED,
                    alorOrderId = "test-${System.nanoTime()}",
                    closeReason = "TAKE_PROFIT",
                    openedAt = LocalDateTime.now().minusDays(daysAgo + 1),
                    closedAt = LocalDateTime.now().minusDays(daysAgo).minusHours(1),
                ),
            )
        }
    }
}
