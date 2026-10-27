package com.trading.bot.service

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit-тесты [EntryLeaseRecoveryGate]: блокировка ENTRY после потери lease
 * снимается только по фактически сверенным скоупам (скан-12 P1).
 *
 * Раньше «чистый» проход реконсиляции по акциям вызывал `recoverAll()` и снимал
 * блокировку вообще со всех скоупов — включая фьючерсный вход, состояние которого
 * никто не сверял, то есть вход снова разрешался вслепую.
 */
class EntryLeaseRecoveryGateTest {
    private val meterRegistry = SimpleMeterRegistry()
    private val gate = EntryLeaseRecoveryGate(meterRegistry)

    @Test
    fun `mark blocks only the affected scope`() {
        gate.mark("account:7", "entry-account-lease-lost")

        assertTrue(gate.isDegraded("account:7"))
        assertFalse(gate.isDegraded("account:8"))
        assertFalse(gate.isDegraded("ticker:Si"))
    }

    @Test
    fun `recover clears only the listed scopes`() {
        gate.mark("account:7", "entry-account-lease-lost")
        gate.mark("ticker:Si", "entry-ticker-lease-lost")

        // Проход по акциям сверяет только свой скоуп — фьючерсный тикер остаётся заблокированным.
        assertTrue(gate.recover(listOf("account:7")))

        assertFalse(gate.isDegraded("account:7"))
        assertTrue(gate.isDegraded("ticker:Si"), "непроверенный скоуп обязан остаться заблокированным")
    }

    @Test
    fun `recover with no verified scopes keeps the gate closed`() {
        gate.mark("account:7", "entry-account-lease-lost")

        // Пустой проход (нет позиций) ничего не подтверждает: блокировка остаётся.
        assertFalse(gate.recover(emptyList()))
        assertTrue(gate.isDegraded("account:7"))
    }

    @Test
    fun `recoverAll is reserved for the authoritative full reconciliation`() {
        gate.mark("account:7", "entry-account-lease-lost")
        gate.mark("ticker:Si", "entry-ticker-lease-lost")

        gate.recoverAll()

        assertTrue(gate.degradedScopes().isEmpty())
        assertEquals(1.0, meterRegistry.counter("entry.lease.recovery_completed").count())
    }
}
