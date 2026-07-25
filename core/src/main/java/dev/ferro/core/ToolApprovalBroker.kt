package dev.ferro.core

import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.ToolApprovalDenied
import dev.ferro.contracts.ToolApprovalExpired
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolApprovalGranted
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.ToolApprovalRequested
import dev.ferro.contracts.TurnId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface ToolApprovalResolution {
    data object Granted : ToolApprovalResolution
    data object Denied : ToolApprovalResolution
    data class Expired(val reason: ToolApprovalExpiryReason) : ToolApprovalResolution
}

class ToolApprovalBroker(
    private val eventStore: AgentEventStore,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var active: ActiveToolApproval? = null
    private val mutablePending = MutableStateFlow<ToolApprovalRequest?>(null)
    val pending: StateFlow<ToolApprovalRequest?> = mutablePending.asStateFlow()

    fun newRequestId(): ApprovalRequestId = ApprovalRequestId("approval_${UUID.randomUUID()}")

    suspend fun request(request: ToolApprovalRequest): ToolApprovalResolution {
        val response = CompletableDeferred<ToolApprovalResolution>()
        mutex.withLock {
            check(active == null) { "Another tool approval is already pending" }
            check(request.binding.expiresAtEpochMs > nowEpochMs()) { "Tool approval is already expired" }
            active = ActiveToolApproval(request, response)
            mutablePending.value = request
        }
        try {
            eventStore.append(
                request.binding.threadId,
                request.binding.turnId,
                ToolApprovalRequested(request),
            )
        } catch (error: Throwable) {
            mutex.withLock {
                active?.takeIf { it.request == request }?.let(::clearLocked)
            }
            throw error
        }
        val remainingMs = (request.binding.expiresAtEpochMs - nowEpochMs()).coerceAtLeast(1)
        val resolution = withTimeoutOrNull(remainingMs) { response.await() }
        if (resolution != null) return resolution
        return expire(request.requestId, request.binding, ToolApprovalExpiryReason.TIMEOUT)
            ?: ToolApprovalResolution.Expired(ToolApprovalExpiryReason.TIMEOUT)
    }

    suspend fun grant(requestId: ApprovalRequestId, expectedBinding: ApprovalBinding): Boolean {
        return mutex.withLock {
            val approval = matchingLocked(requestId, expectedBinding) ?: return@withLock false
            if (expectedBinding.expiresAtEpochMs <= nowEpochMs()) {
                expireLocked(approval, ToolApprovalExpiryReason.TIMEOUT)
                return@withLock false
            }
            eventStore.append(
                expectedBinding.threadId,
                expectedBinding.turnId,
                ToolApprovalGranted(requestId, expectedBinding),
            )
            clearLocked(approval)
            approval.response.complete(ToolApprovalResolution.Granted)
            true
        }
    }

    suspend fun deny(requestId: ApprovalRequestId, expectedBinding: ApprovalBinding): Boolean {
        return mutex.withLock {
            val approval = matchingLocked(requestId, expectedBinding) ?: return@withLock false
            eventStore.append(
                expectedBinding.threadId,
                expectedBinding.turnId,
                ToolApprovalDenied(requestId, expectedBinding),
            )
            clearLocked(approval)
            approval.response.complete(ToolApprovalResolution.Denied)
            true
        }
    }

    suspend fun expireTurn(turnId: TurnId, reason: ToolApprovalExpiryReason): Boolean {
        return mutex.withLock {
            val approval = active?.takeIf { it.request.binding.turnId == turnId }
                ?: return@withLock false
            expireLocked(approval, reason)
            true
        }
    }

    suspend fun recordInvalidated(request: ToolApprovalRequest, reason: ToolApprovalExpiryReason) {
        eventStore.append(
            request.binding.threadId,
            request.binding.turnId,
            ToolApprovalExpired(request.requestId, request.binding, reason),
        )
    }

    private suspend fun expire(
        requestId: ApprovalRequestId,
        expectedBinding: ApprovalBinding,
        reason: ToolApprovalExpiryReason,
    ): ToolApprovalResolution? {
        return mutex.withLock {
            val approval = matchingLocked(requestId, expectedBinding) ?: return@withLock null
            expireLocked(approval, reason)
        }
    }

    private suspend fun expireLocked(
        approval: ActiveToolApproval,
        reason: ToolApprovalExpiryReason,
    ): ToolApprovalResolution.Expired {
        val request = approval.request
        eventStore.append(
            request.binding.threadId,
            request.binding.turnId,
            ToolApprovalExpired(request.requestId, request.binding, reason),
        )
        val resolution = ToolApprovalResolution.Expired(reason)
        clearLocked(approval)
        approval.response.complete(resolution)
        return resolution
    }

    private fun matchingLocked(
        requestId: ApprovalRequestId,
        expectedBinding: ApprovalBinding,
    ): ActiveToolApproval? = active?.takeIf {
        it.request.requestId == requestId && it.request.binding == expectedBinding
    }

    private fun clearLocked(approval: ActiveToolApproval) {
        check(active === approval)
        active = null
        mutablePending.value = null
    }
}

private data class ActiveToolApproval(
    val request: ToolApprovalRequest,
    val response: CompletableDeferred<ToolApprovalResolution>,
)
