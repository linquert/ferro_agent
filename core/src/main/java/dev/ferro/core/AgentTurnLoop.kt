package dev.ferro.core

import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ModelIterationStarted
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.TurnCancelled
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnFailed
import dev.ferro.contracts.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface TurnOutcome {
    data class Completed(val finalMessage: String) : TurnOutcome
    data class Failed(val code: String, val message: String) : TurnOutcome
    data class Cancelled(val reason: String) : TurnOutcome
}

fun interface TurnExecutor {
    suspend fun run(
        threadId: ThreadId,
        turnId: TurnId,
        goal: String,
        coordinator: TurnCoordinator,
    ): TurnOutcome
}

class AgentTurnLoop(
    private val eventStore: AgentEventStore,
    private val contextBuilder: ModelContextBuilder,
    private val provider: ModelProvider,
    private val authorizationGate: ToolAuthorizationGate,
    private val ids: IdGenerator = UuidIdGenerator(),
    private val budget: TurnBudget = TurnBudget(),
    private val toolCallBinder: ToolCallBinder = ToolCallBinder.IDENTITY,
) : TurnExecutor {
    suspend fun run(threadId: ThreadId, turnId: TurnId, goal: String): TurnOutcome =
        run(threadId, turnId, goal, TurnCoordinator.UNCONTROLLED)

    override suspend fun run(
        threadId: ThreadId,
        turnId: TurnId,
        goal: String,
        coordinator: TurnCoordinator,
    ): TurnOutcome {
        require(goal.isNotBlank()) { "Goal must not be blank" }
        TurnLifecycleJournal(eventStore).ensureStarted(threadId, turnId, goal)

        val stepRunner = AgentStepRunner(eventStore, contextBuilder, provider, authorizationGate, ids, budget)
        val durableTurnEvents = eventStore.readThread(threadId).filter { it.turnId == turnId }
        val durableModelCalls = durableTurnEvents.mapNotNull { it.payload as? ToolCallRecorded }
            .filter { it.origin == ToolCallOrigin.MODEL }
        var progress = TurnProgress(
            toolCallCount = durableModelCalls.size,
            actionCount = durableModelCalls.count { it.call.isNativeAction() },
        )
        return try {
            var modelIterations = durableTurnEvents.count { it.payload is ModelIterationStarted }
            iterationLoop@ while (modelIterations < budget.maxIterations) {
                val beforeModel = coordinator.checkpoint(TurnCheckpoint.BEFORE_MODEL)
                if (beforeModel is TurnDirective.Recapture) {
                    stepRunner.recordUserInputs(threadId, turnId, beforeModel.inputs)
                    continue@iterationLoop
                }
                modelIterations++
                coordinator.updateActivity(AgentActivity.Thinking)
                val sampled = stepRunner.sampleStep(threadId, turnId)
                when (val preflight = stepRunner.preflight(
                    sampled.response,
                    progress,
                    sampled.context.request.tools.any { it.name == "complete_task" },
                )) {
                    is StepPreflight.Fail -> return fail(
                        threadId,
                        turnId,
                        preflight.code,
                        preflight.message,
                    )
                    is StepPreflight.RetryWithCommand -> progress = preflight.progress
                    is StepPreflight.Complete -> {
                        val afterModel = coordinator.checkpoint(TurnCheckpoint.AFTER_MODEL_RESPONSE)
                        if (afterModel is TurnDirective.Recapture) {
                            stepRunner.recordUserInputs(threadId, turnId, afterModel.inputs)
                            continue@iterationLoop
                        }
                        eventStore.append(threadId, turnId, TurnCompleted(preflight.finalMessage))
                        return TurnOutcome.Completed(preflight.finalMessage)
                    }
                    is StepPreflight.Tools -> {
                        val calls = sampled.response.toolCalls.map(toolCallBinder::bind)
                        stepRunner.recordToolCalls(threadId, turnId, sampled.iterationId, calls)
                        val afterModel = coordinator.checkpoint(TurnCheckpoint.AFTER_MODEL_RESPONSE)
                        if (afterModel is TurnDirective.Recapture) {
                            stepRunner.cancelUnexecutedTools(
                                sampled,
                                calls,
                                afterModel.cancellationCode(),
                                afterModel.cancellationMessage(),
                            )
                            stepRunner.recordUserInputs(threadId, turnId, afterModel.inputs)
                            progress = TurnProgress(
                                toolCallCount = preflight.toolCallCount,
                                actionCount = progress.actionCount,
                            )
                            continue@iterationLoop
                        }

                        val results = mutableListOf<dev.ferro.contracts.ToolResult>()
                        val seenCallIds = mutableSetOf<String>()
                        for (index in calls.indices) {
                            val call = calls[index]
                            val beforeTool = coordinator.checkpoint(TurnCheckpoint.BEFORE_TOOL)
                            if (beforeTool is TurnDirective.Recapture) {
                                stepRunner.cancelUnexecutedTools(
                                    sampled,
                                    calls.drop(index),
                                    beforeTool.cancellationCode(),
                                    beforeTool.cancellationMessage(),
                                )
                                stepRunner.recordUserInputs(threadId, turnId, beforeTool.inputs)
                                progress = TurnProgress(
                                    toolCallCount = preflight.toolCallCount,
                                    actionCount = progress.actionCount +
                                        calls.take(index).count { it.isNativeAction() },
                                )
                                continue@iterationLoop
                            }
                            coordinator.updateActivity(
                                AgentActivity.UsingTool(
                                    activityHeading(call, sampled.response.message),
                                ),
                            )
                            results += stepRunner.executeTool(
                                sampled,
                                call,
                                seenCallIds,
                                progress.actionCount + calls.take(index).count { it.isNativeAction() },
                            )
                        }
                        when (val outcome = stepRunner.evaluateToolResults(
                            calls.map { it.name },
                            results,
                            progress,
                            preflight.toolCallCount,
                            progress.actionCount + calls.count { it.isNativeAction() },
                        )) {
                            is StepOutcome.Continue -> progress = outcome.progress
                            is StepOutcome.Fail -> return fail(
                                threadId,
                                turnId,
                                outcome.code,
                                outcome.message,
                            )
                            is StepOutcome.Complete -> {
                                eventStore.append(threadId, turnId, TurnCompleted(outcome.finalMessage))
                                return TurnOutcome.Completed(outcome.finalMessage)
                            }
                        }
                    }
                }
            }
            fail(threadId, turnId, "ITERATION_BUDGET_EXCEEDED", "Iteration budget exceeded")
        } catch (cancelled: CancellationException) {
            val reason = cancelled.message
                ?.takeUnless { it.endsWith(" was cancelled") }
                ?: "Cancelled by user"
            withContext(NonCancellable) {
                eventStore.append(threadId, turnId, TurnCancelled(reason))
            }
            TurnOutcome.Cancelled(reason)
        } catch (error: ModelProviderException) {
            fail(
                threadId,
                turnId,
                "MODEL_${error.kind.name}",
                error.message ?: "Model provider failed",
            )
        } catch (error: Throwable) {
            fail(
                threadId,
                turnId,
                "UNEXPECTED_RUNTIME_FAILURE",
                error.message ?: error::class.java.simpleName,
            )
        }
    }

    private suspend fun fail(
        threadId: ThreadId,
        turnId: TurnId,
        code: String,
        message: String,
    ): TurnOutcome.Failed {
        eventStore.append(threadId, turnId, TurnFailed(code, message))
        return TurnOutcome.Failed(code, message)
    }

    private fun activityHeading(call: dev.ferro.contracts.ToolCall, modelHeading: String?): String {
        modelHeading?.lineSequence()?.firstOrNull()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it.take(MAX_ACTIVITY_HEADING_LENGTH) }
        return when (call.name) {
        "observe_screen" -> "Inspecting the screen"
        "tap" -> "Tapping a control"
        "swipe" -> "Scrolling the screen"
        "type_text" -> "Entering text"
        "key_action" -> "Using Android navigation"
        "open_app" -> "Opening an app"
        "wait" -> "Waiting for the screen"
        "request_user_input" -> "Asking for your input"
        "request_user_control" -> "Handing control to you"
        "inspect_android_environment" -> "Checking Android state"
        "complete_task" -> "Completing the task"
        else -> "Using ${call.name}"
        }
    }

    private companion object {
        const val MAX_ACTIVITY_HEADING_LENGTH = 80
    }

    private fun TurnDirective.Recapture.cancellationCode(): String = when (reason) {
        RecaptureReason.USER_INPUT -> "SUPERSEDED_BY_USER_INPUT"
        RecaptureReason.RESUMED -> "INVALIDATED_WHILE_PAUSED"
    }

    private fun TurnDirective.Recapture.cancellationMessage(): String = when (reason) {
        RecaptureReason.USER_INPUT -> "Superseded by user input before tool execution"
        RecaptureReason.RESUMED -> "Invalidated while the user controlled the device"
    }
}
