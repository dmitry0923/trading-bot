package com.trading.bot.service

import com.trading.bot.model.entity.DeploymentApprovalRecord
import com.trading.bot.repository.DeploymentApprovalRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit-тесты per-ticker LIVE-одобрения ([DeploymentApprovalService]): fail-closed,
 * DB-first approve, персистентный REVOKED (без DELETE → нет воскрешения после
 * рестарта), fingerprint-сверка, NOT_READY при сбое persistence.
 */
class DeploymentApprovalServiceTest {
    private val repository = mock<DeploymentApprovalRepository>()
    private lateinit var service: DeploymentApprovalService

    @BeforeEach
    fun setUp() {
        runBlocking { whenever(repository.latest()).thenReturn(emptyList()) }
        service = DeploymentApprovalService(repository)
        service.init()
    }

    @Test
    fun `not ready denies everything even with an approved-looking ticket`() {
        val uninitialized = DeploymentApprovalService(repository)
        assertFalse(uninitialized.isReady())
        assertFalse(uninitialized.isLiveAllowed("GAZP", "fp"))
    }

    @Test
    fun `fail-closed unapproved ticker is not live allowed`() {
        assertTrue(service.isReady())
        assertFalse(service.isLiveAllowed("GAZP", "fp"))
    }

    @Test
    fun `approve then isLiveAllowed true with matching fingerprint`() =
        runBlocking {
            service.approve("GAZP", DeploymentApprovalService.LIVE_ALLOWED, 0.63, "fp")
            assertTrue(service.isLiveAllowed("GAZP", "fp"))
            verify(repository).save(any())
        }

    @Test
    fun `fingerprint mismatch or null denies after approve`() =
        runBlocking {
            service.approve("GAZP", DeploymentApprovalService.LIVE_ALLOWED, 0.63, "fp")
            assertTrue(service.isLiveAllowed("GAZP", "fp"))
            assertFalse(service.isLiveAllowed("GAZP", "other-fingerprint"))
            assertFalse(service.isLiveAllowed("GAZP", null))
        }

    @Test
    fun `approve with non-live status does not allow live`() =
        runBlocking {
            service.approve("GAZP", "MARGINAL", 0.63, "fp")
            assertFalse(service.isLiveAllowed("GAZP", "fp"))
        }

    @Test
    fun `db save fails during approve - approval not activated and error propagates`() {
        runBlocking { whenever(repository.save(any())).thenThrow(RuntimeException("db down")) }
        assertThrows(RuntimeException::class.java) {
            runBlocking { service.approve("GAZP", DeploymentApprovalService.LIVE_ALLOWED, 0.63, "fp") }
        }
        assertFalse(service.isLiveAllowed("GAZP", "fp"))
    }

    @Test
    fun `revoke persists REVOKED via upsert, not delete`() =
        runBlocking {
            service.approve("GAZP", DeploymentApprovalService.LIVE_ALLOWED, 0.63, "fp")
            assertTrue(service.isLiveAllowed("GAZP", "fp"))

            service.revoke("GAZP")
            assertFalse(service.isLiveAllowed("GAZP", "fp"))

            val captor = argumentCaptor<DeploymentApprovalRecord>()
            verify(repository, atLeastOnce()).save(captor.capture())
            assertTrue(captor.allValues.any { it.status == DeploymentApprovalService.REVOKED && it.ticker == "GAZP" })
        }

    @Test
    fun `restart with REVOKED in db cannot resurrect the approval`() =
        runBlocking {
            whenever(repository.latest()).thenReturn(
                listOf(DeploymentApprovalRecord("GAZP", DeploymentApprovalService.REVOKED, 0.63, "fp")),
            )
            val restarted = DeploymentApprovalService(repository)
            restarted.init()
            assertFalse(restarted.isLiveAllowed("GAZP", "fp"))
        }

    @Test
    fun `restart with LIVE_ALLOWED in db restores approval`() =
        runBlocking {
            whenever(repository.latest()).thenReturn(
                listOf(DeploymentApprovalRecord("GAZP", DeploymentApprovalService.LIVE_ALLOWED, 0.63, "fp")),
            )
            val restarted = DeploymentApprovalService(repository)
            restarted.init()
            assertTrue(restarted.isLiveAllowed("GAZP", "fp"))
        }

    @Test
    fun `revoke persistence failure enters global not-ready`() {
        runBlocking { whenever(repository.save(any())).thenThrow(RuntimeException("db down")) }
        runBlocking { service.revoke("GAZP") }
        assertFalse(service.isReady())
        assertFalse(service.isLiveAllowed("GAZP", "fp"))
    }
}
