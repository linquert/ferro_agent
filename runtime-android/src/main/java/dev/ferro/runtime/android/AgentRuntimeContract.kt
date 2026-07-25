package dev.ferro.runtime.android

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.core.AgentSessionSnapshot
import kotlinx.serialization.Serializable

@Serializable
enum class RuntimeProviderKind {
    CHAT_COMPLETIONS,
    RESPONSES,
}

data class StartAgentRequest(
    val goal: String,
    val providerKind: RuntimeProviderKind,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val capabilityScope: TaskCapabilityScope,
) {
    init {
        require(goal.isNotBlank()) { "Goal must not be blank" }
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank" }
        require(model.isNotBlank()) { "Provider model must not be blank" }
        require(apiKey.isNotBlank()) { "Provider credential must not be blank" }
        require(capabilityScope.allowedTools.isNotEmpty()) { "Task capability scope must allow at least one tool" }
    }
}

enum class AgentRuntimePhase {
    IDLE,
    STARTING,
    ACTIVE,
    RECOVERY_PAUSED,
    FAILED,
}

data class AgentRuntimeSnapshot(
    val phase: AgentRuntimePhase = AgentRuntimePhase.IDLE,
    val session: AgentSessionSnapshot? = null,
    val recovery: RecoveryRuntimeSnapshot? = null,
    val taskTitle: String? = null,
    val errorMessage: String? = null,
)

data class RecoveryRuntimeSnapshot(
    val sessionId: String,
    val threadId: dev.ferro.contracts.ThreadId,
    val turnId: dev.ferro.contracts.TurnId,
    val goal: String,
    val providerKind: RuntimeProviderKind,
    val baseUrl: String,
    val model: String,
    val capabilityScope: TaskCapabilityScope,
    val capabilityScopeHash: String,
)

data class AgentRuntimeView(
    val snapshot: AgentRuntimeSnapshot = AgentRuntimeSnapshot(),
    val events: List<AgentEventEnvelope> = emptyList(),
)
