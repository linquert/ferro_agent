package dev.ferro.runtime.android

import dev.ferro.core.AgentActivity
import dev.ferro.core.AgentSessionPhase
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.PolicyProfile
import dev.ferro.contracts.TaskCapabilityScopeEstablished

internal enum class OverlayInputMode {
    NONE,
    STEER,
    ANSWER,
    RESUME,
}

internal enum class OverlayControlAction {
    NONE,
    PAUSE,
}

internal data class CompanionOverlayPresentation(
    val visible: Boolean,
    val taskTitle: String,
    val status: String,
    val prompt: String? = null,
    val inputMode: OverlayInputMode = OverlayInputMode.NONE,
    val inputHint: String? = null,
    val submitLabel: String? = null,
    val controlAction: OverlayControlAction = OverlayControlAction.NONE,
    val canStop: Boolean = false,
    val pendingApproval: ToolApprovalRequest? = null,
)

internal object CompanionOverlayPolicy {
    fun from(view: AgentRuntimeView): CompanionOverlayPresentation {
        val rawTaskTitle = view.snapshot.taskTitle
            ?: view.snapshot.recovery?.goal
            ?: "Current task"
        val autonomous = view.events.asReversed().firstNotNullOfOrNull {
            (it.payload as? TaskCapabilityScopeEstablished)?.scope?.policyProfile
        } == PolicyProfile.AUTONOMOUS ||
            view.snapshot.recovery?.capabilityScope?.policyProfile == PolicyProfile.AUTONOMOUS
        val taskTitle = if (autonomous) "Autonomous | $rawTaskTitle" else rawTaskTitle
        val session = view.snapshot.session
        return when (view.snapshot.phase) {
            AgentRuntimePhase.IDLE,
            AgentRuntimePhase.FAILED,
            -> hidden(taskTitle)
            AgentRuntimePhase.STARTING -> CompanionOverlayPresentation(
                visible = true,
                taskTitle = taskTitle,
                status = "Starting",
                canStop = true,
            )
            AgentRuntimePhase.RECOVERY_PAUSED -> CompanionOverlayPresentation(
                visible = true,
                taskTitle = taskTitle,
                status = "Recovery paused - open Ferro to continue",
                canStop = true,
            )
            AgentRuntimePhase.ACTIVE -> when (session?.phase) {
                AgentSessionPhase.THINKING -> activeInput(
                    taskTitle,
                    "Thinking",
                    OverlayInputMode.STEER,
                    "Send a follow-up",
                    "Send",
                    OverlayControlAction.PAUSE,
                )
                AgentSessionPhase.ACTING -> activeInput(
                    taskTitle,
                    (session.activity as? AgentActivity.UsingTool)?.heading ?: "Acting",
                    OverlayInputMode.STEER,
                    "Send a follow-up",
                    "Send",
                    OverlayControlAction.PAUSE,
                )
                AgentSessionPhase.PAUSE_REQUESTED -> CompanionOverlayPresentation(
                    visible = true,
                    taskTitle = taskTitle,
                    status = "Pausing safely",
                    canStop = true,
                )
                AgentSessionPhase.PAUSED -> activeInput(
                    taskTitle,
                    "Paused - you have control",
                    OverlayInputMode.RESUME,
                    "Optional note before resuming",
                    "Resume",
                    OverlayControlAction.NONE,
                )
                AgentSessionPhase.WAITING_FOR_USER -> activeInput(
                    taskTitle,
                    "Waiting for your response",
                    OverlayInputMode.ANSWER,
                    "Your response",
                    "Respond",
                    OverlayControlAction.NONE,
                    session.pendingUserRequest?.displayText(),
                )
                AgentSessionPhase.WAITING_FOR_APPROVAL -> CompanionOverlayPresentation(
                    visible = true,
                    taskTitle = taskTitle,
                    status = "Approval required",
                    prompt = session.pendingToolApproval?.let {
                        "${it.actionSummary}\n${it.binding.risk.name.lowercase()} risk in " +
                            (it.binding.actionablePackage ?: "current Android surface") +
                            "\n${it.reason}"
                    },
                    canStop = true,
                    pendingApproval = session.pendingToolApproval,
                )
                AgentSessionPhase.INTERRUPTING -> CompanionOverlayPresentation(
                    visible = true,
                    taskTitle = taskTitle,
                    status = "Stopping",
                )
                AgentSessionPhase.IDLE,
                AgentSessionPhase.SHUTDOWN,
                null,
                -> CompanionOverlayPresentation(
                    visible = true,
                    taskTitle = taskTitle,
                    status = "Preparing task",
                    canStop = true,
                )
            }
        }
    }

    private fun hidden(taskTitle: String) = CompanionOverlayPresentation(
        visible = false,
        taskTitle = taskTitle,
        status = "Idle",
    )

    private fun activeInput(
        taskTitle: String,
        status: String,
        inputMode: OverlayInputMode,
        inputHint: String,
        submitLabel: String,
        controlAction: OverlayControlAction,
        prompt: String? = null,
    ) = CompanionOverlayPresentation(
        visible = true,
        taskTitle = taskTitle,
        status = status,
        prompt = prompt,
        inputMode = inputMode,
        inputHint = inputHint,
        submitLabel = submitLabel,
        controlAction = controlAction,
        canStop = true,
    )
}

private fun dev.ferro.core.PendingUserRequest.displayText(): String = buildString {
    append(reason ?: prompt)
    suggestedAction?.let {
        append("\nSuggested action: ")
        append(it)
    }
}
