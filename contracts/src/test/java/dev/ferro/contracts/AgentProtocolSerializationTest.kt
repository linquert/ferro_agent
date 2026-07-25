package dev.ferro.contracts

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentProtocolSerializationTest {
    private val json = Json {
        classDiscriminator = "eventType"
        encodeDefaults = true
    }

    @Test
    fun `event envelope round trips without losing typed payload`() {
        val original = AgentEventEnvelope(
            eventId = "event-1",
            threadId = ThreadId("thread-1"),
            turnId = TurnId("turn-1"),
            sequence = 3,
            timestampEpochMs = 42,
            payload = ToolResultRecorded(
                iterationId = IterationId("iteration-1"),
                result = ToolResult(
                    callId = ToolCallId("call-1"),
                    status = ToolResultStatus.SUCCESS,
                    message = "observed",
                    attachments = listOf(
                        ToolAttachmentRef(
                            kind = ToolAttachmentKind.IMAGE,
                            uri = "artifact://screen-1",
                            mediaType = "image/png",
                        ),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(original)
        val decoded = json.decodeFromString(AgentEventEnvelope.serializer(), encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `reasoning and model usage events round trip as distinct durable facts`() {
        val payloads: List<AgentEventPayload> = listOf(
            AssistantReasoningRecorded(IterationId("iteration-1"), "Inspect the visible controls"),
            ModelResponseCompleted(
                iterationId = IterationId("iteration-1"),
                providerResponseId = "response-1",
                stopReason = ModelStopReason.TOOL_CALLS,
                usage = ModelUsage(120, 30, 150),
            ),
        )

        payloads.forEachIndexed { index, payload ->
            val original = AgentEventEnvelope(
                eventId = "event-$index",
                threadId = ThreadId("thread-1"),
                turnId = TurnId("turn-1"),
                sequence = index.toLong(),
                timestampEpochMs = 42,
                payload = payload,
            )
            val decoded = json.decodeFromString(
                AgentEventEnvelope.serializer(),
                json.encodeToString(original),
            )

            assertEquals(original, decoded)
        }
    }

    @Test
    fun `session submissions round trip with their typed operation`() {
        val submissions = listOf(
            AgentSubmission(
                id = SubmissionId("submission-1"),
                operation = StartTurn(TurnId("turn-1"), "Open settings"),
            ),
            AgentSubmission(
                id = SubmissionId("submission-2"),
                operation = InterruptTurn(TurnId("turn-1")),
            ),
            AgentSubmission(
                id = SubmissionId("submission-3"),
                operation = SteerTurn(
                    expectedTurnId = TurnId("turn-1"),
                    input = "Use the second option instead",
                ),
            ),
            AgentSubmission(
                id = SubmissionId("submission-4"),
                operation = PauseTurn(TurnId("turn-1")),
            ),
            AgentSubmission(
                id = SubmissionId("submission-5"),
                operation = ResumeTurn(TurnId("turn-1")),
            ),
            AgentSubmission(
                id = SubmissionId("submission-6"),
                operation = AnswerUserRequest(UserRequestId("request-1"), "Done"),
            ),
            AgentSubmission(
                id = SubmissionId("submission-7"),
                operation = ShutdownSession,
            ),
        )

        submissions.forEach { original ->
            val decoded = json.decodeFromString(
                AgentSubmission.serializer(),
                json.encodeToString(original),
            )

            assertEquals(original, decoded)
        }
    }

    @Test
    fun `recovery lifecycle and tool origin round trip while legacy calls default to model`() {
        val recoveryCall = ToolCallRecorded(
            IterationId("recovery-iteration"),
            ToolCall(ToolCallId("recovery-call"), "observe_screen", JsonObject(emptyMap())),
            ToolCallOrigin.RUNTIME_RECOVERY,
        )
        val payloads: List<AgentEventPayload> = listOf(
            TurnRecoveryPaused("Android process restarted"),
            recoveryCall,
            TurnRecoveryResumed("observation-fresh"),
        )

        payloads.forEachIndexed { index, payload ->
            val original = AgentEventEnvelope(
                eventId = "recovery-$index",
                threadId = ThreadId("thread"),
                turnId = TurnId("turn"),
                sequence = index.toLong(),
                timestampEpochMs = 10,
                payload = payload,
            )
            assertEquals(
                original,
                json.decodeFromString(AgentEventEnvelope.serializer(), json.encodeToString(original)),
            )
        }

        val modelCall = AgentEventEnvelope(
            eventId = "legacy",
            threadId = ThreadId("thread"),
            turnId = TurnId("turn"),
            sequence = 1,
            timestampEpochMs = 10,
            payload = recoveryCall.copy(origin = ToolCallOrigin.MODEL),
        )
        val encoded = json.encodeToJsonElement(AgentEventEnvelope.serializer(), modelCall).jsonObject
        val payload = encoded.getValue("payload").jsonObject
        val legacy = JsonObject(encoded + ("payload" to JsonObject(payload - "origin")))
        val decoded = json.decodeFromJsonElement(AgentEventEnvelope.serializer(), legacy)

        assertEquals(ToolCallOrigin.MODEL, (decoded.payload as ToolCallRecorded).origin)
    }
}
