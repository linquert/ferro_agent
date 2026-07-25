package dev.ferro.core

import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.PolicyProfile
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.UiStateEvidence
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AuthorizationSubject(
    val threadId: ThreadId,
    val turnId: TurnId,
    val call: ToolCall,
    val scope: TaskCapabilityScope,
    val evidence: UiStateEvidence,
    val completedActionCount: Int,
    val approvalRequestId: ApprovalRequestId,
    val approvalExpiresAtEpochMs: Long,
) {
    init {
        require(completedActionCount >= 0) { "Completed action count must not be negative" }
    }
}

data class RiskAssessment(
    val risk: ToolRisk,
    val actionSummary: String,
    val reason: String,
)

sealed interface AuthorizationDecision {
    data object Allow : AuthorizationDecision

    data class Deny(
        val code: String,
        val reason: String,
    ) : AuthorizationDecision

    data class RequireApproval(
        val request: ToolApprovalRequest,
    ) : AuthorizationDecision
}

fun interface ToolRiskClassifier {
    fun classify(call: ToolCall, evidence: UiStateEvidence): RiskAssessment
}

class BuiltInToolRiskClassifier : ToolRiskClassifier {
    override fun classify(call: ToolCall, evidence: UiStateEvidence): RiskAssessment = when (call.name) {
        "observe_screen" -> assessment(ToolRisk.OBSERVATION, "Capture the current screen")
        "wait" -> assessment(ToolRisk.OBSERVATION, "Wait and inspect the screen")
        "swipe" -> assessment(ToolRisk.LOW, "Swipe in ${target(evidence)}")
        "tap" -> assessment(ToolRisk.MEDIUM, "Tap a control in ${target(evidence)}")
        "key_action" -> classifyKeyAction(call, evidence)
        "open_app" -> assessment(
            ToolRisk.MEDIUM,
            "Open ${call.stringArgument("package_name") ?: "another app"}",
        )
        "type_text" -> assessment(ToolRisk.HIGH, "Enter text in ${target(evidence)}")
        "request_user_input" -> assessment(ToolRisk.LOW, "Ask you for information")
        "request_user_control" -> assessment(ToolRisk.LOW, "Ask you to take control")
        "complete_task" -> assessment(ToolRisk.LOW, "Report that the task is complete")
        "inspect_android_environment" -> assessment(ToolRisk.OBSERVATION, "Inspect Android system facts")
        else -> assessment(ToolRisk.CRITICAL, "Run unknown tool ${call.name}")
    }

    private fun classifyKeyAction(call: ToolCall, evidence: UiStateEvidence): RiskAssessment =
        when (call.stringArgument("action")) {
            "back" -> assessment(ToolRisk.LOW, "Go back in ${target(evidence)}")
            "home", "recents" -> assessment(ToolRisk.MEDIUM, "Leave ${target(evidence)}")
            "notifications" -> assessment(ToolRisk.HIGH, "Open Android notifications")
            else -> assessment(ToolRisk.CRITICAL, "Run an unknown Android navigation action")
        }

    private fun assessment(risk: ToolRisk, summary: String) = RiskAssessment(
        risk = risk,
        actionSummary = summary,
        reason = when (risk) {
            ToolRisk.OBSERVATION -> "This action only observes current state"
            ToolRisk.LOW -> "This action has limited device impact"
            ToolRisk.MEDIUM -> "This action can navigate or activate visible UI"
            ToolRisk.HIGH -> "This action can expose data or make a consequential change"
            ToolRisk.CRITICAL -> "This action is unknown or outside the supported safety model"
        },
    )

    private fun target(evidence: UiStateEvidence): String = evidence.actionablePackage ?: "the current app"
}

class ToolAuthorizationPolicy(
    private val classifier: ToolRiskClassifier = BuiltInToolRiskClassifier(),
) {
    fun decide(subject: AuthorizationSubject): AuthorizationDecision {
        val call = subject.call
        val scope = subject.scope
        val evidence = subject.evidence
        if (call.name !in scope.allowedTools) {
            return deny("TOOL_NOT_ALLOWED", "${call.name} is outside this task's capability scope")
        }
        if (subject.completedActionCount >= scope.maximumActions && call.isNativeAction()) {
            return deny("ACTION_LIMIT_REACHED", "This task has reached its action limit")
        }
        if (scope.policyProfile == PolicyProfile.AUTONOMOUS) {
            return AuthorizationDecision.Allow
        }
        val targetPackage = call.explicitTargetPackage() ?: evidence.actionablePackage
        packageDenial(scope, targetPackage, call)?.let { return it }
        capabilityDenial(scope, call)?.let { return it }

        val assessment = classifier.classify(call, evidence)
        if (assessment.risk == ToolRisk.CRITICAL) {
            return deny("UNCLASSIFIED_ACTION", assessment.reason)
        }
        val requiresApproval = when (scope.policyProfile) {
            PolicyProfile.STRICT -> assessment.risk >= ToolRisk.MEDIUM
            PolicyProfile.STANDARD -> assessment.risk >= ToolRisk.HIGH
            PolicyProfile.AUTONOMOUS -> false
        } || (evidence.settlementStatus != SettlementStatus.SETTLED && assessment.risk >= ToolRisk.MEDIUM)
        if (!requiresApproval) return AuthorizationDecision.Allow

        val binding = ApprovalBinding(
            threadId = subject.threadId,
            turnId = subject.turnId,
            toolCallId = call.id,
            canonicalArgumentsHash = ToolAuthorizationHashes.arguments(call),
            observationId = evidence.observationId,
            actionablePackage = targetPackage,
            uiStateFingerprint = evidence.uiStateFingerprint,
            capabilityScopeHash = ToolAuthorizationHashes.scope(scope),
            risk = assessment.risk,
            expiresAtEpochMs = subject.approvalExpiresAtEpochMs,
        )
        return AuthorizationDecision.RequireApproval(
            ToolApprovalRequest(
                requestId = subject.approvalRequestId,
                binding = binding,
                toolName = call.name,
                actionSummary = assessment.actionSummary,
                reason = if (evidence.settlementStatus == SettlementStatus.SETTLED) {
                    assessment.reason
                } else {
                    "The Android UI did not fully settle. ${assessment.reason}"
                },
            ),
        )
    }

    private fun packageDenial(
        scope: TaskCapabilityScope,
        targetPackage: String?,
        call: ToolCall,
    ): AuthorizationDecision.Deny? {
        if (!call.requiresAndroidTarget()) return null
        if (targetPackage == null) return deny("TARGET_PACKAGE_UNKNOWN", "The actionable Android package is unknown")
        if (targetPackage in scope.deniedPackages) {
            return deny("PACKAGE_DENIED", "$targetPackage is explicitly denied")
        }
        if (targetPackage !in scope.allowedPackages && targetPackage !in scope.transitPackages) {
            return deny("PACKAGE_OUT_OF_SCOPE", "$targetPackage is outside this task's package scope")
        }
        if (targetPackage in scope.transitPackages && !call.allowedOnTransitPackage()) {
            return deny("TRANSIT_CAPABILITY_DENIED", "${call.name} is not allowed on transit package $targetPackage")
        }
        return null
    }

    private fun capabilityDenial(scope: TaskCapabilityScope, call: ToolCall): AuthorizationDecision.Deny? = when {
        call.name == "type_text" && !scope.allowTextEntry ->
            deny("TEXT_ENTRY_NOT_ALLOWED", "Text entry is disabled for this task")
        call.name == "open_app" && !scope.allowAppLaunch ->
            deny("APP_LAUNCH_NOT_ALLOWED", "App launch is disabled for this task")
        call.name == "key_action" && !scope.allowSystemNavigation ->
            deny("SYSTEM_NAVIGATION_NOT_ALLOWED", "Android system navigation is disabled for this task")
        else -> null
    }

    private fun deny(code: String, reason: String) = AuthorizationDecision.Deny(code, reason)
}

object ToolAuthorizationHashes {
    private val json = Json { encodeDefaults = true }

    fun arguments(call: ToolCall): String = sha256(json.encodeToString(canonical(call.arguments)))

    fun scope(scope: TaskCapabilityScope): String = sha256(
        json.encodeToString(
            buildJsonObject {
                put("id", scope.id.value)
                put("allowed_tools", sortedArray(scope.allowedTools))
                put("allowed_packages", sortedArray(scope.allowedPackages))
                put("denied_packages", sortedArray(scope.deniedPackages))
                put("transit_packages", sortedArray(scope.transitPackages))
                put("allow_text_entry", scope.allowTextEntry)
                put("allow_system_navigation", scope.allowSystemNavigation)
                put("allow_app_launch", scope.allowAppLaunch)
                put("maximum_actions", scope.maximumActions)
                put("policy_profile", scope.policyProfile.name)
            },
        ),
    )

    private fun canonical(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.sortedBy { it.key }.associate { it.key to canonical(it.value) })
        is JsonArray -> JsonArray(element.map(::canonical))
        is JsonPrimitive -> element
    }

    private fun sortedArray(values: Set<String>) = buildJsonArray {
        values.sorted().forEach { add(JsonPrimitive(it)) }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

private fun ToolCall.stringArgument(name: String): String? =
    arguments[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

private fun ToolCall.explicitTargetPackage(): String? =
    if (name == "open_app") stringArgument("package_name") else null

private fun ToolCall.requiresAndroidTarget(): Boolean = name in setOf(
    "tap",
    "swipe",
    "type_text",
    "key_action",
    "open_app",
    "wait",
)

internal fun ToolCall.isNativeAction(): Boolean = name in setOf(
    "tap",
    "swipe",
    "type_text",
    "key_action",
    "open_app",
)

private fun ToolCall.allowedOnTransitPackage(): Boolean = when (name) {
    "observe_screen", "wait" -> true
    "key_action" -> stringArgument("action") in setOf("back", "home", "recents")
    else -> false
}
