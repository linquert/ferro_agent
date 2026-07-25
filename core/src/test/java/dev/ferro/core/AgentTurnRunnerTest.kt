package dev.ferro.core

import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.AssistantReasoningRecorded
import dev.ferro.contracts.ModelResponseCompleted
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelResponse
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ModelUsage
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnCancelled
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnFailed
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.ThreadStarted
import dev.ferro.contracts.TurnStarted
import dev.ferro.contracts.emptyArguments
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTurnRunnerTest {
    @Test
    fun `reasoning chat and usage are durable but only chat is replayed into model context`() = runTest {
        val fixture = Fixture()
        val requests = mutableListOf<ModelRequest>()
        val provider = ModelProvider { request ->
            requests += request
            if (requests.size == 1) {
                ModelResponse(
                    providerResponseId = "response-1",
                    reasoning = "I should inspect first.",
                    message = "I will inspect the screen.",
                    toolCalls = listOf(
                        ToolCall(ToolCallId("observe-1"), "observe_screen", emptyArguments()),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                    usage = ModelUsage(100, 25, 125),
                )
            } else {
                ModelResponse(message = "Done", stopReason = ModelStopReason.COMPLETE)
            }
        }

        fixture.runner(provider).run(fixture.threadId, fixture.turnId, "Inspect")

        val payloads = fixture.payloads()
        val reasoning = payloads.filterIsInstance<AssistantReasoningRecorded>().single()
        val completed = payloads.filterIsInstance<ModelResponseCompleted>().first()
        assertEquals("I should inspect first.", reasoning.text)
        assertEquals("response-1", completed.providerResponseId)
        assertEquals(ModelUsage(100, 25, 125), completed.usage)
        val secondInput = requests[1].input.filterIsInstance<dev.ferro.contracts.ModelMessageInput>()
        assertTrue(secondInput.any { it.text == "I will inspect the screen." })
        assertFalse(secondInput.any { it.text.contains("I should inspect first") })
    }

    @Test
    fun `tool result is durably paired and included before the second model request`() = runTest {
        val fixture = Fixture()
        val requests = mutableListOf<ModelRequest>()
        val provider = ModelProvider { request ->
            requests += request
            if (requests.size == 1) {
                ModelResponse(
                    message = "I need to observe.",
                    toolCalls = listOf(
                        ToolCall(ToolCallId("observe-1"), "observe_screen", emptyArguments()),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            } else {
                ModelResponse(message = "Done", stopReason = ModelStopReason.COMPLETE)
            }
        }
        val runner = fixture.runner(provider)

        val outcome = runner.run(fixture.threadId, fixture.turnId, "Inspect the screen")
        val payloads = fixture.payloads()

        assertEquals(TurnOutcome.Completed("Done"), outcome)
        assertEquals(2, requests.size)
        val continuedInput = requests[1].input
        assertTrue(continuedInput.any { it is ModelToolCallInput && it.call.id == ToolCallId("observe-1") })
        assertTrue(continuedInput.any { it is ModelToolResultInput && it.result.callId == ToolCallId("observe-1") })
        val callIndex = payloads.indexOfFirst { it is ToolCallRecorded }
        val resultIndex = payloads.indexOfFirst { it is ToolResultRecorded }
        val completionIndex = payloads.indexOfFirst { it is TurnCompleted }
        assertTrue(callIndex in 0 until resultIndex)
        assertTrue(resultIndex in 0 until completionIndex)
        assertEquals(
            (payloads[callIndex] as ToolCallRecorded).call.id,
            (payloads[resultIndex] as ToolResultRecorded).result.callId,
        )
    }

    @Test
    fun `runtime bound call is the durable call authorized executed and replayed`() = runTest {
        val fixture = Fixture()
        val requests = mutableListOf<ModelRequest>()
        val provider = ModelProvider { request ->
            requests += request
            if (requests.size == 1) {
                ModelResponse(
                    toolCalls = listOf(
                        ToolCall(ToolCallId("observe-1"), "observe_screen", emptyArguments()),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            } else {
                ModelResponse(message = "Done", stopReason = ModelStopReason.COMPLETE)
            }
        }
        val binder = ToolCallBinder { call ->
            call.copy(arguments = buildJsonObject { put("runtime_binding", "screen-current") })
        }

        fixture.runner(provider, toolCallBinder = binder)
            .run(fixture.threadId, fixture.turnId, "Inspect")

        val recorded = fixture.payloads().filterIsInstance<ToolCallRecorded>().single().call
        val replayed = requests[1].input.filterIsInstance<ModelToolCallInput>().single().call
        assertEquals("\"screen-current\"", recorded.arguments["runtime_binding"].toString())
        assertEquals(recorded, replayed)
    }

    @Test
    fun `truncated response cannot execute tool calls`() = runTest {
        val fixture = Fixture()
        var executions = 0
        val provider = ModelProvider {
            ModelResponse(
                toolCalls = listOf(ToolCall(ToolCallId("unsafe"), "observe_screen", emptyArguments())),
                stopReason = ModelStopReason.OUTPUT_LIMIT,
            )
        }
        val runner = fixture.runner(provider) { executions++ }

        val outcome = runner.run(fixture.threadId, fixture.turnId, "Observe")

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(0, executions)
        assertFalse(fixture.payloads().any { it is ToolCallRecorded })
        assertEquals("TRUNCATED_TOOL_CALLS", (fixture.payloads().last() as TurnFailed).code)
    }

    @Test
    fun `repeated tool calls stop at explicit tool budget`() = runTest {
        val fixture = Fixture()
        var callNumber = 0
        val provider = ModelProvider {
            callNumber++
            ModelResponse(
                toolCalls = listOf(
                    ToolCall(ToolCallId("call-$callNumber"), "observe_screen", emptyArguments()),
                ),
                stopReason = ModelStopReason.TOOL_CALLS,
            )
        }
        val runner = fixture.runner(provider, budget = TurnBudget(maxIterations = 20, maxToolCalls = 2))

        val outcome = runner.run(fixture.threadId, fixture.turnId, "Loop")

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals("TOOL_BUDGET_EXCEEDED", (fixture.payloads().last() as TurnFailed).code)
    }

    @Test
    fun `three identical recoverable tool failures stop without spending the turn budget`() = runTest {
        val fixture = Fixture()
        var calls = 0
        val provider = ModelProvider {
            calls++
            ModelResponse(
                toolCalls = listOf(
                    ToolCall(ToolCallId("call-$calls"), "observe_screen", emptyArguments()),
                ),
                stopReason = ModelStopReason.TOOL_CALLS,
            )
        }
        val handler = object : ToolHandler {
            override val spec = ModelToolSpec("observe_screen", "Observe")
            override suspend fun execute(context: ToolExecutionContext, call: ToolCall) = ToolResult(
                callId = call.id,
                status = ToolResultStatus.RECOVERABLE_FAILURE,
                output = buildJsonObject { put("code", "SCREENSHOT_UNAVAILABLE") },
                message = "Screenshot unavailable",
            )
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))
        val runner = AgentTurnLoop(
            fixture.store,
            EventSourcedModelContextBuilder(fixture.store, router),
            provider,
            testAuthorizationGate(router, fixture.store),
            fixture.ids,
            TurnBudget(maxIterations = 12, maxToolCalls = 24, maxConsecutiveIdenticalFailures = 3),
        )

        val outcome = runner.run(fixture.threadId, fixture.turnId, "Observe")

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(3, calls)
        assertEquals("REPEATED_TOOL_FAILURE", (fixture.payloads().last() as TurnFailed).code)
    }

    @Test
    fun `fatal tool result ends turn before another model request`() = runTest {
        val fixture = Fixture()
        var requests = 0
        val provider = ModelProvider {
            requests++
            ModelResponse(
                toolCalls = listOf(ToolCall(ToolCallId("call"), "observe_screen", emptyArguments())),
                stopReason = ModelStopReason.TOOL_CALLS,
            )
        }
        val handler = object : ToolHandler {
            override val spec = ModelToolSpec("observe_screen", "Observe")
            override suspend fun execute(context: ToolExecutionContext, call: ToolCall) = ToolResult(
                callId = call.id,
                status = ToolResultStatus.FATAL_FAILURE,
                output = buildJsonObject { put("code", "DEVICE_CONTROL_UNAVAILABLE") },
                message = "Device control disconnected",
            )
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))
        val runner = AgentTurnLoop(
            fixture.store,
            EventSourcedModelContextBuilder(fixture.store, router),
            provider,
            testAuthorizationGate(router, fixture.store),
            fixture.ids,
        )

        val outcome = runner.run(fixture.threadId, fixture.turnId, "Observe")

        assertTrue(outcome is TurnOutcome.Failed)
        assertEquals(1, requests)
        assertEquals("DEVICE_CONTROL_UNAVAILABLE", (fixture.payloads().last() as TurnFailed).code)
    }

    @Test
    fun `duplicate call id in one model response executes only once and records paired failure`() = runTest {
        val fixture = Fixture()
        var executions = 0
        var samples = 0
        val duplicated = ToolCall(ToolCallId("same"), "observe_screen", emptyArguments())
        val provider = ModelProvider {
            samples++
            if (samples == 1) {
                ModelResponse(toolCalls = listOf(duplicated, duplicated), stopReason = ModelStopReason.TOOL_CALLS)
            } else {
                ModelResponse(message = "Recovered", stopReason = ModelStopReason.COMPLETE)
            }
        }

        val outcome = fixture.runner(provider) { executions++ }
            .run(fixture.threadId, fixture.turnId, "Observe once")

        assertEquals(TurnOutcome.Completed("Recovered"), outcome)
        assertEquals(1, executions)
        val results = fixture.payloads().filterIsInstance<ToolResultRecorded>()
        assertEquals(2, results.size)
        assertEquals(ToolResultStatus.SUCCESS, results[0].result.status)
        assertEquals(ToolResultStatus.RECOVERABLE_FAILURE, results[1].result.status)
        assertEquals("\"DUPLICATE_CALL_ID\"", results[1].result.output["code"].toString())
    }

    @Test
    fun `cancellation writes one durable terminal event`() = runTest {
        val fixture = Fixture()
        val runner = fixture.runner(ModelProvider { awaitCancellation() })
        val job = launch { runner.run(fixture.threadId, fixture.turnId, "Wait") }
        advanceUntilIdle()

        job.cancelAndJoin()

        val cancellations = fixture.payloads().filterIsInstance<TurnCancelled>()
        assertEquals(1, cancellations.size)
        assertEquals("Cancelled by user", cancellations.single().reason)
    }

    @Test
    fun `continuation turns reuse one thread lifecycle and replay prior conversation`() = runTest {
        val fixture = Fixture()
        val requests = mutableListOf<ModelRequest>()
        val provider = ModelProvider { request ->
            requests += request
            ModelResponse(
                message = if (requests.size == 1) "First complete" else "Second complete",
                stopReason = ModelStopReason.COMPLETE,
            )
        }
        val runner = fixture.runner(provider)

        runner.run(fixture.threadId, fixture.turnId, "Initial task")
        runner.run(fixture.threadId, TurnId("turn-2"), "Continue the task")

        val payloads = fixture.payloads()
        assertEquals(1, payloads.filterIsInstance<ThreadStarted>().size)
        assertEquals(2, payloads.filterIsInstance<TurnStarted>().size)
        val secondMessages = requests[1].input.filterIsInstance<dev.ferro.contracts.ModelMessageInput>()
        assertTrue(secondMessages.any { it.text == "Initial task" })
        assertTrue(secondMessages.any { it.text == "First complete" })
        assertTrue(secondMessages.any { it.text == "Continue the task" })
    }

    @Test
    fun `pre-model pause recovery does not spend model iteration budget`() = runTest {
        val fixture = Fixture()
        var checkpoints = 0
        var samples = 0
        val coordinator = object : TurnCoordinator {
            override suspend fun checkpoint(point: TurnCheckpoint): TurnDirective {
                checkpoints++
                return if (point == TurnCheckpoint.BEFORE_MODEL && checkpoints == 1) {
                    TurnDirective.Recapture(RecaptureReason.RESUMED)
                } else {
                    TurnDirective.Proceed
                }
            }

            override suspend fun updateActivity(activity: AgentActivity) = Unit
        }
        val runner = fixture.runner(
            provider = ModelProvider {
                samples++
                ModelResponse(message = "Completed", stopReason = ModelStopReason.COMPLETE)
            },
            budget = TurnBudget(maxIterations = 1),
        )

        val outcome = runner.run(fixture.threadId, fixture.turnId, "Resume", coordinator)

        assertEquals(1, samples)
        assertEquals(TurnOutcome.Completed("Completed"), outcome)
    }

    private class Fixture {
        val ids = SequentialIdGenerator()
        val store = InMemoryAgentEventStore(ids, IncrementingClock())
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")

        fun runner(
            provider: ModelProvider,
            budget: TurnBudget = TurnBudget(),
            toolCallBinder: ToolCallBinder = ToolCallBinder.IDENTITY,
            onExecute: () -> Unit = {},
        ): AgentTurnLoop {
            val handler = object : ToolHandler {
                override val spec = ModelToolSpec("observe_screen", "Observe")
                override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
                    onExecute()
                    return ToolResult(
                        callId = call.id,
                        status = ToolResultStatus.SUCCESS,
                        output = buildJsonObject { put("screen", "ready") },
                    )
                }
            }
            val router = ToolRouter(ToolRegistry(listOf(handler)))
            return AgentTurnLoop(
                eventStore = store,
                contextBuilder = EventSourcedModelContextBuilder(store, router),
                provider = provider,
                authorizationGate = testAuthorizationGate(router, store),
                ids = ids,
                budget = budget,
                toolCallBinder = toolCallBinder,
            )
        }

        suspend fun payloads(): List<AgentEventPayload> = store.readThread(threadId).map { it.payload }
    }
}
