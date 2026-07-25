package dev.ferro.core

import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.UiStateEvidence
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolExecutionPermit(
    val binding: ApprovalBinding,
    val eventGeneration: Long,
)

fun interface ToolAuthorizationEvidenceProvider {
    suspend fun resolve(call: ToolCall): UiStateEvidence

    suspend fun revalidate(call: ToolCall, evidence: UiStateEvidence): Boolean = true
}

class ToolAuthorizationGate(
    private val scope: TaskCapabilityScope,
    private val evidenceProvider: ToolAuthorizationEvidenceProvider,
    private val approvalBroker: ToolApprovalBroker,
    private val toolRouter: ToolRouter,
    private val policy: ToolAuthorizationPolicy = ToolAuthorizationPolicy(),
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val approvalTtlMs: Long = 30_000,
) {
    init {
        require(approvalTtlMs > 0) { "Approval TTL must be positive" }
        val advertisedTools = toolRouter.specs.mapTo(mutableSetOf()) { it.name }
        require(scope.allowedTools.all { it in advertisedTools }) {
            "Capability scope contains tools outside the active catalog"
        }
    }

    suspend fun execute(
        context: ToolExecutionContext,
        call: ToolCall,
        completedActionCount: Int,
    ): ToolResult {
        val evidence = try {
            evidenceProvider.resolve(call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            return failure(call, "STALE_OBSERVATION", error.message ?: "Current Android state is unavailable")
        }
        val subject = AuthorizationSubject(
            threadId = context.threadId,
            turnId = context.turnId,
            call = call,
            scope = scope,
            evidence = evidence,
            completedActionCount = completedActionCount,
            approvalRequestId = approvalBroker.newRequestId(),
            approvalExpiresAtEpochMs = nowEpochMs() + approvalTtlMs,
        )
        var grantedRequest: dev.ferro.contracts.ToolApprovalRequest? = null
        val binding = when (val decision = policy.decide(subject)) {
            AuthorizationDecision.Allow -> bindingFor(subject)
            is AuthorizationDecision.Deny -> return failure(
                call,
                decision.code,
                decision.reason,
                ToolResultStatus.POLICY_DENIED,
            )
            is AuthorizationDecision.RequireApproval -> when (val resolution = approvalBroker.request(decision.request)) {
                ToolApprovalResolution.Granted -> {
                    grantedRequest = decision.request
                    decision.request.binding
                }
                ToolApprovalResolution.Denied -> return failure(
                    call,
                    "USER_DENIED",
                    "The user denied this action",
                    ToolResultStatus.POLICY_DENIED,
                )
                is ToolApprovalResolution.Expired -> return failure(
                    call,
                    if (resolution.reason == ToolApprovalExpiryReason.STEERED ||
                        resolution.reason == ToolApprovalExpiryReason.PAUSED
                    ) {
                        "APPROVAL_INVALIDATED"
                    } else {
                        "APPROVAL_EXPIRED"
                    },
                    "Approval is no longer valid: ${resolution.reason.name.lowercase()}",
                    ToolResultStatus.CANCELLED,
                )
            }
        }
        if (binding.expiresAtEpochMs <= nowEpochMs()) {
            grantedRequest?.let {
                approvalBroker.recordInvalidated(it, ToolApprovalExpiryReason.TIMEOUT)
            }
            return failure(call, "APPROVAL_EXPIRED", "Approval expired before execution", ToolResultStatus.CANCELLED)
        }
        if (!evidenceProvider.revalidate(call, evidence)) {
            grantedRequest?.let {
                approvalBroker.recordInvalidated(it, ToolApprovalExpiryReason.STATE_CHANGED)
            }
            return failure(
                call,
                "APPROVAL_STATE_CHANGED",
                "Android state changed before execution; observe and request approval again",
                ToolResultStatus.CANCELLED,
            )
        }
        return toolRouter.execute(
            context.copy(authorization = ToolExecutionPermit(binding, evidence.eventGeneration)),
            call,
        )
    }

    private fun bindingFor(subject: AuthorizationSubject) = ApprovalBinding(
        threadId = subject.threadId,
        turnId = subject.turnId,
        toolCallId = subject.call.id,
        canonicalArgumentsHash = ToolAuthorizationHashes.arguments(subject.call),
        observationId = subject.evidence.observationId,
        actionablePackage = subject.call.arguments["package_name"]?.toString()?.trim('"')
            ?: subject.evidence.actionablePackage,
        uiStateFingerprint = subject.evidence.uiStateFingerprint,
        capabilityScopeHash = ToolAuthorizationHashes.scope(subject.scope),
        risk = BuiltInToolRiskClassifier().classify(subject.call, subject.evidence).risk,
        expiresAtEpochMs = nowEpochMs() + approvalTtlMs,
    )

    private fun failure(
        call: ToolCall,
        code: String,
        message: String,
        status: ToolResultStatus = ToolResultStatus.RECOVERABLE_FAILURE,
    ) = ToolResult(
        callId = call.id,
        status = status,
        output = buildJsonObject {
            put("code", code)
            put("dispatch", "not_dispatched")
            put("platform_outcome", "rejected")
        },
        message = message,
    )
}

fun unboundAuthorizationEvidence(): UiStateEvidence = UiStateEvidence(
    observationId = "unbound",
    actionablePackage = null,
    uiStateFingerprint = "unbound",
    capturedAtEpochMs = 0,
    eventGeneration = 0,
    settlementStatus = SettlementStatus.SETTLED,
)
