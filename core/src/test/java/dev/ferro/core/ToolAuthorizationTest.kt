package dev.ferro.core

import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.PolicyProfile
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.UiStateEvidence
import dev.ferro.contracts.emptyArguments
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolAuthorizationTest {
    private val policy = ToolAuthorizationPolicy()

    @Test
    fun `autonomous profile allows advertised native actions without package capability or approval gates`() {
        val autonomous = scope(
            allowedPackages = emptySet(),
            allowTextEntry = false,
            policyProfile = PolicyProfile.AUTONOMOUS,
        )

        assertEquals(
            AuthorizationDecision.Allow,
            policy.decide(subject(call("tap", "x" to 0.2, "y" to 0.3), autonomous)),
        )
        assertEquals(
            AuthorizationDecision.Allow,
            policy.decide(subject(call("type_text", "text" to "hello"), autonomous)),
        )
        assertEquals(
            AuthorizationDecision.Allow,
            policy.decide(subject(call("open_app", "package_name" to "com.example.anywhere"), autonomous)),
        )
        assertEquals(
            AuthorizationDecision.Allow,
            policy.decide(subject(call("key_action", "action" to "notifications"), autonomous)),
        )
    }

    @Test
    fun `every built in tool has an explicit non critical risk classification`() {
        val classifier = BuiltInToolRiskClassifier()
        val calls = listOf(
            call("observe_screen"),
            call("wait"),
            call("tap"),
            call("swipe"),
            call("type_text"),
            call("key_action", "action" to "back"),
            call("open_app", "package_name" to TARGET_PACKAGE),
            call("request_user_input"),
            call("request_user_control"),
        )

        calls.forEach { call ->
            assertTrue("${call.name} must be classified", classifier.classify(call, evidence()).risk != ToolRisk.CRITICAL)
        }
        assertEquals(ToolRisk.CRITICAL, classifier.classify(call("future_tool"), evidence()).risk)
    }

    @Test
    fun `blocked package overrides tool and action capabilities`() {
        val scope = scope(
            allowedPackages = setOf(TARGET_PACKAGE),
            deniedPackages = setOf(TARGET_PACKAGE),
            allowTextEntry = true,
        )

        val error = policy.decide(subject(call("type_text", "text" to "hello"), scope))

        assertDeny(error, "PACKAGE_DENIED")
    }

    @Test
    fun `package outside task scope is denied rather than offered for approval`() {
        val decision = policy.decide(
            subject(
                call("tap"),
                scope(allowedPackages = setOf("com.example.other")),
            ),
        )

        assertDeny(decision, "PACKAGE_OUT_OF_SCOPE")
    }

    @Test
    fun `transit package permits observation and navigation but not mutation`() {
        val transitScope = scope(
            allowedPackages = emptySet(),
            transitPackages = setOf(TARGET_PACKAGE),
            allowSystemNavigation = true,
            allowTextEntry = true,
        )

        assertEquals(AuthorizationDecision.Allow, policy.decide(subject(call("wait"), transitScope)))
        assertEquals(
            AuthorizationDecision.Allow,
            policy.decide(subject(call("key_action", "action" to "back"), transitScope)),
        )
        assertDeny(
            policy.decide(subject(call("type_text", "text" to "hello"), transitScope)),
            "TRANSIT_CAPABILITY_DENIED",
        )
    }

    @Test
    fun `capability flags deny consequential tools before risk approval`() {
        assertDeny(
            policy.decide(subject(call("type_text", "text" to "hello"), scope())),
            "TEXT_ENTRY_NOT_ALLOWED",
        )
        assertDeny(
            policy.decide(subject(call("open_app", "package_name" to TARGET_PACKAGE), scope())),
            "APP_LAUNCH_NOT_ALLOWED",
        )
        assertDeny(
            policy.decide(subject(call("key_action", "action" to "back"), scope())),
            "SYSTEM_NAVIGATION_NOT_ALLOWED",
        )
    }

    @Test
    fun `strict profile requires exact approval for medium risk action`() {
        val decision = policy.decide(subject(call("tap", "x" to 0.25, "y" to 0.75), scope()))

        val approval = decision as AuthorizationDecision.RequireApproval
        assertEquals(ToolRisk.MEDIUM, approval.request.binding.risk)
        assertEquals(TARGET_PACKAGE, approval.request.binding.actionablePackage)
        assertEquals("observation-1", approval.request.binding.observationId)
        assertEquals(TurnId("turn"), approval.request.binding.turnId)
        assertEquals(ToolCallId("call"), approval.request.binding.toolCallId)
    }

    @Test
    fun `standard profile allows medium risk but still asks for high risk`() {
        val scope = scope(policyProfile = PolicyProfile.STANDARD, allowTextEntry = true)

        assertEquals(AuthorizationDecision.Allow, policy.decide(subject(call("tap"), scope)))
        assertTrue(
            policy.decide(subject(call("type_text", "text" to "hello"), scope)) is
                AuthorizationDecision.RequireApproval,
        )
    }

    @Test
    fun `unsettled consequential action requires approval warning`() {
        val decision = policy.decide(
            subject(
                call("tap"),
                scope(policyProfile = PolicyProfile.STANDARD),
                evidence(settlementStatus = SettlementStatus.TIMED_OUT),
            ),
        ) as AuthorizationDecision.RequireApproval

        assertTrue(decision.request.reason.contains("did not fully settle"))
    }

    @Test
    fun `native action budget denies before handler authority`() {
        val scope = scope(maximumActions = 2)
        assertDeny(
            policy.decide(subject(call("tap"), scope, completedActionCount = 2)),
            "ACTION_LIMIT_REACHED",
        )
        assertEquals(
            AuthorizationDecision.Allow,
            policy.decide(subject(call("observe_screen"), scope, completedActionCount = 2)),
        )
    }

    @Test
    fun `canonical argument hash ignores object key ordering and preserves value changes`() {
        val first = ToolCall(
            ToolCallId("one"),
            "tap",
            buildJsonObject {
                put("x", 0.25)
                put("nested", buildJsonObject {
                    put("b", 2)
                    put("a", 1)
                })
            },
        )
        val reordered = ToolCall(
            ToolCallId("two"),
            "tap",
            buildJsonObject {
                put("nested", buildJsonObject {
                    put("a", 1)
                    put("b", 2)
                })
                put("x", 0.25)
            },
        )
        val changed = reordered.copy(arguments = buildJsonObject { put("x", 0.5) })

        assertEquals(ToolAuthorizationHashes.arguments(first), ToolAuthorizationHashes.arguments(reordered))
        assertNotEquals(ToolAuthorizationHashes.arguments(first), ToolAuthorizationHashes.arguments(changed))
    }

    @Test
    fun `scope hash ignores set iteration order and changes with authority`() {
        val first = scope(
            allowedPackages = linkedSetOf("com.example.alpha", TARGET_PACKAGE),
            allowedTools = linkedSetOf("tap", "wait"),
        )
        val reordered = scope(
            allowedPackages = linkedSetOf(TARGET_PACKAGE, "com.example.alpha"),
            allowedTools = linkedSetOf("wait", "tap"),
        )
        val expanded = reordered.copy(allowTextEntry = true)

        assertEquals(ToolAuthorizationHashes.scope(first), ToolAuthorizationHashes.scope(reordered))
        assertNotEquals(ToolAuthorizationHashes.scope(first), ToolAuthorizationHashes.scope(expanded))
    }

    private fun subject(
        call: ToolCall,
        scope: TaskCapabilityScope,
        evidence: UiStateEvidence = evidence(),
        completedActionCount: Int = 0,
    ) = AuthorizationSubject(
        threadId = ThreadId("thread"),
        turnId = TurnId("turn"),
        call = call,
        scope = scope,
        evidence = evidence,
        completedActionCount = completedActionCount,
        approvalRequestId = ApprovalRequestId("approval"),
        approvalExpiresAtEpochMs = 10_000,
    )

    private fun scope(
        allowedTools: Set<String> = BUILT_IN_TOOLS,
        allowedPackages: Set<String> = setOf(TARGET_PACKAGE),
        deniedPackages: Set<String> = emptySet(),
        transitPackages: Set<String> = emptySet(),
        allowTextEntry: Boolean = false,
        allowSystemNavigation: Boolean = false,
        allowAppLaunch: Boolean = false,
        maximumActions: Int = 100,
        policyProfile: PolicyProfile = PolicyProfile.STRICT,
    ) = TaskCapabilityScope(
        id = CapabilityScopeId("scope"),
        allowedTools = allowedTools,
        allowedPackages = allowedPackages,
        deniedPackages = deniedPackages,
        transitPackages = transitPackages,
        allowTextEntry = allowTextEntry,
        allowSystemNavigation = allowSystemNavigation,
        allowAppLaunch = allowAppLaunch,
        maximumActions = maximumActions,
        policyProfile = policyProfile,
    )

    private fun evidence(settlementStatus: SettlementStatus = SettlementStatus.SETTLED) = UiStateEvidence(
        observationId = "observation-1",
        actionablePackage = TARGET_PACKAGE,
        uiStateFingerprint = "fingerprint-1",
        capturedAtEpochMs = 1_000,
        eventGeneration = 7,
        settlementStatus = settlementStatus,
    )

    private fun call(name: String, vararg arguments: Pair<String, Any>): ToolCall = ToolCall(
        ToolCallId("call"),
        name,
        buildJsonObject {
            arguments.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Double -> put(key, value)
                    is Int -> put(key, value)
                    else -> error("Unsupported test argument")
                }
            }
        },
    )

    private fun assertDeny(decision: AuthorizationDecision, code: String) {
        assertTrue(decision is AuthorizationDecision.Deny)
        assertEquals(code, (decision as AuthorizationDecision.Deny).code)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.example.target"
        val BUILT_IN_TOOLS = setOf(
            "observe_screen",
            "wait",
            "tap",
            "swipe",
            "type_text",
            "key_action",
            "open_app",
            "request_user_input",
            "request_user_control",
        )
    }
}
