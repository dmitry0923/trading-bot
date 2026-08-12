package com.trading.bot.service

import com.trading.bot.config.MacroConfig
import com.trading.bot.repository.MacroSnapshotRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import java.math.BigDecimal

class MacroSnapshotCollectorTest {
    private val config = MacroConfig()
    private val macroContextService = Mockito.mock(MacroContextService::class.java)
    private val macroSnapshotRepository = Mockito.mock(MacroSnapshotRepository::class.java)
    private val meterRegistry = SimpleMeterRegistry()

    private val collector =
        MacroSnapshotCollector(
            config,
            macroContextService,
            macroSnapshotRepository,
            meterRegistry,
        )

    @BeforeEach
    fun reset() {
        Mockito.reset(macroContextService, macroSnapshotRepository)
    }

    @Test
    fun `collect persists snapshot with fetched macro values`() {
        config.snapshotEnabled = true
        runBlocking {
            Mockito
                .`when`(macroContextService.fetch())
                .thenReturn(
                    MacroContextService.MacroContext(
                        cbrRate = BigDecimal("16.0"),
                        brentPrice = BigDecimal("75.5"),
                        usdRub = BigDecimal("92.3"),
                    ),
                )
        }

        runBlocking { collector.collect() }

        runBlocking {
            Mockito.verify(macroContextService).fetch()
            Mockito
                .verify(macroSnapshotRepository)
                .save(
                    argThat {
                        cbrRate.compareTo(BigDecimal("16.0")) == 0 &&
                            brentPrice.compareTo(BigDecimal("75.5")) == 0 &&
                            usdRub.compareTo(BigDecimal("92.3")) == 0 &&
                            capturedAt != null
                    },
                )
        }
        assertEquals(1.0, meterRegistry.get("macro.snapshot.saved").counter().count(), 1e-9)
    }

    @Test
    fun `collect does nothing when snapshots disabled`() {
        config.snapshotEnabled = false

        runBlocking { collector.collect() }

        runBlocking {
            Mockito.verify(macroContextService, Mockito.never()).fetch()
            Mockito.verify(macroSnapshotRepository, Mockito.never()).save(any())
        }
    }

    @Test
    fun `collect on failure increments error counter and does not save`() {
        config.snapshotEnabled = true
        runBlocking {
            Mockito
                .`when`(macroContextService.fetch())
                .thenThrow(RuntimeException("network down"))
        }

        runBlocking { collector.collect() }

        runBlocking {
            Mockito.verify(macroSnapshotRepository, Mockito.never()).save(any())
        }
        assertEquals(1.0, meterRegistry.get("macro.snapshot.collect.error").counter().count(), 1e-9)
    }
}
