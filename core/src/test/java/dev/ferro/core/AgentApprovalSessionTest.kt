package dev.ferro.core

import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.GrantToolApproval
import dev.ferro.contracts.PauseTurn
import dev.ferro.contracts.ResumeTurn
import dev.ferro.contracts.ShutdownSession
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.TurnId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentApprovalSessionTest {
    @Test
    fun `exact approval moves session through waiting phase and resumes same turn`() = runTest {
        val fixture = Fixture()
        val request = fixture.request()
        val executor = TurnExecutor { _, _, _, _ ->
            when (fixture.approvals.request(request)) {
                ToolApprovalResolution.Granted -> TurnOutcome.Completed("approved")
                else -> TurnOutcome.Failed("NOT_APPROVED", "Action was not approved")
            }
        }
        val io = fixture.session(executor).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Approve action"))
        runCurrent()

        assertEquals(AgentSessionPhase.WAITING_FOR_APPROVAL, io.snapshot.value.phase)
        assertEquals(request, io.snapshot.value.pendingToolApproval)
        io.submit(GrantToolApproval(request.requestId, request.binding))
        advanceUntilIdle()

        assertEquals(AgentSessionPhase.IDLE, io.snapshot.value.phase)
        assertEquals(TurnOutcome.Completed("approved"), io.snapshot.value.lastOutcome)
        assertNull(io.snapshot.value.pendingToolApproval)
        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `stale approval response cannot release the active waiter`() = runTest {
        val fixture = Fixture()
        val request = fixture.request()
        val executor = TurnExecutor { _, _, _, _ ->
            fixture.approvals.request(request)
            TurnOutcome.Completed("resolved")
        }
        val io = fixture.session(executor).startIn(this)
        io.submit(StartTurn(fixture.turnId, "Approve action"))
        runCurrent()

        io.submit(
            GrantToolApproval(
                request.requestId,
                request.binding.copy(uiStateFingerprint = "stale"),
            ),
        )
        runCurrent()

        assertEquals(AgentSessionPhase.WAITING_FOR_APPROVAL, io.snapshot.value.phase)
        io.submit(GrantToolApproval(request.requestId, request.binding))
        advanceUntilIdle()
        assertEquals(TurnOutcome.Completed("resolved"), io.snapshot.value.lastOutcome)
        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `pause expires approval then pauses at next checkpoint without executing authority`() = runTest {
        val fixture = Fixture()
        val request = fixture.request()
        var resolution: ToolApprovalResolution? = null
        var directive: TurnDirective? = null
        val executor = TurnExecutor { _, _, _, coordinator ->
            resolution = fixture.approvals.request(request)
            directive = coordinator.checkpoint(TurnCheckpoint.BEFORE_MODEL)
            TurnOutcome.Completed("resumed")
        }
        val io = fixture.session(executor).startIn(this)
        io.submit(StartTurn(fixture.turnId, "Approve action"))
        runCurrent()

        io.submit(PauseTurn(fixture.turnId))
        runCurrent()

        assertEquals(
            ToolApprovalResolution.Expired(ToolApprovalExpiryReason.PAUSED),
            resolution,
        )
        assertEquals(AgentSessionPhase.PAUSED, io.snapshot.value.phase)
        io.submit(ResumeTurn(fixture.turnId))
        advanceUntilIdle()
        assertEquals(TurnDirective.Recapture(RecaptureReason.RESUMED), directive)
        assertEquals(TurnOutcome.Completed("resumed"), io.snapshot.value.lastOutcome)
        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    private class Fixture {
        val ids = SequentialIdGenerator()
        val store = InMemoryAgentEventStore(ids, IncrementingClock())
        val approvals = ToolApprovalBroker(store) { 1_000L }
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")

        fun session(executor: TurnExecutor) = AgentSession(
            threadId,
            executor,
            store,
            UserInteractionBroker(store),
            approvals,
        )

        fun request(): ToolApprovalRequest {
            val binding = ApprovalBinding(
                threadId = threadId,
                turnId = turnId,
                toolCallId = ToolCallId("call"),
                canonicalArgumentsHash = "arguments",
                observationId = "observation",
                actionablePackage = "com.example.target",
                uiStateFingerprint = "fingerprint",
                capabilityScopeHash = "scope",
                risk = ToolRisk.HIGH,
                expiresAtEpochMs = 10_000,
            )
            return ToolApprovalRequest(
                ApprovalRequestId("approval"),
                binding,
                "type_text",
                "Enter text",
                "Text entry requires approval",
            )
        }
    }
}
