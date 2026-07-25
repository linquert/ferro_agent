package dev.ferro.contracts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class SubmissionId(val value: String)

@Serializable
sealed interface AgentOperation

@Serializable
@SerialName("start_turn")
data class StartTurn(
    val turnId: TurnId,
    val goal: String,
) : AgentOperation {
    init {
        require(goal.isNotBlank()) { "Goal must not be blank" }
    }
}

@Serializable
@SerialName("interrupt_turn")
data class InterruptTurn(
    val expectedTurnId: TurnId,
) : AgentOperation

@Serializable
@SerialName("steer_turn")
data class SteerTurn(
    val expectedTurnId: TurnId,
    val input: String,
) : AgentOperation {
    init {
        require(input.isNotBlank()) { "Steering input must not be blank" }
    }
}

@Serializable
@SerialName("pause_turn")
data class PauseTurn(
    val expectedTurnId: TurnId,
) : AgentOperation

@Serializable
@SerialName("resume_turn")
data class ResumeTurn(
    val expectedTurnId: TurnId,
) : AgentOperation

@Serializable
@JvmInline
value class UserRequestId(val value: String)

@Serializable
@SerialName("answer_user_request")
data class AnswerUserRequest(
    val requestId: UserRequestId,
    val response: String,
) : AgentOperation

@Serializable
@SerialName("grant_tool_approval")
data class GrantToolApproval(
    val requestId: ApprovalRequestId,
    val expectedBinding: ApprovalBinding,
) : AgentOperation

@Serializable
@SerialName("deny_tool_approval")
data class DenyToolApproval(
    val requestId: ApprovalRequestId,
    val expectedBinding: ApprovalBinding,
) : AgentOperation

@Serializable
@SerialName("shutdown_session")
data object ShutdownSession : AgentOperation

@Serializable
data class AgentSubmission(
    val id: SubmissionId,
    val operation: AgentOperation,
)
