package com.trading.bot.infrastructure.tracing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC

/**
 * Покрытие TraceContext (roadmap 13.18, infra P2): MDC-ключи trace_id/cycle_id/ticker/agent,
 * удаление пустых значений, наследование через MDCContext в дочерних корутинах и
 * восстановление окружения после withMdc.
 */
class TraceContextTest {
    @AfterEach
    fun clearMdc() {
        MDC.clear()
    }

    @Test
    fun `put stores value and blank or null removes it`() {
        TraceContext.put(TraceContext.TRACE_ID, "t1")
        assertEquals("t1", TraceContext.traceId())

        TraceContext.put(TraceContext.TRACE_ID, "")
        assertNull(TraceContext.traceId())

        TraceContext.put(TraceContext.TRACE_ID, "t2")
        TraceContext.put(TraceContext.TRACE_ID, null)
        assertNull(TraceContext.traceId())
    }

    @Test
    fun `currentMdc returns a copy of the current context`() {
        TraceContext.put(TraceContext.TRACE_ID, "t1")
        TraceContext.put(TraceContext.CYCLE_ID, "c1")

        val mdc = TraceContext.currentMdc()

        assertEquals("t1", mdc[TraceContext.TRACE_ID])
        assertEquals("c1", mdc[TraceContext.CYCLE_ID])
        mdc.toMutableMap().clear()
        assertEquals("t1", TraceContext.traceId(), "мутация копии не должна влиять на MDC")
    }

    @Test
    fun `mdcContext merges current mdc with extra overriding same keys`() {
        TraceContext.put(TraceContext.TRACE_ID, "root")
        TraceContext.put(TraceContext.TICKER, "SBER")

        val ctx = TraceContext.mdcContext(mapOf(TraceContext.AGENT to "strategy", TraceContext.TICKER to "GAZP"))

        assertEquals("root", ctx.contextMap!![TraceContext.TRACE_ID])
        assertEquals("GAZP", ctx.contextMap!![TraceContext.TICKER], "extra перекрывает текущий MDC")
        assertEquals("strategy", ctx.contextMap!![TraceContext.AGENT])
    }

    @Test
    fun `mdc context propagates to child coroutine on another dispatcher`() {
        val seen =
            runBlocking(
                TraceContext.mdcContext(
                    mapOf(
                        TraceContext.TRACE_ID to "t1",
                        TraceContext.CYCLE_ID to "c1",
                        TraceContext.TICKER to "SBER",
                        TraceContext.AGENT to "strategy",
                    ),
                ),
            ) {
                async(Dispatchers.IO) { TraceContext.currentMdc() }.await()
            }

        assertEquals("t1", seen[TraceContext.TRACE_ID])
        assertEquals("c1", seen[TraceContext.CYCLE_ID])
        assertEquals("SBER", seen[TraceContext.TICKER])
        assertEquals("strategy", seen[TraceContext.AGENT])
    }

    @Test
    fun `withMdc exposes extra inside block and restores previous mdc after`() {
        TraceContext.put(TraceContext.TRACE_ID, "parent")
        TraceContext.put(TraceContext.TICKER, "SBER")

        runBlocking {
            TraceContext.withMdc(mapOf(TraceContext.AGENT to "strategy")) {
                assertEquals("parent", TraceContext.traceId(), "родительский trace_id наследуется")
                assertEquals("SBER", MDC.get(TraceContext.TICKER))
                assertEquals("strategy", MDC.get(TraceContext.AGENT))
            }
        }

        assertEquals("parent", TraceContext.traceId(), "после withMdc окружение восстановлено")
        assertNull(MDC.get(TraceContext.AGENT), "добавленный ключ не остаётся в MDC")
    }

    @Test
    fun `withMdc extra overrides parent value inside block only`() {
        TraceContext.put(TraceContext.TRACE_ID, "parent")

        runBlocking {
            TraceContext.withMdc(mapOf(TraceContext.TRACE_ID to "child")) {
                assertEquals("child", TraceContext.traceId())
            }
        }

        assertEquals("parent", TraceContext.traceId())
    }

    @Test
    fun `mdc keys are the expected constants`() {
        assertEquals("trace_id", TraceContext.TRACE_ID)
        assertEquals("cycle_id", TraceContext.CYCLE_ID)
        assertEquals("ticker", TraceContext.TICKER)
        assertEquals("agent", TraceContext.AGENT)
    }
}
