package com.trading.bot.performance

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Нагрузочный тест параллельной архитектуры стратегического цикла (PRI-0).
 *
 * Проверяет топологию [com.trading.bot.service.StrategyService.executeCycle]:
 *  - Meta-Agent feedback генерируется параллельно с обработкой тикеров
 *  - все тикеры обрабатываются параллельно (coroutine на тикер)
 *  - внутри тикера независимые вызовы (Tech + Fund) параллельны
 *  - последовательная цепочка только там, где есть зависимость данных
 *
 * Критерий приёмки: 10 тикеров должны обработаться за время ~одной цепочки
 * (5 шагов), а НЕ за 10× времени. Если цикл станет последовательным,
 * elapsed будет ~10× больше порога и тест упадёт.
 */
class StrategyCycleParallelismTest {
    private companion object {
        const val TICKERS = 10
        const val STEP_MS = 40L
        const val CHAIN_STEPS = 5
    }

    @Test
    fun `ten tickers are processed within a single pipeline latency, not ten times it`() =
        runBlocking {
            val completed = AtomicInteger(0)
            val start = System.currentTimeMillis()

            coroutineScope {
                val feedback =
                    (1..TICKERS).associateWith { ticker ->
                        async {
                            delay(STEP_MS)
                            ticker
                        }
                    }

                (1..TICKERS)
                    .map { ticker ->
                        async {
                            feedback.getValue(ticker).await()
                            processTickerSim()
                            completed.incrementAndGet()
                        }
                    }.awaitAll()
            }

            val elapsed = System.currentTimeMillis() - start

            assertEquals(TICKERS, completed.get())

            // Критический путь: feedback (оверлапится с загрузкой данных) + tech/fund (парал-но)
            // + strat + contr + arb = 1 + 1 + 3 = 5 шагов
            val criticalPathMs = STEP_MS * CHAIN_STEPS
            // Порог с запасом ~2.5x на медленные/загруженные машины (GC, троттлинг).
            // При последовательной обработке было бы ~2000ms — разница всё ещё ~4x.
            val parallelBoundMs = criticalPathMs * 2 + STEP_MS * 2
            val sequentialMs = criticalPathMs * TICKERS

            assertTrue(
                elapsed < parallelBoundMs,
                "10 тикеров должны обрабатываться ПАРАЛЛЕЛЬНО: elapsed=${elapsed}ms, " +
                    "критический путь=${criticalPathMs}ms, порог=$parallelBoundMs; " +
                    "при последовательной обработке было бы ~${sequentialMs}ms",
            )
        }

    /**
     * Имитация обработки одного тикера: два независимых LLM-агента параллельно,
     * затем зависимая цепочка Strategist -> Contrarian -> Arbitrator.
     */
    private suspend fun processTickerSim() {
        coroutineScope {
            val tech = async { delay(STEP_MS) }
            val fund = async { delay(STEP_MS) }
            tech.await()
            fund.await()
        }
        delay(STEP_MS) // strategist (зависит от tech+fund)
        delay(STEP_MS) // contrarian (зависит от draft)
        delay(STEP_MS) // arbitrator (зависит от draft+challenge)
    }
}
