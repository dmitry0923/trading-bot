package com.trading.bot.application

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Праздничный календарь MOEX для моделей клиринга (P1: «праздники не
 * моделируются»).
 *
 * Модель нерабочих дней — консервативная, две группы:
 *  - выходные [DayOfWeek.SATURDAY]/[DayOfWeek.SUNDAY];
 *  - российские государственные праздники по фиксированным датам
 *    (новогодние каникулы 1–8 января, 23 февраля, 8 марта, 1 и 9 мая,
 *    12 июня, 4 ноября) — фиксированные дни месяца, без учёта переносов
 *    «суббота/воскресенье → ближайший будний», которые правительство
 *    утверждает ежегодно (переносы задаются конфигурацией
 *    [com.trading.bot.config.FundingConfig.holidays]).
 *
 * Российские праздничные дни совпадают с нерабочими днями MOEX: если
 * праздник выпадает на будний день, биржа не работает (клиринг не проводится);
 * если на выходной — день и так исключён. Переносы правительства (рабочая
 * суббота / дополнительные будние каникулы) НЕ выводятся автоматически —
 * их задают через [extraHolidays] по производственному календарю.
 */
class MoexHolidayCalendar(
    private val extraHolidays: Set<LocalDate> = emptySet(),
) {
    /**
     * Является ли день нерабочим (выходной или государственный праздник или
     * дата из [extraHolidays]).
     *
     * @param date проверяемая дата
     * @return true если клиринг в этот день не проводится
     */
    fun isNonTradingDay(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY ||
            date.dayOfWeek == DayOfWeek.SUNDAY ||
            isRussianPublicHoliday(date) ||
            date in extraHolidays

    /**
     * Является ли день торговым днём MOEX (в нём проводится клиринг).
     *
     * @param date проверяемая дата
     * @return true если клиринг в этот день проводится
     */
    fun isTradingDay(date: LocalDate): Boolean = !isNonTradingDay(date)

    companion object {
        /** Официальные праздничные дни России (фиксированные даты). */
        private val PUBLIC_HOLIDAYS: Set<Pair<Int, Int>> =
            setOf(
                1 to 1,
                1 to 2,
                1 to 3,
                1 to 4,
                1 to 5,
                1 to 6,
                1 to 7,
                1 to 8,
                2 to 23,
                3 to 8,
                5 to 1,
                5 to 9,
                6 to 12,
                11 to 4,
            )

        /**
         * Является ли дата российским государственным праздником (без учёта
         * ежегодных переносов).
         *
         * @param date проверяемая дата
         * @return true если дата — фиксированный государственный праздник
         */
        fun isRussianPublicHoliday(date: LocalDate): Boolean = (date.monthValue to date.dayOfMonth) in PUBLIC_HOLIDAYS
    }
}
