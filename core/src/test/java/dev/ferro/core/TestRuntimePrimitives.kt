package dev.ferro.core

import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.IterationId
import dev.ferro.contracts.PolicyProfile
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.UiStateEvidence

class SequentialIdGenerator : IdGenerator {
    private var event = 0
    private var thread = 0
    private var turn = 0
    private var iteration = 0
    private var call = 0

    override fun eventId(): String = "event-${++event}"
    override fun threadId(): ThreadId = ThreadId("thread-${++thread}")
    override fun turnId(): TurnId = TurnId("turn-${++turn}")
    override fun iterationId(): IterationId = IterationId("iteration-${++iteration}")
    override fun toolCallId(): ToolCallId = ToolCallId("call-${++call}")
}

class IncrementingClock(private var now: Long = 0) : FerroClock {
    override fun nowEpochMs(): Long = ++now
}

fun testAuthorizationGate(
    router: ToolRouter,
    eventStore: AgentEventStore,
): ToolAuthorizationGate {
    val evidence = UiStateEvidence(
        observationId = "observation-test",
        actionablePackage = "com.example.target",
        uiStateFingerprint = "fingerprint-test",
        capturedAtEpochMs = 1,
        eventGeneration = 1,
        settlementStatus = SettlementStatus.SETTLED,
    )
    return ToolAuthorizationGate(
        scope = TaskCapabilityScope(
            id = CapabilityScopeId("scope-test"),
            allowedTools = router.specs.mapTo(mutableSetOf()) { it.name },
            allowedPackages = setOf("com.example.target"),
            allowTextEntry = true,
            allowSystemNavigation = true,
            allowAppLaunch = true,
            policyProfile = PolicyProfile.STANDARD,
        ),
        evidenceProvider = object : ToolAuthorizationEvidenceProvider {
            override suspend fun resolve(call: dev.ferro.contracts.ToolCall) = evidence
            override suspend fun revalidate(call: dev.ferro.contracts.ToolCall, evidence: UiStateEvidence) = true
        },
        approvalBroker = ToolApprovalBroker(eventStore),
        toolRouter = router,
        policy = ToolAuthorizationPolicy(
            ToolRiskClassifier { _, _ -> RiskAssessment(ToolRisk.OBSERVATION, "Test action", "Test policy") },
        ),
    )
}
