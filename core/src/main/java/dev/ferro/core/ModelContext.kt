package dev.ferro.core

import dev.ferro.contracts.ModelContextSummary
import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnResumed
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

data class StepContext(
    val threadId: ThreadId,
    val turnId: TurnId,
    val contextFingerprint: String,
    val summary: ModelContextSummary,
    val request: ModelRequest,
)

interface ModelContextBuilder {
    suspend fun captureStep(threadId: ThreadId, turnId: TurnId): StepContext
}

class EventSourcedModelContextBuilder(
    private val eventStore: AgentEventStore,
    private val toolRouter: ToolRouter,
    private val attachmentResolver: ModelAttachmentResolver = ModelAttachmentResolver.NONE,
    private val conversationHistory: ConversationHistory = EventReconstructedConversationHistory,
    private val budget: TurnBudget = TurnBudget(),
) : ModelContextBuilder {
    override suspend fun captureStep(threadId: ThreadId, turnId: TurnId): StepContext {
        val events = eventStore.readThread(threadId)
        val input = conversationHistory.rebuild(events).boundedForModel().toMutableList()
        val lastResumeSequence = events.lastOrNull {
            it.turnId == turnId &&
                (it.payload is TurnResumed || it.payload is TurnRecoveryPaused)
        }?.sequence ?: Long.MIN_VALUE
        val latestToolResult = events.asReversed()
            .firstNotNullOfOrNull { event ->
                if (event.turnId == turnId && event.sequence > lastResumeSequence) {
                    (event.payload as? ToolResultRecorded)?.result
                } else {
                    null
                }
            }
        latestToolResult?.attachments?.lastOrNull()
            ?.let { attachmentResolver.resolve(it) }
            ?.copy(
                prompt = currentScreenshotPrompt(latestToolResult),
                sourceToolCallId = latestToolResult.callId,
                sourceObservationId = latestToolResult.output["observation_id"]
                    ?.jsonPrimitive
                    ?.contentOrNull,
                isFromLatestToolResult = true,
            )
            ?.let(input::add)
        val lastSequence = events.lastOrNull()?.sequence ?: 0L
        val fingerprint = listOf(threadId.value, turnId.value, lastSequence, toolRouter.catalogVersion)
            .joinToString(":")
            .hashCode()
            .toString(16)
        return StepContext(
            threadId = threadId,
            turnId = turnId,
            contextFingerprint = fingerprint,
            summary = ModelContextSummary(
                inputItems = input.size,
                userMessages = input.count {
                    it is ModelMessageInput && it.role == ModelMessageRole.USER
                },
                assistantMessages = input.count {
                    it is ModelMessageInput && it.role == ModelMessageRole.ASSISTANT
                },
                toolCalls = input.count { it is ModelToolCallInput },
                toolResults = input.count { it is ModelToolResultInput },
                images = input.count { it is ModelImageInput },
                advertisedTools = toolRouter.specs.size,
            ),
            request = ModelRequest(
                threadId = threadId,
                turnId = turnId,
                instructions = AndroidAgentInstructions.build(
                    remainingIterations = (budget.maxIterations - events.count {
                        it.turnId == turnId && it.payload is dev.ferro.contracts.ModelIterationStarted
                    }).coerceAtLeast(0),
                    remainingToolCalls = (budget.maxToolCalls - events.count {
                        it.turnId == turnId &&
                            (it.payload as? dev.ferro.contracts.ToolCallRecorded)
                                ?.origin == dev.ferro.contracts.ToolCallOrigin.MODEL
                    }).coerceAtLeast(0),
                ),
                input = input,
                tools = toolRouter.specs,
                metadata = mapOf(
                    "context_fingerprint" to fingerprint,
                    "tool_catalog_version" to toolRouter.catalogVersion,
                    "last_event_sequence" to lastSequence.toString(),
                ),
            ),
        )
    }

    private fun currentScreenshotPrompt(result: dev.ferro.contracts.ToolResult): String {
        val observationId = result.output["observation_id"]?.jsonPrimitive?.contentOrNull
        return buildString {
            append("Latest usable screenshot returned by a tool result")
            observationId?.let {
                append(" (observation_id=")
                append(it)
                append(')')
            }
            append(". It is attached by this result, not inferred from an earlier tool call.")
        }
    }

}

private fun List<dev.ferro.contracts.ModelInputItem>.boundedForModel(): List<dev.ferro.contracts.ModelInputItem> {
    if (size <= MAX_HISTORY_ITEMS) return this
    val firstTask = firstOrNull { it is ModelMessageInput && it.role == ModelMessageRole.USER }
    val tail = takeLast(MAX_HISTORY_ITEMS - 1).toMutableList()
    while (tail.firstOrNull() is ModelToolResultInput) tail.removeAt(0)
    return listOfNotNull(firstTask) + tail
}

private const val MAX_HISTORY_ITEMS = 50
