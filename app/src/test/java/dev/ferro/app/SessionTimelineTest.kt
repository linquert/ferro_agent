package dev.ferro.app

import dev.ferro.contracts.AssistantMessageRecorded
import dev.ferro.contracts.AssistantReasoningRecorded
import dev.ferro.contracts.IterationId
import dev.ferro.contracts.ModelResponseCompleted
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.ModelUsage
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnPaused
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnRecoveryResumed
import dev.ferro.contracts.UserInputRecorded
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTimelineTest {
    @Test
    fun `timeline keeps thinking and assistant chat visibly distinct`() {
        val reasoning = sessionEventText(
            AssistantReasoningRecorded(IterationId("iteration"), "Inspect before tapping"),
        )
        val chat = sessionEventText(AssistantMessageRecorded("I will inspect the screen."))

        assertEquals("Thinking", reasoning.label)
        assertEquals("Inspect before tapping", reasoning.detail)
        assertEquals("Agent", chat.label)
        assertEquals("I will inspect the screen.", chat.detail)
    }

    @Test
    fun `timeline exposes exact token usage and tool arguments`() {
        val usage = sessionEventText(
            ModelResponseCompleted(
                IterationId("iteration"),
                stopReason = ModelStopReason.TOOL_CALLS,
                usage = ModelUsage(90, 10, 100),
            ),
        )
        val tool = sessionEventText(
            ToolCallRecorded(
                IterationId("iteration"),
                ToolCall(
                    ToolCallId("call"),
                    "tap",
                    buildJsonObject {
                        put("x", 0.25)
                        put("y", 0.75)
                    },
                ),
            ),
        )

        assertEquals("90 input + 10 output = 100 tokens", usage.detail)
        assertTrue(tool.detail.contains("\"x\":0.25"))
        assertTrue(tool.detail.contains("\"y\":0.75"))
    }

    @Test
    fun `timeline makes steering and paused state explicit`() {
        val steering = sessionEventText(UserInputRecorded("Use the second option"))
        val paused = sessionEventText(TurnPaused)

        assertEquals("You", steering.label)
        assertEquals("Use the second option", steering.detail)
        assertEquals("Paused", paused.label)
        assertEquals("You have control of the device", paused.detail)
    }

    @Test
    fun `timeline identifies runtime recovery separately from model tool calls`() {
        val runtimeObservation = sessionEventText(
            ToolCallRecorded(
                IterationId("recovery"),
                ToolCall(ToolCallId("call"), "observe_screen", buildJsonObject { }),
                ToolCallOrigin.RUNTIME_RECOVERY,
            ),
        )
        val paused = sessionEventText(TurnRecoveryPaused("Android restarted"))
        val resumed = sessionEventText(TurnRecoveryResumed("observation-new"))

        assertEquals("Recovery observation", runtimeObservation.label)
        assertEquals("Recovery paused", paused.label)
        assertEquals("Recovery resumed", resumed.label)
        assertTrue(resumed.technicalDetail?.contains("observation-new") == true)
    }
}
