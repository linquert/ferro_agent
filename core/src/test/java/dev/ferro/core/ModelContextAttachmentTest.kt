package dev.ferro.core

import dev.ferro.contracts.IterationId
import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolAttachmentKind
import dev.ferro.contracts.ToolAttachmentRef
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnRecoveryResumed
import dev.ferro.contracts.TurnStarted
import dev.ferro.contracts.UserInputRecorded
import dev.ferro.contracts.TurnResumed
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextAttachmentTest {
    @Test
    fun `long context keeps original task and newest user facts within bounded window`() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryAgentEventStore()
            val threadId = ThreadId("thread-bounded")
            val turnId = TurnId("turn-bounded")
            store.append(threadId, turnId, TurnStarted("Original task"))
            repeat(70) { index ->
                store.append(threadId, turnId, UserInputRecorded("Update $index"))
            }
            val builder = EventSourcedModelContextBuilder(
                store,
                ToolRouter(ToolRegistry(emptyList())),
            )

            val step = builder.captureStep(threadId, turnId)
            val messages = step.request.input.filterIsInstance<ModelMessageInput>()

            assertTrue(step.request.input.size <= 50)
            assertEquals("Original task", messages.first().text)
            assertEquals("Update 69", messages.last().text)
            assertTrue(step.request.instructions.contains("30 model iterations and 60 tool calls"))
        }

    @Test
    fun `recovery capture becomes current image without fabricating model tool history`() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryAgentEventStore()
            val threadId = ThreadId("thread-recovery")
            val turnId = TurnId("turn-recovery")
            val iterationId = IterationId("recovery-iteration")
            val call = ToolCall(ToolCallId("recovery-call"), "observe_screen", JsonObject(emptyMap()))
            val oldCall = ToolCall(ToolCallId("old-call"), "tap", JsonObject(emptyMap()))
            store.append(threadId, turnId, TurnStarted("Continue safely"))
            store.append(
                threadId,
                turnId,
                ToolCallRecorded(IterationId("before-restart"), oldCall),
            )
            store.append(
                threadId,
                turnId,
                ToolResultRecorded(
                    IterationId("before-restart"),
                    ToolResult(
                        oldCall.id,
                        ToolResultStatus.SUCCESS,
                        output = buildJsonObject { put("observation_id", "old-observation") },
                        attachments = listOf(attachment("old")),
                    ),
                ),
            )
            store.append(threadId, turnId, TurnRecoveryPaused("process restart"))
            store.append(
                threadId,
                turnId,
                ToolCallRecorded(iterationId, call, ToolCallOrigin.RUNTIME_RECOVERY),
            )
            store.append(
                threadId,
                turnId,
                ToolResultRecorded(
                    iterationId,
                    ToolResult(
                        call.id,
                        ToolResultStatus.SUCCESS,
                        output = buildJsonObject { put("observation_id", "fresh-observation") },
                        attachments = listOf(attachment("fresh")),
                    ),
                ),
            )
            store.append(threadId, turnId, TurnRecoveryResumed("fresh-observation"))
            val resolved = mutableListOf<String>()
            val builder = EventSourcedModelContextBuilder(
                store,
                ToolRouter(ToolRegistry(emptyList())),
                ModelAttachmentResolver { ref ->
                    resolved += ref.uri
                    ModelImageInput("data:image/png;base64,AAA")
                },
            )

            val step = builder.captureStep(threadId, turnId)

            assertEquals(listOf("artifact://fresh"), resolved)
            assertEquals(1, step.request.input.count { it is dev.ferro.contracts.ModelToolCallInput })
            assertEquals(1, step.request.input.count { it is ModelToolResultInput })
            assertTrue(step.request.input.none {
                it is dev.ferro.contracts.ModelToolCallInput && it.call.id == call.id
            })
            assertTrue(step.request.input.none {
                it is ModelToolResultInput && it.result.callId == call.id
            })
            assertEquals(1, step.request.input.count { it is ModelImageInput })
            val image = step.request.input.last() as ModelImageInput
            assertEquals("fresh-observation", image.sourceObservationId)
            assertTrue(image.isFromLatestToolResult)
        }

    @Test
    fun `context preserves every result but resolves only latest screenshot`() = kotlinx.coroutines.test.runTest {
        val store = InMemoryAgentEventStore()
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")
        val oldAttachment = attachment("old")
        val latestAttachment = attachment("latest")
        store.append(threadId, turnId, ToolResultRecorded(
            IterationId("iteration-1"),
            ToolResult(ToolCallId("call-1"), ToolResultStatus.SUCCESS, attachments = listOf(oldAttachment)),
        ))
        store.append(threadId, turnId, ToolResultRecorded(
            IterationId("iteration-2"),
            ToolResult(
                ToolCallId("call-2"),
                ToolResultStatus.SUCCESS,
                output = buildJsonObject { put("observation_id", "observation-2") },
                attachments = listOf(latestAttachment),
            ),
        ))
        val resolved = mutableListOf<String>()
        val builder = EventSourcedModelContextBuilder(
            store,
            ToolRouter(ToolRegistry(emptyList())),
            ModelAttachmentResolver { ref ->
                resolved += ref.uri
                ModelImageInput("data:${ref.mediaType};base64,AAA", prompt = ref.uri)
            },
        )

        val step = builder.captureStep(threadId, turnId)
        val input = step.request.input

        assertEquals(2, input.count { it is ModelToolResultInput })
        assertEquals(1, input.count { it is ModelImageInput })
        assertEquals(listOf("artifact://latest"), resolved)
        val image = input.last() as ModelImageInput
        assertTrue(image.prompt?.contains("Latest usable screenshot") == true)
        assertTrue(image.prompt?.contains("observation_id=observation-2") == true)
        assertTrue(image.prompt?.contains("not inferred from an earlier tool call") == true)
        assertEquals(ToolCallId("call-2"), image.sourceToolCallId)
        assertEquals("observation-2", image.sourceObservationId)
        assertTrue(image.isFromLatestToolResult)
        assertEquals(input.size, step.summary.inputItems)
        assertEquals(2, step.summary.toolResults)
        assertEquals(1, step.summary.images)
        assertEquals(0, step.summary.advertisedTools)
    }

    @Test
    fun `attachment-free latest result never reuses an older screenshot`() = kotlinx.coroutines.test.runTest {
        val store = InMemoryAgentEventStore()
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")
        store.append(
            threadId,
            turnId,
            ToolResultRecorded(
                IterationId("iteration-1"),
                ToolResult(
                    ToolCallId("call-1"),
                    ToolResultStatus.SUCCESS,
                    attachments = listOf(attachment("stale")),
                ),
            ),
        )
        store.append(
            threadId,
            turnId,
            ToolResultRecorded(
                IterationId("iteration-2"),
                ToolResult(
                    ToolCallId("call-2"),
                    ToolResultStatus.RECOVERABLE_FAILURE,
                    message = "No current screenshot",
                ),
            ),
        )
        val resolved = mutableListOf<String>()
        val builder = EventSourcedModelContextBuilder(
            store,
            ToolRouter(ToolRegistry(emptyList())),
            ModelAttachmentResolver { ref ->
                resolved += ref.uri
                ModelImageInput("data:image/png;base64,AAA")
            },
        )

        val step = builder.captureStep(threadId, turnId)

        assertTrue(resolved.isEmpty())
        assertEquals(0, step.request.input.count { it is ModelImageInput })
        assertEquals(0, step.summary.images)
    }

    @Test
    fun `unresolvable latest attachment is omitted instead of falling back`() = kotlinx.coroutines.test.runTest {
        val store = InMemoryAgentEventStore()
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")
        store.append(
            threadId,
            turnId,
            ToolResultRecorded(
                IterationId("iteration-1"),
                ToolResult(
                    ToolCallId("call-1"),
                    ToolResultStatus.SUCCESS,
                    attachments = listOf(attachment("old")),
                ),
            ),
        )
        store.append(
            threadId,
            turnId,
            ToolResultRecorded(
                IterationId("iteration-2"),
                ToolResult(
                    ToolCallId("call-2"),
                    ToolResultStatus.SUCCESS,
                    attachments = listOf(attachment("missing")),
                ),
            ),
        )
        val resolved = mutableListOf<String>()
        val builder = EventSourcedModelContextBuilder(
            store,
            ToolRouter(ToolRegistry(emptyList())),
            ModelAttachmentResolver { ref ->
                resolved += ref.uri
                null
            },
        )

        val step = builder.captureStep(threadId, turnId)

        assertEquals(listOf("artifact://missing"), resolved)
        assertEquals(0, step.summary.images)
    }

    @Test
    fun `new turn keeps thread history but does not attach the previous turn screenshot as current`() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryAgentEventStore()
            val threadId = ThreadId("thread")
            val previousTurn = TurnId("turn-1")
            val resumedTurn = TurnId("turn-2")
            store.append(threadId, previousTurn, TurnStarted("Initial task"))
            store.append(
                threadId,
                previousTurn,
                ToolResultRecorded(
                    IterationId("iteration-1"),
                    ToolResult(
                        ToolCallId("call-1"),
                        ToolResultStatus.SUCCESS,
                        attachments = listOf(attachment("previous-turn")),
                    ),
                ),
            )
            store.append(threadId, resumedTurn, TurnStarted("Continue after user control"))
            val resolved = mutableListOf<String>()
            val builder = EventSourcedModelContextBuilder(
                store,
                ToolRouter(ToolRegistry(emptyList())),
                ModelAttachmentResolver { ref ->
                    resolved += ref.uri
                    ModelImageInput("data:image/png;base64,AAA")
                },
            )

            val step = builder.captureStep(threadId, resumedTurn)

            assertTrue(resolved.isEmpty())
            assertEquals(0, step.summary.images)
            assertEquals(1, step.summary.toolResults)
            assertEquals(
                listOf("Initial task", "Continue after user control"),
                step.request.input.filterIsInstance<ModelMessageInput>()
                    .filter { it.role == ModelMessageRole.USER }
                    .map { it.text },
            )
        }

    @Test
    fun `resume invalidates screenshots captured before manual user control`() =
        kotlinx.coroutines.test.runTest {
            val store = InMemoryAgentEventStore()
            val threadId = ThreadId("thread")
            val turnId = TurnId("turn")
            store.append(
                threadId,
                turnId,
                ToolResultRecorded(
                    IterationId("iteration"),
                    ToolResult(
                        ToolCallId("call"),
                        ToolResultStatus.SUCCESS,
                        attachments = listOf(attachment("before-pause")),
                    ),
                ),
            )
            store.append(threadId, turnId, TurnResumed)
            val resolved = mutableListOf<String>()
            val builder = EventSourcedModelContextBuilder(
                store,
                ToolRouter(ToolRegistry(emptyList())),
                ModelAttachmentResolver { ref ->
                    resolved += ref.uri
                    ModelImageInput("data:image/png;base64,AAA")
                },
            )

            val step = builder.captureStep(threadId, turnId)

            assertTrue(resolved.isEmpty())
            assertEquals(0, step.summary.images)
            assertEquals(1, step.summary.toolResults)
        }

    private fun attachment(id: String) = ToolAttachmentRef(
        ToolAttachmentKind.IMAGE,
        "artifact://$id",
        "image/png",
    )
}
