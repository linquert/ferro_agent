package dev.ferro.core

import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.PolicyProfile
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolApprovalExpired
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.UiStateEvidence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolAuthorizationGateTest {
    @Test
    fun `scope denial returns paired policy result without invoking handler`() = runTest {
        val fixture = Fixture(scope = scope(allowedPackages = setOf("com.example.other")))

        val result = fixture.gate.execute(fixture.context, fixture.tapCall, 0)

        assertEquals(0, fixture.executions)
        assertEquals(fixture.tapCall.id, result.callId)
        assertEquals(ToolResultStatus.POLICY_DENIED, result.status)
        assertEquals("\"PACKAGE_OUT_OF_SCOPE\"", result.output["code"].toString())
    }

    @Test
    fun `exact user grant revalidates and dispatches once with bound permit`() = runTest {
        val fixture = Fixture()
        val execution = async { fixture.gate.execute(fixture.context, fixture.tapCall, 0) }
        runCurrent()
        val pending = requireNotNull(fixture.broker.pending.value)

        assertTrue(fixture.broker.grant(pending.requestId, pending.binding))
        val result = execution.await()

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals(1, fixture.executions)
        assertEquals(1, fixture.revalidations)
        assertNotNull(fixture.receivedPermit)
        assertEquals(ToolAuthorizationHashes.arguments(fixture.tapCall), fixture.receivedPermit?.binding?.canonicalArgumentsHash)
    }

    @Test
    fun `user denial continues with normal policy result and no dispatch`() = runTest {
        val fixture = Fixture()
        val execution = async { fixture.gate.execute(fixture.context, fixture.tapCall, 0) }
        runCurrent()
        val pending = requireNotNull(fixture.broker.pending.value)

        assertTrue(fixture.broker.deny(pending.requestId, pending.binding))
        val result = execution.await()

        assertEquals(0, fixture.executions)
        assertEquals(ToolResultStatus.POLICY_DENIED, result.status)
        assertEquals("\"USER_DENIED\"", result.output["code"].toString())
    }

    @Test
    fun `state change after approval invalidates authority before handler`() = runTest {
        val fixture = Fixture(revalidate = false)
        val execution = async { fixture.gate.execute(fixture.context, fixture.tapCall, 0) }
        runCurrent()
        val pending = requireNotNull(fixture.broker.pending.value)

        fixture.broker.grant(pending.requestId, pending.binding)
        val result = execution.await()

        assertEquals(0, fixture.executions)
        assertEquals(ToolResultStatus.CANCELLED, result.status)
        assertEquals("\"APPROVAL_STATE_CHANGED\"", result.output["code"].toString())
        val expired = fixture.store.readThread(ThreadId("thread"))
            .mapNotNull { it.payload as? ToolApprovalExpired }
            .single()
        assertEquals(ToolApprovalExpiryReason.STATE_CHANGED, expired.reason)
    }

    @Test
    fun `evidence failure becomes stale observation result without policy or handler access`() = runTest {
        val fixture = Fixture(resolveFailure = IllegalStateException("screen changed"))

        val result = fixture.gate.execute(fixture.context, fixture.tapCall, 0)

        assertEquals(0, fixture.executions)
        assertEquals(ToolResultStatus.RECOVERABLE_FAILURE, result.status)
        assertEquals("\"STALE_OBSERVATION\"", result.output["code"].toString())
    }

    private class Fixture(
        scope: TaskCapabilityScope = scope(),
        private val revalidate: Boolean = true,
        private val resolveFailure: Throwable? = null,
    ) {
        val store = InMemoryAgentEventStore(SequentialIdGenerator(), IncrementingClock())
        val broker = ToolApprovalBroker(store) { 1_000L }
        var executions = 0
        var revalidations = 0
        var receivedPermit: ToolExecutionPermit? = null
        val tapCall = ToolCall(
            ToolCallId("call"),
            FerroToolNames.TAP,
            buildJsonObject {
                put("observation_id", "observation")
                put("x", 0.25)
                put("y", 0.75)
            },
        )
        val context = ToolExecutionContext(ThreadId("thread"), TurnId("turn"), "context")
        private val evidence = UiStateEvidence(
            observationId = "observation",
            actionablePackage = "com.example.target",
            uiStateFingerprint = "fingerprint",
            capturedAtEpochMs = 900,
            eventGeneration = 3,
            settlementStatus = SettlementStatus.SETTLED,
        )
        private val handler = object : ToolHandler {
            override val spec = ModelToolSpec(
                FerroToolNames.TAP,
                "Tap",
                setOf("observation_id", "x", "y"),
            )

            override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
                executions++
                receivedPermit = context.authorization
                return ToolResult(call.id, ToolResultStatus.SUCCESS)
            }
        }
        private val router = ToolRouter(ToolRegistry(listOf(handler)))
        val gate = ToolAuthorizationGate(
            scope = scope,
            evidenceProvider = object : ToolAuthorizationEvidenceProvider {
                override suspend fun resolve(call: ToolCall): UiStateEvidence {
                    resolveFailure?.let { throw it }
                    return evidence
                }

                override suspend fun revalidate(call: ToolCall, evidence: UiStateEvidence): Boolean {
                    revalidations++
                    return revalidate
                }
            },
            approvalBroker = broker,
            toolRouter = router,
            nowEpochMs = { 1_000L },
        )
    }

    private companion object {
        fun scope(allowedPackages: Set<String> = setOf("com.example.target")) = TaskCapabilityScope(
            id = CapabilityScopeId("scope"),
            allowedTools = setOf(FerroToolNames.TAP),
            allowedPackages = allowedPackages,
            policyProfile = PolicyProfile.STRICT,
        )
    }
}
