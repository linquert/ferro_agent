package dev.ferro.runtime.android

import dev.ferro.contracts.ApprovalBinding
import dev.ferro.contracts.ApprovalRequestId
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolRisk
import dev.ferro.core.AgentActivity
import dev.ferro.core.AgentSessionPhase
import dev.ferro.core.AgentSessionSnapshot
import dev.ferro.core.PendingUserRequest
import dev.ferro.contracts.UserRequestId
import dev.ferro.contracts.UserRequestKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionOverlayPolicyTest {
    @Test
    fun `idle is hidden while active tool state exposes concise steering controls`() {
        assertFalse(CompanionOverlayPolicy.from(AgentRuntimeView()).visible)

        val presentation = CompanionOverlayPolicy.from(
            activeView(
                AgentSessionPhase.ACTING,
                AgentActivity.UsingTool("Tapping a control"),
            ),
        )

        assertTrue(presentation.visible)
        assertEquals("Change display settings", presentation.taskTitle)
        assertEquals("Tapping a control", presentation.status)
        assertEquals(OverlayInputMode.STEER, presentation.inputMode)
        assertEquals(OverlayControlAction.PAUSE, presentation.controlAction)
        assertTrue(presentation.canStop)
    }

    @Test
    fun `paused state resumes with optional note and no duplicate pause control`() {
        val presentation = CompanionOverlayPolicy.from(activeView(AgentSessionPhase.PAUSED, AgentActivity.Paused))

        assertEquals("Paused - you have control", presentation.status)
        assertEquals(OverlayInputMode.RESUME, presentation.inputMode)
        assertEquals("Resume", presentation.submitLabel)
        assertEquals(OverlayControlAction.NONE, presentation.controlAction)
    }

    @Test
    fun `waiting state carries exact model prompt and response semantics`() {
        val request = PendingUserRequest(
            ThreadId("thread"),
            TurnId("turn"),
            UserRequestId("request"),
            UserRequestKind.INPUT,
            "Which account should I use?",
        )
        val presentation = CompanionOverlayPolicy.from(
            activeView(
                AgentSessionPhase.WAITING_FOR_USER,
                AgentActivity.WaitingForUser(request.prompt),
                request,
            ),
        )

        assertEquals("Which account should I use?", presentation.prompt)
        assertEquals(OverlayInputMode.ANSWER, presentation.inputMode)
        assertEquals("Respond", presentation.submitLabel)
    }

    @Test
    fun `manual handover projects runtime reason and suggested action`() {
        val request = PendingUserRequest(
            ThreadId("thread"),
            TurnId("turn"),
            UserRequestId("request"),
            UserRequestKind.CONTROL,
            "fallback prompt",
            reason = "The authentication screen is unreadable",
            suggestedAction = "Unlock the app and return control",
        )

        val presentation = CompanionOverlayPolicy.from(
            activeView(
                AgentSessionPhase.WAITING_FOR_USER,
                AgentActivity.WaitingForUser(request.prompt),
                request,
            ),
        )

        assertEquals(
            "The authentication screen is unreadable\nSuggested action: Unlock the app and return control",
            presentation.prompt,
        )
    }

    @Test
    fun `recovery state cannot resume without reopening credential UI`() {
        val presentation = CompanionOverlayPolicy.from(
            AgentRuntimeView(
                snapshot = AgentRuntimeSnapshot(
                    phase = AgentRuntimePhase.RECOVERY_PAUSED,
                    taskTitle = "Recovered task",
                ),
            ),
        )

        assertTrue(presentation.visible)
        assertEquals(OverlayInputMode.NONE, presentation.inputMode)
        assertEquals("Recovery paused - open Ferro to continue", presentation.status)
        assertTrue(presentation.canStop)
    }

    @Test
    fun `approval state projects exact runtime request without generic input`() {
        val approval = ToolApprovalRequest(
            ApprovalRequestId("approval"),
            ApprovalBinding(
                ThreadId("thread"),
                TurnId("turn"),
                ToolCallId("call"),
                "arguments",
                "observation",
                "com.example.target",
                "fingerprint",
                "scope",
                ToolRisk.HIGH,
                10_000,
            ),
            "type_text",
            "Enter text",
            "Text entry can change data",
        )
        val presentation = CompanionOverlayPolicy.from(
            AgentRuntimeView(
                snapshot = AgentRuntimeSnapshot(
                    phase = AgentRuntimePhase.ACTIVE,
                    taskTitle = "Complete form",
                    session = AgentSessionSnapshot(
                        threadId = ThreadId("thread"),
                        phase = AgentSessionPhase.WAITING_FOR_APPROVAL,
                        activeTurnId = TurnId("turn"),
                        activity = AgentActivity.WaitingForApproval("Enter text"),
                        pendingToolApproval = approval,
                    ),
                ),
            ),
        )

        assertEquals("Approval required", presentation.status)
        assertEquals(OverlayInputMode.NONE, presentation.inputMode)
        assertEquals(approval, presentation.pendingApproval)
        assertTrue(presentation.prompt.orEmpty().contains("com.example.target"))
        assertTrue(presentation.canStop)
    }

    private fun activeView(
        phase: AgentSessionPhase,
        activity: AgentActivity,
        request: PendingUserRequest? = null,
    ) = AgentRuntimeView(
        snapshot = AgentRuntimeSnapshot(
            phase = AgentRuntimePhase.ACTIVE,
            taskTitle = "Change display settings",
            session = AgentSessionSnapshot(
                threadId = ThreadId("thread"),
                phase = phase,
                activeTurnId = TurnId("turn"),
                activity = activity,
                pendingUserRequest = request,
            ),
        ),
    )
}
