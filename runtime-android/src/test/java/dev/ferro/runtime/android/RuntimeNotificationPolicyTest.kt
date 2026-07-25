package dev.ferro.runtime.android

import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.core.AgentActivity
import dev.ferro.core.AgentSessionPhase
import dev.ferro.core.AgentSessionSnapshot
import dev.ferro.core.TurnOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeNotificationPolicyTest {
    @Test
    fun `active tool work exposes current heading with pause and stop`() {
        val notification = RuntimeNotificationPolicy.from(
            activeView(
                AgentSessionPhase.ACTING,
                AgentActivity.UsingTool("Inspecting the screen"),
            ),
        )

        assertEquals("Inspecting the screen", notification.text)
        assertEquals(
            listOf(RuntimeNotificationAction.PAUSE, RuntimeNotificationAction.STOP),
            notification.actions,
        )
        assertTrue(notification.ongoing)
    }

    @Test
    fun `paused work exposes resume and stop but never pause`() {
        val notification = RuntimeNotificationPolicy.from(
            activeView(AgentSessionPhase.PAUSED, AgentActivity.Paused),
        )

        assertEquals("Paused - you have control", notification.text)
        assertEquals(
            listOf(RuntimeNotificationAction.RESUME, RuntimeNotificationAction.STOP),
            notification.actions,
        )
    }

    @Test
    fun `waiting for user does not offer unsafe resume`() {
        val notification = RuntimeNotificationPolicy.from(
            activeView(
                AgentSessionPhase.WAITING_FOR_USER,
                AgentActivity.WaitingForUser("Choose an account"),
            ),
        )

        assertEquals("Waiting for your response", notification.text)
        assertEquals(listOf(RuntimeNotificationAction.STOP), notification.actions)
    }

    @Test
    fun `completed runtime is removable and has no mutation actions`() {
        val notification = RuntimeNotificationPolicy.from(
            AgentRuntimeView(
                AgentRuntimeSnapshot(
                    AgentRuntimePhase.IDLE,
                    AgentSessionSnapshot(
                        ThreadId("thread"),
                        AgentSessionPhase.IDLE,
                        lastOutcome = TurnOutcome.Completed("Done"),
                    ),
                ),
            ),
        )

        assertEquals("Task completed", notification.text)
        assertTrue(notification.actions.isEmpty())
        assertFalse(notification.ongoing)
    }

    @Test
    fun `recovered task is visibly paused and can only be stopped from notification`() {
        val notification = RuntimeNotificationPolicy.from(
            AgentRuntimeView(
                AgentRuntimeSnapshot(
                    phase = AgentRuntimePhase.RECOVERY_PAUSED,
                    recovery = RecoveryRuntimeSnapshot(
                        "session",
                        ThreadId("thread"),
                        TurnId("turn"),
                        "Task",
                        RuntimeProviderKind.CHAT_COMPLETIONS,
                        "https://example.test/v1",
                        "model",
                        testCapabilityScope(),
                        dev.ferro.core.ToolAuthorizationHashes.scope(testCapabilityScope()),
                    ),
                ),
            ),
        )

        assertEquals("Paused after Android restarted Ferro", notification.text)
        assertEquals(listOf(RuntimeNotificationAction.STOP), notification.actions)
        assertTrue(notification.ongoing)
    }

    private fun activeView(phase: AgentSessionPhase, activity: AgentActivity) = AgentRuntimeView(
        AgentRuntimeSnapshot(
            AgentRuntimePhase.ACTIVE,
            AgentSessionSnapshot(
                threadId = ThreadId("thread"),
                phase = phase,
                activeTurnId = TurnId("turn"),
                activity = activity,
            ),
        ),
    )
}
