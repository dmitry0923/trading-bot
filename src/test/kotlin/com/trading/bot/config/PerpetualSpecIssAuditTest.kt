package com.trading.bot.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.math.BigDecimal

/**
 * Аудит спек бессрочных фьючерсов против снимка MOEX ISS (2026-10-18).
 *
 * Причина появления: 18.10.2026 при расширении юниверса carry-портфеля
 * (P1 шага 2, `18_10_2026.md`) у `SBERF`/`GAZPF` обнаружен `lotSize = 1` при
 * реальном `LOTVOLUME = 100`, а у `GAZPF` — `priceStep = 0.001` при
 * `MINSTEP = 0.01`. Для портфельного движка (`notional = price × lotSize`) это
 * давало 100-кратно завышенную комиссию на контракт (она задана в ₽/контракт,
 * а не в bp), из-за чего результат U7 был артефактом спеки, а не сигнала.
 *
 * Тест фиксирует сверку в двух источниках: Kotlin-дефолты (`InstrumentsConfig`)
 * и runtime-источник (`application.yml`, реально биндится Spring'ом).
 */
class PerpetualSpecIssAuditTest {
    private data class IssSpec(
        val lotSize: Int,
        val priceStep: String,
        val priceStepCost: String,
    )

    /** Снимок MOEX ISS: LOTVOLUME / MINSTEP / STEPPRICE (futures/forts, 2026-10-18). */
    private val iss =
        mapOf(
            "CNYRUBF" to IssSpec(1000, "0.001", "1.0"),
            "USDRUBF" to IssSpec(1000, "0.01", "10.0"),
            "EURRUBF" to IssSpec(1000, "0.01", "10.0"),
            "GLDRUBF" to IssSpec(1, "0.1", "0.1"),
            "IMOEXF" to IssSpec(10, "0.5", "5.0"),
            "SBERF" to IssSpec(100, "0.01", "1.0"),
            "GAZPF" to IssSpec(100, "0.01", "1.0"),
        )

    @Test
    fun `kotlin defaults for perpetual futures match MOEX ISS snapshot`() {
        val config = InstrumentsConfig()
        iss.forEach { (ticker, expected) ->
            val spec = config.find(ticker)
            assertThat(spec).describedAs("$ticker присутствует в InstrumentsConfig").isNotNull
            val actual = spec!!
            val lotAssert = assertThat(actual.lotSize).describedAs("$ticker lotSize")
            lotAssert.isEqualTo(expected.lotSize)
            val stepAssert = assertThat(actual.priceStep).describedAs("$ticker priceStep")
            stepAssert.isEqualByComparingTo(BigDecimal(expected.priceStep))
            val stepCostAssert = assertThat(actual.priceStepCost).describedAs("$ticker priceStepCost")
            stepCostAssert.isEqualByComparingTo(BigDecimal(expected.priceStepCost))
        }
    }

    @Test
    fun `application yml perpetual specs match MOEX ISS snapshot`() {
        val file = File("src/main/resources/application.yml")
        assertThat(file).describedAs("application.yml найден от рабочей директории модуля").exists()
        val root = Yaml().load<Map<String, Any?>>(file.readText())
        val instrumentsBlock = root["instruments"] as Map<*, *>
        val list = instrumentsBlock["instruments"] as List<*>
        val specs = list.filterIsInstance<Map<*, *>>().associateBy { it["ticker"] as String }

        iss.forEach { (ticker, expected) ->
            val spec = specs[ticker]
            assertThat(spec).describedAs("$ticker в instruments.instruments (yml)").isNotNull
            val actual = spec!!
            val lotAssert = assertThat(actual["lotSize"]).describedAs("$ticker lotSize (yml)")
            lotAssert.isEqualTo(expected.lotSize)
            val step = BigDecimal(actual["priceStep"].toString())
            val stepAssert = assertThat(step).describedAs("$ticker priceStep (yml)")
            stepAssert.isEqualByComparingTo(BigDecimal(expected.priceStep))
            val stepCost = BigDecimal(actual["priceStepCost"].toString())
            val stepCostAssert = assertThat(stepCost).describedAs("$ticker priceStepCost (yml)")
            stepCostAssert.isEqualByComparingTo(BigDecimal(expected.priceStepCost))
        }
    }
}
