package dev.ferro.provider.responses

import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.core.ProviderCredentialSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveResponsesProviderTest {
    @Test
    fun `configured endpoint returns a real terminal response`() = runTest {
        val key = System.getenv("FERRO_LIVE_API_KEY").orEmpty()
        assumeTrue("FERRO_LIVE_API_KEY is required for the live provider test", key.isNotBlank())
        val config = ResponsesProviderConfig(
            baseUrl = System.getenv("FERRO_LIVE_BASE_URL") ?: "https://api.openai.com/v1",
            model = System.getenv("FERRO_LIVE_MODEL") ?: "gpt-5.6-luna",
            reasoningEffort = "none",
        )
        val provider = ResponsesModelProvider(config, ProviderCredentialSource { key })
        val response = provider.sample(
            ModelRequest(
                threadId = ThreadId("live-thread"),
                turnId = TurnId("live-turn"),
                instructions = "Answer the user directly and briefly.",
                input = listOf(ModelMessageInput(ModelMessageRole.USER, "Reply with exactly FERRO_LIVE_OK")),
                tools = emptyList(),
                metadata = emptyMap(),
            ),
        )

        assertEquals(ModelStopReason.COMPLETE, response.stopReason)
        assertEquals("FERRO_LIVE_OK", response.message?.trim())
        assertNotNull(response.providerResponseId)
    }
}
