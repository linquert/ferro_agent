package dev.ferro.runtime.android

import dev.ferro.core.AgentActivity
import dev.ferro.core.AgentSessionPhase
import dev.ferro.core.TurnOutcome

internal enum class RuntimeNotificationAction {
    PAUSE,
    RESUME,
    STOP,
}

internal data class RuntimeNotificationState(
    val text: String,
    val ongoing: Boolean,
    val actions: List<RuntimeNotificationAction>,
)

internal object RuntimeNotificationPolicy {
    fun from(view: AgentRuntimeView): RuntimeNotificationState {
        val session = view.snapshot.session
        return when (view.snapshot.phase) {
            AgentRuntimePhase.STARTING -> RuntimeNotificationState("Starting task", true, listOf(RuntimeNotificationAction.STOP))
            AgentRuntimePhase.FAILED -> RuntimeNotificationState(
                view.snapshot.errorMessage?.let { "Failed: $it" } ?: "Task failed",
                false,
                emptyList(),
            )
            AgentRuntimePhase.RECOVERY_PAUSED -> RuntimeNotificationState(
                "Paused after Android restarted Ferro",
                true,
                listOf(RuntimeNotificationAction.STOP),
            )
            AgentRuntimePhase.IDLE -> RuntimeNotificationState(
                text = when (val outcome = session?.lastOutcome) {
                    is TurnOutcome.Completed -> "Task completed"
                    is TurnOutcome.Cancelled -> "Task stopped"
                    is TurnOutcome.Failed -> "Failed: ${outcome.code}"
                    null -> "Ready"
                },
                ongoing = false,
                actions = emptyList(),
            )
            AgentRuntimePhase.ACTIVE -> when (session?.phase) {
                AgentSessionPhase.THINKING -> active("Thinking")
                AgentSessionPhase.ACTING -> active(
                    (session.activity as? AgentActivity.UsingTool)?.heading ?: "Acting",
                )
                AgentSessionPhase.PAUSE_REQUESTED -> RuntimeNotificationState(
                    "Pausing safely",
                    true,
                    listOf(RuntimeNotificationAction.STOP),
                )
                AgentSessionPhase.PAUSED -> RuntimeNotificationState(
                    "Paused - you have control",
                    true,
                    listOf(RuntimeNotificationAction.RESUME, RuntimeNotificationAction.STOP),
                )
                AgentSessionPhase.WAITING_FOR_USER -> RuntimeNotificationState(
                    "Waiting for your response",
                    true,
                    listOf(RuntimeNotificationAction.STOP),
                )
                AgentSessionPhase.WAITING_FOR_APPROVAL -> RuntimeNotificationState(
                    "Approval required",
                    true,
                    listOf(RuntimeNotificationAction.STOP),
                )
                AgentSessionPhase.INTERRUPTING -> RuntimeNotificationState("Stopping", true, emptyList())
                AgentSessionPhase.IDLE, AgentSessionPhase.SHUTDOWN, null -> RuntimeNotificationState(
                    "Preparing task",
                    true,
                    listOf(RuntimeNotificationAction.STOP),
                )
            }
        }
    }

    private fun active(text: String) = RuntimeNotificationState(
        text,
        true,
        listOf(RuntimeNotificationAction.PAUSE, RuntimeNotificationAction.STOP),
    )
}
