package dev.ferro.contracts

import kotlinx.serialization.Serializable

object FerroToolNames {
    const val OBSERVE_SCREEN = "observe_screen"
    const val TAP = "tap"
    const val SWIPE = "swipe"
    const val TYPE_TEXT = "type_text"
    const val KEY_ACTION = "key_action"
    const val OPEN_APP = "open_app"
    const val WAIT = "wait"
    const val REQUEST_USER_INPUT = "request_user_input"
    const val REQUEST_USER_CONTROL = "request_user_control"
    const val COMPLETE_TASK = "complete_task"
    const val INSPECT_ANDROID_ENVIRONMENT = "inspect_android_environment"

    val all: Set<String> = setOf(
        OBSERVE_SCREEN,
        TAP,
        SWIPE,
        TYPE_TEXT,
        KEY_ACTION,
        OPEN_APP,
        WAIT,
        REQUEST_USER_INPUT,
        REQUEST_USER_CONTROL,
        COMPLETE_TASK,
        INSPECT_ANDROID_ENVIRONMENT,
    )
}

@Serializable
@JvmInline
value class CapabilityScopeId(val value: String) {
    init {
        require(value.isNotBlank()) { "Capability scope ID must not be blank" }
    }
}

@Serializable
@JvmInline
value class ApprovalRequestId(val value: String) {
    init {
        require(value.isNotBlank()) { "Approval request ID must not be blank" }
    }
}

@Serializable
enum class PolicyProfile {
    STRICT,
    STANDARD,
    AUTONOMOUS,
}

@Serializable
data class TaskCapabilityScope(
    val id: CapabilityScopeId,
    val allowedTools: Set<String>,
    val allowedPackages: Set<String>,
    val deniedPackages: Set<String> = emptySet(),
    val transitPackages: Set<String> = emptySet(),
    val allowTextEntry: Boolean = false,
    val allowSystemNavigation: Boolean = false,
    val allowAppLaunch: Boolean = false,
    val maximumActions: Int = 100,
    val policyProfile: PolicyProfile = PolicyProfile.STRICT,
) {
    init {
        require(allowedTools.all(String::isNotBlank)) { "Allowed tool names must not be blank" }
        require(allowedPackages.all(::isPackageName)) { "Allowed package names must be valid" }
        require(deniedPackages.all(::isPackageName)) { "Denied package names must be valid" }
        require(transitPackages.all(::isPackageName)) { "Transit package names must be valid" }
        require(maximumActions > 0) { "Maximum actions must be positive" }
    }

    companion object {
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        private fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)
    }
}

@Serializable
enum class ToolRisk {
    OBSERVATION,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

@Serializable
enum class SettlementStatus {
    SETTLED,
    TIMED_OUT,
    UNAVAILABLE,
}

@Serializable
data class UiStateEvidence(
    val observationId: String,
    val actionablePackage: String?,
    val uiStateFingerprint: String,
    val capturedAtEpochMs: Long,
    val eventGeneration: Long,
    val settlementStatus: SettlementStatus,
) {
    init {
        require(observationId.isNotBlank()) { "Observation ID must not be blank" }
        require(uiStateFingerprint.isNotBlank()) { "UI-state fingerprint must not be blank" }
        require(capturedAtEpochMs >= 0) { "Capture time must not be negative" }
        require(eventGeneration >= 0) { "Event generation must not be negative" }
    }
}

@Serializable
data class ApprovalBinding(
    val threadId: ThreadId,
    val turnId: TurnId,
    val toolCallId: ToolCallId,
    val canonicalArgumentsHash: String,
    val observationId: String,
    val actionablePackage: String?,
    val uiStateFingerprint: String,
    val capabilityScopeHash: String,
    val risk: ToolRisk,
    val expiresAtEpochMs: Long,
) {
    init {
        require(canonicalArgumentsHash.isNotBlank()) { "Arguments hash must not be blank" }
        require(observationId.isNotBlank()) { "Observation ID must not be blank" }
        require(uiStateFingerprint.isNotBlank()) { "UI-state fingerprint must not be blank" }
        require(capabilityScopeHash.isNotBlank()) { "Capability-scope hash must not be blank" }
        require(expiresAtEpochMs >= 0) { "Approval expiry must not be negative" }
    }
}

@Serializable
data class ToolApprovalRequest(
    val requestId: ApprovalRequestId,
    val binding: ApprovalBinding,
    val toolName: String,
    val actionSummary: String,
    val reason: String,
) {
    init {
        require(toolName.isNotBlank()) { "Approval tool name must not be blank" }
        require(actionSummary.isNotBlank()) { "Approval action summary must not be blank" }
        require(reason.isNotBlank()) { "Approval reason must not be blank" }
    }
}
