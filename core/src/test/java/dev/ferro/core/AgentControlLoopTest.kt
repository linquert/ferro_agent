package dev.ferro.core

import dev.ferro.contracts.AnswerUserRequest
import dev.ferro.contracts.InterruptTurn
import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelResponse
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.PauseTurn
import dev.ferro.contracts.ResumeTurn
import dev.ferro.contracts.ShutdownSession
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.SteerTurn
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolAttachmentKind
import dev.ferro.contracts.ToolAttachmentRef
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.emptyArguments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentControlLoopTest {
    @Test
    fun `steer arriving during sampling cancels stale tools before they touch device`() = runTest {
        val fixture = Fixture()
        val modelStarted = CompletableDeferred<Unit>()
        val releaseModel = CompletableDeferred<Unit>()
        val requests = mutableListOf<ModelRequest>()
        var deviceExecutions = 0
        val handler = handler("tap") { call ->
            deviceExecutions++
            ToolResult(call.id, ToolResultStatus.SUCCESS)
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))
        val provider = ModelProvider { request ->
            requests += request
            if (requests.size == 1) {
                modelStarted.complete(Unit)
                releaseModel.await()
                ModelResponse(
                    toolCalls = listOf(ToolCall(ToolCallId("stale-call"), "tap", emptyArguments())),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            } else {
                ModelResponse(message = "Adjusted", stopReason = ModelStopReason.COMPLETE)
            }
        }
        val io = fixture.session(router, provider).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Initial task"))
        advanceUntilIdle()
        assertTrue(modelStarted.isCompleted)
        io.submit(SteerTurn(fixture.turnId, "Do not tap; inspect instead"))
        advanceUntilIdle()
        releaseModel.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, deviceExecutions)
        assertEquals(2, requests.size)
        assertTrue(
            requests[1].input.filterIsInstance<ModelMessageInput>()
                .any { it.text == "Do not tap; inspect instead" },
        )
        val staleResult = fixture.store.readThread(fixture.threadId)
            .map { it.payload }
            .filterIsInstance<ToolResultRecorded>()
            .single()
            .result
        assertEquals(ToolResultStatus.CANCELLED, staleResult.status)
        assertEquals("\"SUPERSEDED_BY_USER_INPUT\"", staleResult.output["code"].toString())

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `pause during tool waits for result then blocks next tool until resume`() = runTest {
        val fixture = Fixture()
        val firstToolStarted = CompletableDeferred<Unit>()
        val releaseFirstTool = CompletableDeferred<Unit>()
        val executed = mutableListOf<String>()
        val handler = handler("act") { call ->
            executed += call.id.value
            if (call.id.value == "first") {
                firstToolStarted.complete(Unit)
                releaseFirstTool.await()
            }
            ToolResult(call.id, ToolResultStatus.SUCCESS)
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))
        var requests = 0
        val provider = ModelProvider {
            requests++
            if (requests == 1) {
                ModelResponse(
                    toolCalls = listOf(
                        ToolCall(ToolCallId("first"), "act", emptyArguments()),
                        ToolCall(ToolCallId("second"), "act", emptyArguments()),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            } else {
                ModelResponse(message = "Done after resume", stopReason = ModelStopReason.COMPLETE)
            }
        }
        val io = fixture.session(router, provider).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Two actions"))
        advanceUntilIdle()
        assertTrue(firstToolStarted.isCompleted)
        io.submit(PauseTurn(fixture.turnId))
        advanceUntilIdle()
        assertEquals(AgentSessionPhase.PAUSE_REQUESTED, io.snapshot.value.phase)

        releaseFirstTool.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first"), executed)
        assertEquals(AgentSessionPhase.PAUSED, io.snapshot.value.phase)

        io.submit(ResumeTurn(fixture.turnId))
        advanceUntilIdle()
        assertEquals(listOf("first"), executed)
        assertEquals(TurnOutcome.Completed("Done after resume"), io.snapshot.value.lastOutcome)
        val second = fixture.store.readThread(fixture.threadId)
            .map { it.payload }
            .filterIsInstance<ToolResultRecorded>()
            .first { it.result.callId == ToolCallId("second") }
        assertEquals(ToolResultStatus.CANCELLED, second.result.status)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `request user input suspends tool and answer resumes exact call`() = runTest {
        val fixture = Fixture()
        val broker = UserInteractionBroker(fixture.store)
        val router = ToolRouter(ToolRegistry(UserInteractionToolCatalog(broker).handlers()))
        var requests = 0
        val provider = ModelProvider {
            requests++
            if (requests == 1) {
                ModelResponse(
                    toolCalls = listOf(
                        ToolCall(
                            ToolCallId("question"),
                            "request_user_input",
                            buildJsonObject { put("prompt", "Which account should I use?") },
                        ),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            } else {
                ModelResponse(message = "Used personal", stopReason = ModelStopReason.COMPLETE)
            }
        }
        val io = fixture.session(router, provider, broker).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Choose an account"))
        advanceUntilIdle()

        assertEquals(AgentSessionPhase.WAITING_FOR_USER, io.snapshot.value.phase)
        val request = io.snapshot.value.pendingUserRequest!!
        assertEquals("Which account should I use?", request.prompt)

        io.submit(AnswerUserRequest(request.requestId, "Personal account"))
        advanceUntilIdle()

        assertEquals(TurnOutcome.Completed("Used personal"), io.snapshot.value.lastOutcome)
        val result = fixture.store.readThread(fixture.threadId)
            .map { it.payload }
            .filterIsInstance<ToolResultRecorded>()
            .single()
            .result
        assertEquals("\"Personal account\"", result.output["response"].toString())

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `request user control returns note with recovered fresh screenshot`() = runTest {
        val fixture = Fixture()
        val broker = UserInteractionBroker(fixture.store)
        val recovery = UserControlRecovery { _, call, note ->
            ToolResult(
                callId = call.id,
                status = ToolResultStatus.SUCCESS,
                output = buildJsonObject {
                    put("observation_id", "fresh-after-user")
                    put("user_note", note)
                },
                attachments = listOf(
                    ToolAttachmentRef(
                        ToolAttachmentKind.IMAGE,
                        "artifact://fresh-after-user",
                        "image/png",
                    ),
                ),
            )
        }
        val router = ToolRouter(
            ToolRegistry(UserInteractionToolCatalog(broker, recovery).handlers()),
        )
        var requests = 0
        val provider = ModelProvider {
            requests++
            if (requests == 1) {
                ModelResponse(
                    toolCalls = listOf(
                        ToolCall(
                            ToolCallId("control"),
                            "request_user_control",
                            buildJsonObject {
                                put("reason", "Sign-in requires manual authentication")
                                put("suggested_action", "Complete the sign-in")
                            },
                        ),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            } else {
                ModelResponse(message = "Sign-in complete", stopReason = ModelStopReason.COMPLETE)
            }
        }
        val io = fixture.session(router, provider, broker).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Sign in"))
        advanceUntilIdle()
        val pending = io.snapshot.value.pendingUserRequest!!
        assertEquals("Sign-in requires manual authentication", pending.reason)
        assertEquals("Complete the sign-in", pending.suggestedAction)
        io.submit(AnswerUserRequest(pending.requestId, "I completed sign-in"))
        advanceUntilIdle()

        val result = fixture.store.readThread(fixture.threadId)
            .map { it.payload }
            .filterIsInstance<ToolResultRecorded>()
            .single()
            .result
        assertEquals("\"fresh-after-user\"", result.output["observation_id"].toString())
        assertEquals("\"I completed sign-in\"", result.output["user_note"].toString())
        assertEquals("artifact://fresh-after-user", result.attachments.single().uri)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `explicit completion command is recorded before successful turn outcome`() = runTest {
        val fixture = Fixture()
        val lifecycle = AgentLifecycleToolCatalog().handlers()
        val router = ToolRouter(ToolRegistry(lifecycle))
        val provider = ModelProvider {
            ModelResponse(
                message = "Finishing the task",
                toolCalls = listOf(
                    ToolCall(
                        ToolCallId("complete"),
                        "complete_task",
                        buildJsonObject {
                            put("summary", "The requested task is complete")
                            put("evidence", "The final state was visibly confirmed")
                        },
                    ),
                ),
                stopReason = ModelStopReason.TOOL_CALLS,
            )
        }
        val io = fixture.session(router, provider).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Complete a task"))
        advanceUntilIdle()

        assertEquals(
            TurnOutcome.Completed("The requested task is complete"),
            io.snapshot.value.lastOutcome,
        )
        val payloads = fixture.store.readThread(fixture.threadId).map { it.payload }
        val resultIndex = payloads.indexOfFirst {
            it is ToolResultRecorded && it.result.status == ToolResultStatus.TASK_COMPLETED
        }
        val completedIndex = payloads.indexOfFirst { it is dev.ferro.contracts.TurnCompleted }
        assertTrue(resultIndex in 0 until completedIndex)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `message-only response gets one repair iteration when completion command is advertised`() = runTest {
        val fixture = Fixture()
        val router = ToolRouter(ToolRegistry(AgentLifecycleToolCatalog().handlers()))
        var requests = 0
        val provider = ModelProvider {
            requests++
            if (requests == 1) {
                ModelResponse(message = "It is done", stopReason = ModelStopReason.COMPLETE)
            } else {
                ModelResponse(
                    toolCalls = listOf(
                        ToolCall(
                            ToolCallId("complete"),
                            "complete_task",
                            buildJsonObject { put("summary", "Done after protocol repair") },
                        ),
                    ),
                    stopReason = ModelStopReason.TOOL_CALLS,
                )
            }
        }
        val io = fixture.session(router, provider).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Complete deliberately"))
        advanceUntilIdle()

        assertEquals(2, requests)
        assertEquals(TurnOutcome.Completed("Done after protocol repair"), io.snapshot.value.lastOutcome)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `interrupt clears suspended user waiter and terminates active turn`() = runTest {
        val fixture = Fixture()
        val broker = UserInteractionBroker(fixture.store)
        val router = ToolRouter(ToolRegistry(UserInteractionToolCatalog(broker).handlers()))
        val provider = ModelProvider {
            ModelResponse(
                toolCalls = listOf(
                    ToolCall(
                        ToolCallId("question"),
                        "request_user_input",
                        buildJsonObject { put("prompt", "Need confirmation") },
                    ),
                ),
                stopReason = ModelStopReason.TOOL_CALLS,
            )
        }
        val io = fixture.session(router, provider, broker).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Wait for confirmation"))
        advanceUntilIdle()
        assertEquals(AgentSessionPhase.WAITING_FOR_USER, io.snapshot.value.phase)

        io.submit(InterruptTurn(fixture.turnId))
        advanceUntilIdle()

        assertEquals(null, broker.pending.value)
        assertEquals(AgentSessionPhase.IDLE, io.snapshot.value.phase)
        assertEquals(TurnOutcome.Cancelled("Stopped by user"), io.snapshot.value.lastOutcome)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    private fun handler(
        name: String,
        execute: suspend (ToolCall) -> ToolResult,
    ) = object : ToolHandler {
        override val spec = ModelToolSpec(name, name)
        override suspend fun execute(context: ToolExecutionContext, call: ToolCall) = execute(call)
    }

    private class Fixture {
        val ids = SequentialIdGenerator()
        val store = InMemoryAgentEventStore(ids, IncrementingClock())
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")

        fun session(
            router: ToolRouter,
            provider: ModelProvider,
            broker: UserInteractionBroker = UserInteractionBroker(store),
        ): AgentSession {
            val runner = AgentTurnLoop(
                store,
                EventSourcedModelContextBuilder(store, router),
                provider,
                testAuthorizationGate(router, store),
                ids,
            )
            return AgentSession(threadId, runner, store, broker)
        }
    }
}
