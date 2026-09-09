package com.trading.bot.application

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Модель фьючерсного funding (CNYRUBF) — моменты начисления funding между
 * открытием и закрытием позиции, как база для вычитания funding из P&L сделки.
 *
 * MOEX публикует funding по валютному фьючерсу CNYRUBF как ежедневную величину
 * за контракт (с учётом лота 1000 CNY); начисление/списание происходит на
 * клиринге. Конкретное значение funding за КАЖДЫЙ клиринг — из снапшотов
 * [com.trading.bot.application.funding.FundingSnapshotService] (LIVE — MOEX,
 * SIM/backtest — конфигурационный параметр). Здесь — только МОМЕНТЫ начисления:
 * даты клирингов, которые позиция пережила между открытием и закрытием.
 *
 * Модель (консервативный оцен-подход к стоимости удержания):
 *  - клиринг/начисление funding — в 18:45 МСК (основной клиринг FORTS);
 *  - клиринг «засчитывается», если позиция была открыта на момент клиринга дня:
 *      openedAt < clearing(day) < closedAt;
 *  - внутридневная позиция (открыта и закрыта в один день до 18:45) funding не
 *    платит; позиция, закрытая ПОСЛЕ 18:45 в день открытия, — платит за день;
 *  - выходные клирингов не имеют (funding не начисляется); праздники не
 *    моделируются (приближение — завышение на праздничных днях минимально);
 *  - открытие/закрытие трактуются в часовом поясе МСК ([openedAt]/[closedAt]
 *    хранятся как локальное время сервера — производственный контур работает
 *    в московском времени).
 */
object FundingCosts {
    private val CLEARING_TIME: LocalTime = LocalTime.of(18, 45)

    /**
     * Даты клирингов, которые позиция пережила: дни d, для которых открытие было
     * до клиринга дня d, а закрытие — после. Упорядочены по возрастанию.
     *
     * @param openedAt момент открытия позиции (локальное время МСК)
     * @param closedAt момент закрытия позиции (локальное время МСК)
     * @return список дат начислений funding (пуст для внутридневной позиции)
     */
    fun clearingDates(
        openedAt: LocalDateTime,
        closedAt: LocalDateTime,
    ): List<LocalDate> {
        if (closedAt <= openedAt) return emptyList()
        val result = ArrayList<LocalDate>()
        var day = openedAt.toLocalDate()
        val last = closedAt.toLocalDate()
        while (!day.isAfter(last)) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) {
                val clearing = day.atTime(CLEARING_TIME)
                if (openedAt.isBefore(clearing) && closedAt.isAfter(clearing)) result.add(day)
            }
            day = day.plusDays(1)
        }
        return result
    }

    /**
     * Количество клирингов, через которые позиция держалась (0 для внутридневной
     * позиции). Эквивалент размеру [clearingDates].
     */
    fun clearingsCrossed(
        openedAt: LocalDateTime,
        closedAt: LocalDateTime,
    ): Int = clearingDates(openedAt, closedAt).size
}
