package dev.ferro.core

import dev.ferro.contracts.TurnId
import kotlinx.coroutines.CompletableDeferred

sealed interface AgentActivity {
    data object Thinking : AgentActivity
    data class UsingTool(val heading: String) : AgentActivity
    data class WaitingForUser(val instruction: String) : AgentActivity
    data class WaitingForApproval(val action: String) : AgentActivity
    data object Paused : AgentActivity
}

enum class TurnCheckpoint {
    BEFORE_MODEL,
    AFTER_MODEL_RESPONSE,
    BEFORE_TOOL,
}

sealed interface TurnDirective {
    data object Proceed : TurnDirective
    data class Recapture(
        val reason: RecaptureReason,
        val inputs: List<String> = emptyList(),
    ) : TurnDirective
}

enum class RecaptureReason {
    USER_INPUT,
    RESUMED,
}

interface TurnCoordinator {
    suspend fun checkpoint(point: TurnCheckpoint): TurnDirective
    suspend fun updateActivity(activity: AgentActivity)

    companion object {
        val UNCONTROLLED = object : TurnCoordinator {
            override suspend fun checkpoint(point: TurnCheckpoint) = TurnDirective.Proceed
            override suspend fun updateActivity(activity: AgentActivity) = Unit
        }
    }
}

internal class TurnInputQueue {
    private val inputs = ArrayDeque<String>()

    fun add(input: String) {
        inputs.add(input)
    }

    fun drain(): List<String> = buildList {
        while (inputs.isNotEmpty()) add(inputs.removeFirst())
    }

    fun clear() {
        inputs.clear()
    }

    val isNotEmpty: Boolean get() = inputs.isNotEmpty()
}

internal data class ActiveTurn(
    val turnId: TurnId,
    val job: kotlinx.coroutines.Job,
    val pendingInput: TurnInputQueue = TurnInputQueue(),
    var pauseRequested: Boolean = false,
    var checkpointWaiter: CompletableDeferred<TurnDirective>? = null,
    var activity: AgentActivity = AgentActivity.Thinking,
)
