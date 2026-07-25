package dev.ferro.core

import dev.ferro.contracts.AssistantMessageRecorded
import dev.ferro.contracts.AssistantReasoningRecorded
import dev.ferro.contracts.IterationId
import dev.ferro.contracts.ModelIterationStarted
import dev.ferro.contracts.ModelResponse
import dev.ferro.contracts.ModelResponseCompleted
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.UserInputRecorded
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TurnProgress(
    val toolCallCount: Int = 0,
    val actionCount: Int = 0,
    val lastFailureSignature: String? = null,
    val identicalFailureCount: Int = 0,
    val consecutiveMessageOnlyResponses: Int = 0,
)

sealed interface StepOutcome {
    data class Continue(val progress: TurnProgress) : StepOutcome
    data class Complete(val finalMessage: String) : StepOutcome
    data class Fail(val code: String, val message: String) : StepOutcome
}

data class SampledStep(
    val context: StepContext,
    val iterationId: IterationId,
    val response: ModelResponse,
)

sealed interface StepPreflight {
    data class Tools(val toolCallCount: Int) : StepPreflight
    data class Complete(val finalMessage: String) : StepPreflight
    data class RetryWithCommand(val progress: TurnProgress) : StepPreflight
    data class Fail(val code: String, val message: String) : StepPreflight
}

class AgentStepRunner(
    private val eventStore: AgentEventStore,
    private val contextBuilder: ModelContextBuilder,
    private val provider: ModelProvider,
    private val authorizationGate: ToolAuthorizationGate,
    private val ids: IdGenerator,
    private val budget: TurnBudget,
) {
    suspend fun sampleStep(threadId: ThreadId, turnId: TurnId): SampledStep {
        val context = contextBuilder.captureStep(threadId, turnId)
        val iterationId = ids.iterationId()
        eventStore.append(
            threadId,
            turnId,
            ModelIterationStarted(iterationId, context.contextFingerprint, context.summary),
        )
        val response = provider.sample(context.request)
        persistModelResponse(threadId, turnId, iterationId, response)
        return SampledStep(context, iterationId, response)
    }

    fun preflight(
        response: ModelResponse,
        progress: TurnProgress,
        explicitCompletionRequired: Boolean = false,
    ): StepPreflight {
        if (response.stopReason == ModelStopReason.OUTPUT_LIMIT && response.toolCalls.isNotEmpty()) {
            return StepPreflight.Fail(
                "TRUNCATED_TOOL_CALLS",
                "The model response ended at its output limit; no tool calls were executed.",
            )
        }
        if (response.toolCalls.isEmpty()) {
            val message = response.message
            if (message.isNullOrBlank()) {
                return StepPreflight.Fail("EMPTY_MODEL_RESPONSE", "Model returned no message or tool call")
            }
            if (!explicitCompletionRequired) return StepPreflight.Complete(message)
            return if (progress.consecutiveMessageOnlyResponses == 0) {
                StepPreflight.RetryWithCommand(
                    progress.copy(consecutiveMessageOnlyResponses = 1),
                )
            } else {
                StepPreflight.Fail(
                    "MISSING_TERMINAL_COMMAND",
                    "Model returned chat twice without a tool call; complete_task is required to finish",
                )
            }
        }
        val completionCalls = response.toolCalls.filter { it.name == "complete_task" }
        if (completionCalls.isNotEmpty() && response.toolCalls.size != 1) {
            return StepPreflight.Fail(
                "INVALID_COMPLETION_BATCH",
                "complete_task must be the only tool call in a model response",
            )
        }
        val toolCallCount = progress.toolCallCount + response.toolCalls.size
        return if (toolCallCount > budget.maxToolCalls) {
            StepPreflight.Fail("TOOL_BUDGET_EXCEEDED", "Tool-call budget exceeded")
        } else {
            StepPreflight.Tools(toolCallCount)
        }
    }

    suspend fun recordToolCalls(
        threadId: ThreadId,
        turnId: TurnId,
        iterationId: IterationId,
        calls: List<ToolCall>,
    ) {
        calls.forEach { call ->
            eventStore.append(threadId, turnId, ToolCallRecorded(iterationId, call))
        }
    }

    suspend fun executeTool(
        sampled: SampledStep,
        call: ToolCall,
        seenCallIds: MutableSet<String> = mutableSetOf(),
        completedActionCount: Int = 0,
    ): ToolResult {
        val result = if (!seenCallIds.add(call.id.value)) {
            ToolResult(
                callId = call.id,
                status = ToolResultStatus.RECOVERABLE_FAILURE,
                output = buildJsonObject {
                    put("code", "DUPLICATE_CALL_ID")
                    put("dispatch", "not_dispatched")
                    put("platform_outcome", "rejected")
                },
                message = "Duplicate tool call ID",
            )
        } else {
            authorizationGate.execute(
                ToolExecutionContext(
                    sampled.context.threadId,
                    sampled.context.turnId,
                    sampled.context.contextFingerprint,
                ),
                call,
                completedActionCount,
            )
        }
        recordToolResult(
            sampled.context.threadId,
            sampled.context.turnId,
            sampled.iterationId,
            result,
        )
        return result
    }

    suspend fun cancelUnexecutedTools(
        sampled: SampledStep,
        calls: List<ToolCall>,
        code: String,
        reason: String,
    ) {
        calls.forEach { call ->
            recordToolResult(
                sampled.context.threadId,
                sampled.context.turnId,
                sampled.iterationId,
                ToolResult(
                    callId = call.id,
                    status = ToolResultStatus.CANCELLED,
                    output = buildJsonObject {
                        put("code", code)
                        put("dispatch", "not_dispatched")
                        put("platform_outcome", "cancelled")
                    },
                    message = reason,
                ),
            )
        }
    }

    suspend fun recordUserInputs(threadId: ThreadId, turnId: TurnId, inputs: List<String>) {
        inputs.forEach { input ->
            eventStore.append(threadId, turnId, UserInputRecorded(input))
        }
    }

    fun evaluateToolResults(
        toolNames: List<String>,
        results: List<ToolResult>,
        previousProgress: TurnProgress,
        toolCallCount: Int,
        actionCount: Int,
    ): StepOutcome {
        results.firstOrNull { it.status == ToolResultStatus.FATAL_FAILURE }?.let { result ->
            return StepOutcome.Fail(
                result.output["code"]?.toString()?.trim('"') ?: "FATAL_TOOL_FAILURE",
                result.message ?: "A required tool became unavailable",
            )
        }
        results.firstOrNull { it.status == ToolResultStatus.TASK_COMPLETED }?.let { result ->
            return StepOutcome.Complete(result.message ?: "Task completed")
        }
        if (results.isEmpty() || !results.all { it.status == ToolResultStatus.RECOVERABLE_FAILURE }) {
            return StepOutcome.Continue(TurnProgress(toolCallCount = toolCallCount, actionCount = actionCount))
        }
        val signature = toolNames.zip(results).joinToString("|") { (name, result) ->
            "$name:${result.output["code"]}:${result.message}"
        }
        val identicalFailureCount = if (signature == previousProgress.lastFailureSignature) {
            previousProgress.identicalFailureCount + 1
        } else {
            1
        }
        if (identicalFailureCount >= budget.maxConsecutiveIdenticalFailures) {
            return StepOutcome.Fail(
                "REPEATED_TOOL_FAILURE",
                "The same tool failure repeated $identicalFailureCount times",
            )
        }
        return StepOutcome.Continue(
            TurnProgress(
                toolCallCount = toolCallCount,
                actionCount = actionCount,
                lastFailureSignature = signature,
                identicalFailureCount = identicalFailureCount,
            ),
        )
    }

    private suspend fun persistModelResponse(
        threadId: ThreadId,
        turnId: TurnId,
        iterationId: IterationId,
        response: ModelResponse,
    ) {
        response.reasoning?.takeIf(String::isNotBlank)?.let { reasoning ->
            eventStore.append(threadId, turnId, AssistantReasoningRecorded(iterationId, reasoning))
        }
        response.message?.takeIf(String::isNotBlank)?.let { message ->
            eventStore.append(threadId, turnId, AssistantMessageRecorded(message))
        }
        eventStore.append(
            threadId,
            turnId,
            ModelResponseCompleted(
                iterationId = iterationId,
                providerResponseId = response.providerResponseId,
                stopReason = response.stopReason,
                usage = response.usage,
            ),
        )
    }

    private suspend fun recordToolResult(
        threadId: ThreadId,
        turnId: TurnId,
        iterationId: IterationId,
        result: ToolResult,
    ) {
        eventStore.append(threadId, turnId, ToolResultRecorded(iterationId, result))
    }
}
