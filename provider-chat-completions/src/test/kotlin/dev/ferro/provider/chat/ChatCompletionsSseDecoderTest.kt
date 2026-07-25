package dev.ferro.provider.chat

import dev.ferro.contracts.ModelStopReason
import dev.ferro.core.ModelProviderException
import dev.ferro.core.ModelProviderFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatCompletionsSseDecoderTest {
    @Test
    fun `assembles split chat reasoning and usage into separate response fields`() {
        val decoder = ChatCompletionsSseDecoder()
        decoder.accept(chunk("resp-1", "{\"content\":\"FERRO_\",\"reasoning_content\":\"private thought\"}"))
        decoder.accept(chunk("resp-1", "{\"content\":\"OK\"}", "stop"))
        decoder.accept("""{"id":"resp-1","choices":[],"usage":{"prompt_tokens":8,"completion_tokens":2,"total_tokens":10}}""")
        decoder.accept("[DONE]")

        val response = decoder.finish()

        assertEquals("FERRO_OK", response.message)
        assertEquals("private thought", response.reasoning)
        assertEquals(ModelStopReason.COMPLETE, response.stopReason)
        assertEquals(10L, response.usage?.totalTokens)
        assertTrue(response.message?.contains("private thought") == false)
    }

    @Test
    fun `assembles fragmented indexed tool calls without inventing ids`() {
        val decoder = ChatCompletionsSseDecoder()
        decoder.accept(chunk("resp-2", """{"tool_calls":[{"index":0,"id":"call-7","type":"function","function":{"name":"tap","arguments":"{\"x\":"}}]}"""))
        decoder.accept(chunk("resp-2", """{"tool_calls":[{"index":0,"function":{"arguments":"42}"}}]}""", "tool_calls"))
        decoder.accept("[DONE]")

        val response = decoder.finish()

        assertEquals(ModelStopReason.TOOL_CALLS, response.stopReason)
        assertEquals("call-7", response.toolCalls.single().id.value)
        assertEquals("42", response.toolCalls.single().arguments.getValue("x").toString())
        assertNull(response.message)
    }

    @Test
    fun `rejects truncated streams and malformed completed arguments`() {
        val truncated = ChatCompletionsSseDecoder()
        truncated.accept(chunk("resp", "{\"content\":\"partial\"}", "stop"))
        val truncatedError = assertThrows(ModelProviderException::class.java) { truncated.finish() }
        assertEquals(ModelProviderFailureKind.STREAM_PROTOCOL, truncatedError.kind)
        assertTrue(truncatedError.retryable)

        val malformed = ChatCompletionsSseDecoder()
        malformed.accept(chunk("resp", """{"tool_calls":[{"index":0,"id":"call","function":{"name":"tap","arguments":"{"}}]}""", "tool_calls"))
        malformed.accept("[DONE]")
        val malformedError = assertThrows(ModelProviderException::class.java) { malformed.finish() }
        assertEquals(ModelProviderFailureKind.STREAM_PROTOCOL, malformedError.kind)
        assertTrue(!malformedError.retryable)
    }

    @Test
    fun `classifies streamed authentication errors`() {
        val decoder = ChatCompletionsSseDecoder()
        val error = assertThrows(ModelProviderException::class.java) {
            decoder.accept("""{"error":{"code":"invalid_api_key","message":"Unauthorized"}}""")
        }
        assertEquals(ModelProviderFailureKind.AUTHENTICATION, error.kind)
        assertTrue(!error.retryable)
    }

    private fun chunk(id: String, delta: String, finishReason: String? = null): String =
        """{"id":"$id","choices":[{"index":0,"delta":$delta,"finish_reason":${finishReason?.let { "\"$it\"" } ?: "null"}}]}"""
}
