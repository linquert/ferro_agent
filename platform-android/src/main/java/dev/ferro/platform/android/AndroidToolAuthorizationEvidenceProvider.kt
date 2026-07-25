package dev.ferro.platform.android

import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.UiStateEvidence
import dev.ferro.core.ToolAuthorizationEvidenceProvider

internal class AndroidToolAuthorizationEvidenceProvider(
    private val controller: AndroidDeviceController,
) : ToolAuthorizationEvidenceProvider {
    override suspend fun resolve(call: ToolCall): UiStateEvidence =
        if (call.name in UNBOUND_TOOLS) controller.unboundEvidence() else controller.resolveEvidence(call)

    override suspend fun revalidate(call: ToolCall, evidence: UiStateEvidence): Boolean =
        call.name in UNBOUND_TOOLS || controller.revalidateEvidence(call, evidence)

    private companion object {
        val UNBOUND_TOOLS = setOf(
            FerroToolNames.OBSERVE_SCREEN,
            FerroToolNames.REQUEST_USER_INPUT,
            FerroToolNames.REQUEST_USER_CONTROL,
            FerroToolNames.COMPLETE_TASK,
            FerroToolNames.INSPECT_ANDROID_ENVIRONMENT,
            FerroToolNames.OPEN_APP,
            FerroToolNames.WAIT,
        )
    }
}

internal fun AndroidObservation.toUiStateEvidence(
    settlementOverride: SettlementStatus = settlementStatus,
    eventGenerationOverride: Long = eventGeneration,
) = UiStateEvidence(
    observationId = id,
    actionablePackage = foregroundPackage,
    uiStateFingerprint = uiStateFingerprint,
    capturedAtEpochMs = capturedAtEpochMs,
    eventGeneration = eventGenerationOverride,
    settlementStatus = settlementOverride,
)
