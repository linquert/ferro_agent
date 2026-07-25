package dev.ferro.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
@JvmInline
value class ThreadId(val value: String)

@Serializable
@JvmInline
value class TurnId(val value: String)

@Serializable
@JvmInline
value class IterationId(val value: String)

@Serializable
@JvmInline
value class ToolCallId(val value: String)

@Serializable
enum class TurnStatus {
    QUEUED,
    RUNNING,
    WAITING_FOR_APPROVAL,
    WAITING_FOR_USER,
    CANCELLING,
    COMPLETED,
    FAILED,
    ABORTED,
}

@Serializable
data class AgentEventEnvelope(
    val eventId: String,
    val threadId: ThreadId,
    val turnId: TurnId? = null,
    val sequence: Long,
    val timestampEpochMs: Long,
    val payload: AgentEventPayload,
)

@Serializable
sealed interface AgentEventPayload

@Serializable
@SerialName("thread_started")
data class ThreadStarted(
    val title: String,
) : AgentEventPayload

@Serializable
@SerialName("turn_started")
data class TurnStarted(
    val goal: String,
) : AgentEventPayload

@Serializable
@SerialName("task_capability_scope_established")
data class TaskCapabilityScopeEstablished(
    val scope: TaskCapabilityScope,
    val scopeHash: String,
) : AgentEventPayload {
    init {
        require(scopeHash.isNotBlank()) { "Capability scope hash must not be blank" }
    }
}

@Serializable
@SerialName("model_iteration_started")
data class ModelIterationStarted(
    val iterationId: IterationId,
    val contextFingerprint: String,
    val contextSummary: ModelContextSummary? = null,
) : AgentEventPayload

@Serializable
data class ModelContextSummary(
    val inputItems: Int,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolCalls: Int,
    val toolResults: Int,
    val images: Int,
    val advertisedTools: Int,
)

@Serializable
@SerialName("assistant_message")
data class AssistantMessageRecorded(
    val text: String,
) : AgentEventPayload

@Serializable
@SerialName("assistant_reasoning")
data class AssistantReasoningRecorded(
    val iterationId: IterationId,
    val text: String,
) : AgentEventPayload

@Serializable
@SerialName("model_response_completed")
data class ModelResponseCompleted(
    val iterationId: IterationId,
    val providerResponseId: String? = null,
    val stopReason: ModelStopReason,
    val usage: ModelUsage? = null,
) : AgentEventPayload

@Serializable
enum class ToolCallOrigin {
    MODEL,
    RUNTIME_RECOVERY,
}

@Serializable
@SerialName("tool_call")
data class ToolCallRecorded(
    val iterationId: IterationId,
    val call: ToolCall,
    val origin: ToolCallOrigin = ToolCallOrigin.MODEL,
) : AgentEventPayload

@Serializable
@SerialName("tool_result")
data class ToolResultRecorded(
    val iterationId: IterationId,
    val result: ToolResult,
) : AgentEventPayload

@Serializable
@SerialName("turn_completed")
data class TurnCompleted(
    val finalMessage: String,
) : AgentEventPayload

@Serializable
@SerialName("turn_failed")
data class TurnFailed(
    val code: String,
    val message: String,
) : AgentEventPayload

@Serializable
@SerialName("turn_cancelled")
data class TurnCancelled(
    val reason: String,
) : AgentEventPayload

@Serializable
@SerialName("user_input")
data class UserInputRecorded(
    val text: String,
) : AgentEventPayload {
    init {
        require(text.isNotBlank()) { "User input must not be blank" }
    }
}

@Serializable
enum class UserRequestKind {
    INPUT,
    CONTROL,
}

@Serializable
@SerialName("user_control_requested")
data class UserRequestOpened(
    val requestId: UserRequestId,
    val kind: UserRequestKind,
    val prompt: String,
    val reason: String? = null,
    val suggestedAction: String? = null,
) : AgentEventPayload

@Serializable
@SerialName("user_request_answered")
data class UserRequestAnswered(
    val requestId: UserRequestId,
) : AgentEventPayload

@Serializable
@SerialName("tool_approval_requested")
data class ToolApprovalRequested(
    val request: ToolApprovalRequest,
) : AgentEventPayload

@Serializable
@SerialName("tool_approval_granted")
data class ToolApprovalGranted(
    val requestId: ApprovalRequestId,
    val binding: ApprovalBinding,
) : AgentEventPayload

@Serializable
@SerialName("tool_approval_denied")
data class ToolApprovalDenied(
    val requestId: ApprovalRequestId,
    val binding: ApprovalBinding,
) : AgentEventPayload

@Serializable
enum class ToolApprovalExpiryReason {
    TIMEOUT,
    PAUSED,
    STEERED,
    TURN_FINISHED,
    INTERRUPTED,
    PROCESS_RESTART,
    STATE_CHANGED,
}

@Serializable
@SerialName("tool_approval_expired")
data class ToolApprovalExpired(
    val requestId: ApprovalRequestId,
    val binding: ApprovalBinding,
    val reason: ToolApprovalExpiryReason,
) : AgentEventPayload

@Serializable
@SerialName("turn_pause_requested")
data object TurnPauseRequested : AgentEventPayload

@Serializable
@SerialName("turn_paused")
data object TurnPaused : AgentEventPayload

@Serializable
@SerialName("turn_resumed")
data object TurnResumed : AgentEventPayload

@Serializable
@SerialName("turn_recovery_paused")
data class TurnRecoveryPaused(
    val reason: String,
) : AgentEventPayload

@Serializable
@SerialName("turn_recovery_resumed")
data class TurnRecoveryResumed(
    val observationId: String,
) : AgentEventPayload

@Serializable
data class ToolCall(
    val id: ToolCallId,
    val name: String,
    val arguments: JsonObject,
)

@Serializable
enum class ToolResultStatus {
    SUCCESS,
    RECOVERABLE_FAILURE,
    POLICY_DENIED,
    APPROVAL_REQUIRED,
    CANCELLED,
    FATAL_FAILURE,
    TASK_COMPLETED,
}

@Serializable
enum class ToolAttachmentKind {
    IMAGE,
}

@Serializable
data class ToolAttachmentRef(
    val kind: ToolAttachmentKind,
    val uri: String,
    val mediaType: String,
) {
    init {
        require(uri.isNotBlank()) { "Attachment URI must not be blank" }
        require(mediaType.isNotBlank()) { "Attachment media type must not be blank" }
    }
}

@Serializable
data class ToolResult(
    val callId: ToolCallId,
    val status: ToolResultStatus,
    val output: JsonObject = JsonObject(emptyMap()),
    val message: String? = null,
    val attachments: List<ToolAttachmentRef> = emptyList(),
)
