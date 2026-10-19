package com.trading.bot.model.entity

import java.math.BigDecimal
import java.time.LocalDate

/**
 * Дневная запись ряда ставок Банка России (P2 c1 плана 18_10_2026, research).
 *
 * Одна строка = одна календарная дата: ключевая ставка ЦБ ([keyRate]) и/или
 * ставка межбанковского рынка [ruonia] (% годовых). Поля nullable, потому что
 * RUONIA за дату D публикуется на D+1, а ключевая ставка меняется редко —
 * при повторной донакачке частичные строки дозаполняются (merge).
 *
 * Источник — официальный SOAP-сервис ЦБ DailyInfoWebServ (методы KeyRate/Ruonia).
 * В торговом контуре не участвует.
 */
data class CbrRateRecord(
    val rateDate: LocalDate,
    val keyRate: BigDecimal? = null,
    val ruonia: BigDecimal? = null,
    val source: String = "CBR",
)
