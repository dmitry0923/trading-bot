package com.trading.bot.service

import com.trading.bot.config.MacroConfig
import com.trading.bot.repository.CbrRatesHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [MacroContextService.fetch] — сборка макро-контекста и живой курс USD/RUB с MOEX.
 *
 * Сеть не используется: `WebClient.create()` подменяется статическим моком
 * (inline mock maker), а цепочка `get().uri(..).retrieve().bodyToMono(..)` собирается
 * из моков Spring. Проверяются: успешный разбор `marketdata.data[0][1]`, деградация
 * на конфиг при пустом/нечисловом ответе и при ошибке транспорта, а также
 * публикация macro-метрик.
 */
class MacroContextServiceFetchTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-25T09:00:00Z"), ZoneId.of("Europe/Moscow"))
    private val meterRegistry = SimpleMeterRegistry()
    private lateinit var webClientStatic: MockedStatic<WebClient>

    private val responseSpec: WebClient.ResponseSpec = mock()
    private val headersSpec: WebClient.RequestHeadersSpec<*> = mock()
    private val uriSpec: WebClient.RequestHeadersUriSpec<*> = mock()
    private val webClient: WebClient = mock()

    @BeforeEach
    fun setUp() {
        webClientStatic = Mockito.mockStatic(WebClient::class.java)
        webClientStatic.`when`<WebClient> { WebClient.create() }.thenReturn(webClient)
        whenever(webClient.get()).thenReturn(uriSpec)
        whenever(uriSpec.uri(any<String>())).thenReturn(headersSpec)
        whenever(headersSpec.retrieve()).thenReturn(responseSpec)
    }

    @AfterEach
    fun tearDown() {
        webClientStatic.close()
    }

    private fun config(): MacroConfig =
        MacroConfig().apply {
            cbrRate = BigDecimal("16.0")
            brentPrice = BigDecimal("72.5")
            usdRub = BigDecimal("92.0")
            usdRubTicker = "USD000UTSTOM"
            liveRateEnabled = false
        }

    private fun service(
        config: MacroConfig = config(),
        repository: CbrRatesHistoryRepository? = null,
    ): MacroContextService = MacroContextService(config, ObjectMapper(), meterRegistry, repository, clock)

    private fun stubBody(body: String) {
        whenever(responseSpec.bodyToMono(String::class.java)).thenReturn(Mono.just(body))
    }

    private fun stubBodyFailure(error: RuntimeException) {
        whenever(responseSpec.bodyToMono(String::class.java)).thenReturn(Mono.error(error))
    }

    @Test
    fun `fetch uses live usd rub and publishes macro gauges`() {
        stubBody("""{"marketdata":{"columns":["SECID","LAST"],"data":[["USD000UTSTOM",95.25]]}}""")

        val context = runBlocking { service().fetch() }

        assertEquals(0, BigDecimal("95.25").compareTo(context.usdRub))
        assertEquals(0, BigDecimal("16.0").compareTo(context.cbrRate))
        assertEquals(0, BigDecimal("72.5").compareTo(context.brentPrice))
        assertEquals(95.25, meterRegistry.find("macro.usd_rub").gauge()?.value() ?: 0.0, 1e-9)
        assertEquals(16.0, meterRegistry.find("macro.cbr_rate").gauge()?.value() ?: 0.0, 1e-9)
        assertEquals(72.5, meterRegistry.find("macro.brent").gauge()?.value() ?: 0.0, 1e-9)
        assertEquals(
            1.0,
            meterRegistry.counter("macro.usd_rub.live", "status", "OK").count(),
        )
    }

    @Test
    fun `fetch falls back to config when marketdata is empty`() {
        stubBody("""{"marketdata":{"columns":["SECID","LAST"],"data":[]}}""")

        val context = runBlocking { service().fetch() }

        assertEquals(0, BigDecimal("92.0").compareTo(context.usdRub))
        assertNull(meterRegistry.find("macro.usd_rub.live").counter())
    }

    @Test
    fun `fetch falls back to config when last price is not numeric`() {
        stubBody("""{"marketdata":{"columns":["SECID","LAST"],"data":[["USD000UTSTOM","-"]]}}""")

        val context = runBlocking { service().fetch() }

        assertEquals(0, BigDecimal("92.0").compareTo(context.usdRub))
    }

    @Test
    fun `fetch falls back to config and counts transport error`() {
        stubBodyFailure(IllegalStateException("moex unavailable"))

        val context = runBlocking { service().fetch() }

        assertEquals(0, BigDecimal("92.0").compareTo(context.usdRub))
        assertEquals(
            1.0,
            meterRegistry.counter("macro.usd_rub.live", "status", "FALLBACK").count(),
        )
    }

    @Test
    fun `fetch uses live cbr rate from history when enabled`(): Unit =
        runBlocking {
            stubBody("""{"marketdata":{"data":[["USD000UTSTOM",93.10]]}}""")
            val repository = Mockito.mock(CbrRatesHistoryRepository::class.java)
            Mockito
                .`when`(repository.findLatestKeyRateRecord())
                .thenReturn(
                    com.trading.bot.model.entity.CbrRateRecord(
                        rateDate = java.time.LocalDate.of(2026, 10, 23),
                        keyRate = BigDecimal("14.0"),
                    ),
                )
            val enabled = config().apply { liveRateEnabled = true }

            val context = service(enabled, repository).fetch()

            assertEquals(0, BigDecimal("14.0").compareTo(context.cbrRate))
            assertEquals(0, BigDecimal("93.10").compareTo(context.usdRub))
        }

    @Test
    fun `fetch degrades to config when cbr history read fails`(): Unit =
        runBlocking {
            stubBody("""{"marketdata":{"data":[["USD000UTSTOM",93.10]]}}""")
            val repository = Mockito.mock(CbrRatesHistoryRepository::class.java)
            Mockito
                .`when`(repository.findLatestKeyRateRecord())
                .thenThrow(IllegalStateException("db down"))
            val enabled = config().apply { liveRateEnabled = true }

            val context = service(enabled, repository).fetch()

            assertEquals(0, BigDecimal("16.0").compareTo(context.cbrRate))
        }

    /**
     * Независимость гейджа от предыдущего значения: повторный `fetch()` с другой
     * ценой должен отдавать новое значение (MutableGauges), а не замороженное первое.
     */
    @Test
    fun `repeated fetch updates macro gauges`() {
        stubBody("""{"marketdata":{"data":[["USD000UTSTOM",95.25]]}}""")
        val service = service()
        runBlocking { service.fetch() }

        stubBody("""{"marketdata":{"data":[["USD000UTSTOM",96.75]]}}""")
        runBlocking { service.fetch() }

        assertEquals(96.75, meterRegistry.find("macro.usd_rub").gauge()?.value() ?: 0.0, 1e-9)
    }
}
