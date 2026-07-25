package dev.ferro.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAgentInstructionsTest {
    @Test
    fun `prompt teaches recovery grounding handover completion and concise overlay headings`() {
        val prompt = AndroidAgentInstructions.build(remainingIterations = 17, remainingToolCalls = 33)

        assertTrue(prompt.contains("current screen is unexpected"))
        assertTrue(prompt.contains("battery percentage"))
        assertTrue(prompt.contains("inspect_android_environment"))
        assertTrue(prompt.contains("black, blank, or nearly uniform screenshot"))
        assertTrue(prompt.contains("request_user_control"))
        assertTrue(prompt.contains("reason and one concise suggested_action"))
        assertTrue(prompt.contains("complete_task only when"))
        assertTrue(prompt.contains("shown in Ferro's companion overlay"))
        assertTrue(prompt.contains("17 model iterations and 33 tool calls"))
        assertFalse(prompt.contains("com.linkedin"))
        assertFalse(prompt.contains("in.swiggy"))
    }
}
