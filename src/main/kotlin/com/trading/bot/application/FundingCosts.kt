package com.trading.bot.application

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Модель фьючерсного funding (CNYRUBF) — количество клирингов, которые пережила
 * позиция, как база для вычитания funding из P&L сделки.
 *
 * MOEX публикует funding по валютному фьючерсу CNYRUBF как ежедневную величину
 * за контракт (с учётом лота 1000 CNY); начисление/списание происходит на
 * клиринге. Точное значение величины funding — конфигурационный параметр
 * [com.trading.bot.config.InstrumentsConfig.InstrumentSpec.fundingRubPerContractPerDay]
 * (provisional, сверяется по данным MOEX/выпискам). Здесь — только МОМЕНТЫ
 * начисления: количество клирингов, которые позиция пережила между открытием
 * и закрытием.
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
     * Количество клирингов, через которые позиция держалась: дни d, для которых
     * открытие было до клиринга дня d, а закрытие — после.
     *
     * @param openedAt момент открытия позиции (локальное время МСК)
     * @param closedAt момент закрытия позиции (локальное время МСК)
     * @return число начислений funding (0 для внутридневной позиции)
     */
    fun clearingsCrossed(
        openedAt: LocalDateTime,
        closedAt: LocalDateTime,
    ): Int {
        if (closedAt <= openedAt) return 0
        var count = 0
        var day = openedAt.toLocalDate()
        val last = closedAt.toLocalDate()
        while (!day.isAfter(last)) {
            if (day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY) {
                val clearing = day.atTime(CLEARING_TIME)
                if (openedAt.isBefore(clearing) && closedAt.isAfter(clearing)) count++
            }
            day = day.plusDays(1)
        }
        return count
    }
}
