package com.trading.bot.service

import com.trading.bot.repository.DeploymentApprovalRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/**
 * Unit-тесты per-ticker LIVE-одобрения ([DeploymentApprovalService]): fail-closed
 * (тикер без LIVE_ALLOWED заблокирован), approve/revoke обновляют in-memory кэш.
 */
class DeploymentApprovalServiceTest {
    private val repository = mock<DeploymentApprovalRepository>()
    private lateinit var service: DeploymentApprovalService

    @BeforeEach
    fun setUp() {
        service = DeploymentApprovalService(repository)
    }

    @Test
    fun `fail-closed unapproved ticker is not live allowed`() {
        assertFalse(service.isLiveAllowed("GAZP"))
    }

    @Test
    fun `approve then isLiveAllowed is true`() =
        runBlocking {
            service.approve("GAZP", "LIVE_ALLOWED", 0.63, "abc")
            assertTrue(service.isLiveAllowed("GAZP"))
            verify(repository).save(any())
        }

    @Test
    fun `approve with non-live status does not allow live`() =
        runBlocking {
            service.approve("GAZP", "MARGINAL", 0.63, "abc")
            assertFalse(service.isLiveAllowed("GAZP"))
        }

    @Test
    fun `revoke removes live approval`() =
        runBlocking {
            service.approve("GAZP", "LIVE_ALLOWED", 0.63, "abc")
            service.revoke("GAZP")
            assertFalse(service.isLiveAllowed("GAZP"))
            verify(repository).delete("GAZP")
        }
}
