package com.trading.bot.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * Транзакции для R2DBC-записей, которые обязаны быть атомарными.
 *
 * Первый потребитель — [com.trading.bot.service.InvestorService]: движение денег
 * по `investor_accounts` и проводка в `investor_transactions` должны применяться
 * вместе. Раньше «атомарность» держалась на try/catch-компенсации: исключение она
 * откатывала, но падение процесса между двумя записями оставляло баланс без
 * проводки (скан-10 P3).
 *
 * `ReactiveTransactionManager` — стандартный bean Spring Boot для R2DBC
 * (`R2dbcTransactionManagerAutoConfiguration`), поэтому здесь объявляется только
 * оператор.
 */
@Configuration
class TransactionConfig {
    @Bean
    fun transactionalOperator(transactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(transactionManager)
}
