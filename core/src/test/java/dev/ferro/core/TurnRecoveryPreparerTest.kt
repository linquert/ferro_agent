package dev.ferro.core

import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.IterationId
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolApprovalExpired
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.ToolApprovalRequested
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnCancelled
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnRecoveryResumed
import dev.ferro.contracts.TurnStarted
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnRecoveryPreparerTest {
    @Test
    fun `restart settles unmatched call once and pauses without executing it`() = runTest {
        val fixture = Fixture()
        fixture.start()
        val orphan = ToolCall(fixture.ids.toolCallId(), "tap", JsonObject(emptyMap()))
        fixture.store.append(
            fixture.threadId,
            fixture.turnId,
            ToolCallRecorded(IterationId("model-iteration"), orphan),
        )
        val approval = ToolApprovalRequest(
            ApprovalRequestId("approval-orphan"),
            ApprovalBinding(
                fixture.threadId,
                fixture.turnId,
                orphan.id,
                "arguments",
                "observation",
                "com.example.target",
                "fingerprint",
                "scope",
                ToolRisk.HIGH,
                10_000,
            ),
            "tap",
            "Tap a control",
            "Tap requires approval",
        )
        fixture.store.append(fixture.threadId, fixture.turnId, ToolApprovalRequested(approval))

        assertTrue(fixture.preparer.pauseAfterProcessRestart(fixture.threadId, fixture.turnId))
        assertTrue(fixture.preparer.pauseAfterProcessRestart(fixture.threadId, fixture.turnId))

        val payloads = fixture.payloads()
        val result = payloads.filterIsInstance<ToolResultRecorded>().single().result
        assertEquals(orphan.id, result.callId)
        assertEquals(ToolResultStatus.CANCELLED, result.status)
        assertEquals(0, fixture.executedCalls.size)
        assertEquals(1, payloads.filterIsInstance<TurnRecoveryPaused>().size)
        val expired = payloads.filterIsInstance<ToolApprovalExpired>().single()
        assertEquals(ToolApprovalExpiryReason.PROCESS_RESTART, expired.reason)
    }

    @Test
    fun `explicit resume captures real fresh observation and records recovery lifecycle`() = runTest {
        val fixture = Fixture()
        fixture.start()
        fixture.preparer.pauseAfterProcessRestart(fixture.threadId, fixture.turnId)

        val result = fixture.preparer.prepareExplicitResume(fixture.threadId, fixture.turnId)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals(listOf("observe_screen"), fixture.executedCalls.map(ToolCall::name))
        val payloads = fixture.payloads()
        assertEquals(
            ToolCallOrigin.RUNTIME_RECOVERY,
            payloads.filterIsInstance<ToolCallRecorded>().single().origin,
        )
        assertEquals("observation-fresh", payloads.filterIsInstance<TurnRecoveryResumed>().single().observationId)
    }

    @Test
    fun `failed fresh observation keeps recovery paused and never emits resumed`() = runTest {
        val fixture = Fixture(observationStatus = ToolResultStatus.RECOVERABLE_FAILURE)
        fixture.start()
        fixture.preparer.pauseAfterProcessRestart(fixture.threadId, fixture.turnId)

        val failure = runCatching {
            fixture.preparer.prepareExplicitResume(fixture.threadId, fixture.turnId)
        }.exceptionOrNull()

        assertTrue(failure is RecoveryPreparationException)
        assertTrue(fixture.payloads().none { it is TurnRecoveryResumed })
        assertEquals(
            ToolResultStatus.RECOVERABLE_FAILURE,
            fixture.payloads().filterIsInstance<ToolResultRecorded>().last().result.status,
        )
    }

    @Test
    fun `terminal turn cannot be recovered and abandon is idempotent`() = runTest {
        val fixture = Fixture()
        fixture.start()
        fixture.store.append(fixture.threadId, fixture.turnId, TurnCompleted("done"))

        assertFalse(fixture.preparer.pauseAfterProcessRestart(fixture.threadId, fixture.turnId))
        fixture.preparer.abandon(fixture.threadId, fixture.turnId, "discarded")

        assertTrue(fixture.payloads().none { it is TurnRecoveryPaused || it is TurnCancelled })
    }

    @Test
    fun `runtime recovery observation stays out of canonical conversation history`() = runTest {
        val fixture = Fixture()
        fixture.start()
        fixture.preparer.pauseAfterProcessRestart(fixture.threadId, fixture.turnId)
        fixture.preparer.prepareExplicitResume(fixture.threadId, fixture.turnId)

        val history = EventReconstructedConversationHistory.rebuild(
            fixture.store.readThread(fixture.threadId),
        )

        assertEquals(1, history.size)
        assertEquals(0, history.filterIsInstance<dev.ferro.contracts.ModelToolCallInput>().size)
        assertEquals(0, history.filterIsInstance<dev.ferro.contracts.ModelToolResultInput>().size)
    }

    private class Fixture(
        private val observationStatus: ToolResultStatus = ToolResultStatus.SUCCESS,
    ) {
        val ids = SequentialIdGenerator()
        val store = InMemoryAgentEventStore(ids, IncrementingClock())
        val threadId = ThreadId("thread-recovery")
        val turnId = TurnId("turn-recovery")
        val executedCalls = mutableListOf<ToolCall>()
        private val router = ToolRouter(
            ToolRegistry(
                listOf(
                    object : ToolHandler {
                        override val spec = ModelToolSpec(
                            name = "observe_screen",
                            description = "test observer",
                            inputSchema = buildJsonObject { put("type", "object") },
                        )

                        override suspend fun execute(
                            context: ToolExecutionContext,
                            call: ToolCall,
                        ): ToolResult {
                            executedCalls += call
                            return ToolResult(
                                callId = call.id,
                                status = observationStatus,
                                output = buildJsonObject {
                                    if (observationStatus == ToolResultStatus.SUCCESS) {
                                        put("observation_id", JsonPrimitive("observation-fresh"))
                                    }
                                },
                                message = if (observationStatus == ToolResultStatus.SUCCESS) {
                                    "captured"
                                } else {
                                    "capture unavailable"
                                },
                            )
                        }
                    },
                ),
            ),
        )
        val preparer = TurnRecoveryPreparer(store, router, ids)

        suspend fun start() {
            store.append(threadId, turnId, TurnStarted("Recover this task"))
        }

        suspend fun payloads(): List<AgentEventPayload> =
            store.readThread(threadId).map { it.payload }
    }
}
