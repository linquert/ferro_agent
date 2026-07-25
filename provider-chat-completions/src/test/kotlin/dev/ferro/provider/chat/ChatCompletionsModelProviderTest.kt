package dev.ferro.provider.chat

import dev.ferro.contracts.ModelStopReason
import dev.ferro.core.ModelProviderException
import dev.ferro.core.ModelProviderFailureKind
import dev.ferro.core.ProviderCredentialSource
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatCompletionsModelProviderTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun `sends bearer credential only in header and decodes terminal stream`() = runTest {
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(
                "data: {\"id\":\"response-1\",\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n",
            ))
        val provider = provider("replacement-secret")

        val response = provider.sample(textRequest())
        val recorded = server.takeRequest()

        assertEquals("Bearer replacement-secret", recorded.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", recorded.path)
        assertFalse(recorded.body.readUtf8().contains("replacement-secret"))
        assertEquals("OK", response.message)
        assertEquals(ModelStopReason.COMPLETE, response.stopReason)
    }

    @Test
    fun `classifies HTTP failures without reflecting response body or credential`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("replacement-secret provider detail"))
        val provider = provider("replacement-secret")

        val error = assertThrows(ModelProviderException::class.java) {
            kotlinx.coroutines.runBlocking { provider.sample(textRequest()) }
        }

        assertEquals(ModelProviderFailureKind.RATE_LIMIT, error.kind)
        assertTrue(error.retryable)
        assertFalse(error.message.orEmpty().contains("replacement-secret"))
        assertFalse(error.message.orEmpty().contains("provider detail"))
    }

    private fun provider(secret: String) = ChatCompletionsModelProvider(
        config = ChatCompletionsProviderConfig(baseUrl = server.url("/v1").toString()),
        credentials = ProviderCredentialSource { secret },
        calls = OkHttpClient(),
    )
}
