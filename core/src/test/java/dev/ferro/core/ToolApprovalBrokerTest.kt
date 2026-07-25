package dev.ferro.core

import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolApprovalDenied
import dev.ferro.contracts.ToolApprovalExpired
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolApprovalGranted
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.ToolApprovalRequested
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.TurnId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolApprovalBrokerTest {
    private val ids = SequentialIdGenerator()
    private val store = InMemoryAgentEventStore(ids, IncrementingClock())
    private var now = 1_000L
    private val broker = ToolApprovalBroker(store) { now }

    @Test
    fun `exact grant resolves one shot request and records causal events`() = runTest {
        val request = request()
        val waiting = async { broker.request(request) }
        runCurrent()

        assertEquals(request, broker.pending.value)
        assertTrue(broker.grant(request.requestId, request.binding))
        assertEquals(ToolApprovalResolution.Granted, waiting.await())
        assertNull(broker.pending.value)
        val payloads = payloads()
        assertTrue(payloads[0] is ToolApprovalRequested)
        assertTrue(payloads[1] is ToolApprovalGranted)
        assertFalse(broker.grant(request.requestId, request.binding))
    }

    @Test
    fun `stale binding cannot consume or authorize active request`() = runTest {
        val request = request()
        val waiting = async { broker.request(request) }
        runCurrent()
        val stale = request.binding.copy(uiStateFingerprint = "changed")

        assertFalse(broker.grant(request.requestId, stale))
        assertEquals(request, broker.pending.value)
        assertTrue(broker.deny(request.requestId, request.binding))
        assertEquals(ToolApprovalResolution.Denied, waiting.await())
        assertTrue(payloads().last() is ToolApprovalDenied)
    }

    @Test
    fun `pause expiry releases waiter without granting authority`() = runTest {
        val request = request()
        val waiting = async { broker.request(request) }
        runCurrent()

        assertTrue(broker.expireTurn(TurnId("turn"), ToolApprovalExpiryReason.PAUSED))

        assertEquals(
            ToolApprovalResolution.Expired(ToolApprovalExpiryReason.PAUSED),
            waiting.await(),
        )
        val expired = payloads().filterIsInstance<ToolApprovalExpired>().single()
        assertEquals(request.binding, expired.binding)
        assertFalse(broker.grant(request.requestId, request.binding))
    }

    @Test
    fun `deadline timeout expires pending authority`() = runTest {
        val request = request(expiresAt = 1_100L)
        val waiting = async { broker.request(request) }
        runCurrent()

        advanceTimeBy(101)
        runCurrent()

        assertEquals(
            ToolApprovalResolution.Expired(ToolApprovalExpiryReason.TIMEOUT),
            waiting.await(),
        )
        assertNull(broker.pending.value)
    }

    @Test
    fun `failed grant journal append keeps exact approval pending and retryable`() = runTest {
        val backing = InMemoryAgentEventStore(ids, IncrementingClock())
        val failingStore = FailNextAppendEventStore(backing)
        val durableBroker = ToolApprovalBroker(failingStore) { now }
        val request = request()
        val waiting = async { durableBroker.request(request) }
        runCurrent()

        failingStore.failNextAppend = true
        val failure = runCatching {
            durableBroker.grant(request.requestId, request.binding)
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(request, durableBroker.pending.value)
        assertFalse(waiting.isCompleted)
        assertTrue(durableBroker.grant(request.requestId, request.binding))
        assertEquals(ToolApprovalResolution.Granted, waiting.await())
    }

    @Test
    fun `failed request journal append removes non durable pending projection`() = runTest {
        val failingStore = FailNextAppendEventStore(store).apply { failNextAppend = true }
        val durableBroker = ToolApprovalBroker(failingStore) { now }

        val failure = runCatching { durableBroker.request(request()) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertNull(durableBroker.pending.value)
    }

    private suspend fun payloads() = store.readThread(ThreadId("thread")).map { it.payload }

    private fun request(expiresAt: Long = 2_000L): ToolApprovalRequest {
        val binding = ApprovalBinding(
            threadId = ThreadId("thread"),
            turnId = TurnId("turn"),
            toolCallId = ToolCallId("call"),
            canonicalArgumentsHash = "arguments",
            observationId = "observation",
            actionablePackage = "com.example.target",
            uiStateFingerprint = "fingerprint",
            capabilityScopeHash = "scope",
            risk = ToolRisk.HIGH,
            expiresAtEpochMs = expiresAt,
        )
        return ToolApprovalRequest(
            requestId = ApprovalRequestId("approval"),
            binding = binding,
            toolName = "type_text",
            actionSummary = "Enter text",
            reason = "Text entry can make a consequential change",
        )
    }
}

private class FailNextAppendEventStore(
    private val delegate: AgentEventStore,
) : AgentEventStore {
    var failNextAppend: Boolean = false

    override suspend fun append(
        threadId: ThreadId,
        turnId: TurnId?,
        payload: AgentEventPayload,
    ): AgentEventEnvelope {
        if (failNextAppend) {
            failNextAppend = false
            error("injected append failure")
        }
        return delegate.append(threadId, turnId, payload)
    }

    override suspend fun readThread(threadId: ThreadId): List<AgentEventEnvelope> =
        delegate.readThread(threadId)

    override fun observeThread(threadId: ThreadId): Flow<List<AgentEventEnvelope>> =
        delegate.observeThread(threadId)
}
