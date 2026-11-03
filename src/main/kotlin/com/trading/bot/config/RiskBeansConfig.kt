package com.trading.bot.config

import com.trading.bot.application.MoexHolidayCalendar
import com.trading.bot.domain.risk.FuturesStopResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/**
 * Регистрация чисто-доменных риск-бинов (без Spring-аннотаций в domain-слое):
 * [FuturesStopResolver] инжектится в FuturesEntryProfile. Праздничный календарь
 * клирингов [MoexHolidayCalendar] собирается из конфигурации [FundingConfig].
 */
@Configuration
class RiskBeansConfig {
    @Bean
    fun futuresStopResolver(): FuturesStopResolver = FuturesStopResolver()

    @Bean
    fun moexHolidayCalendar(fundingConfig: FundingConfig): MoexHolidayCalendar =
        MoexHolidayCalendar(extraHolidays = fundingConfig.holidayDates())

    /** Единые часы бизнес-времени (МСК) для health/расписаний research-контура. */
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Europe/Moscow"))
}
