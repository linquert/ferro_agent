package dev.ferro.provider.responses

import dev.ferro.contracts.ModelStopReason
import dev.ferro.core.ModelProviderException
import dev.ferro.core.ModelProviderFailureKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponsesSseDecoderTest {
    @Test
    fun `completed stream returns final text id and usage`() {
        val decoder = ResponsesSseDecoder()
        decoder.accept("""
            {"type":"response.output_text.delta","delta":"Hel"}
        """.trimIndent())
        decoder.accept("""
            {"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Hello"}]}}
        """.trimIndent())
        decoder.accept("""
            {"type":"response.completed","response":{"id":"resp-1","usage":{"input_tokens":12,"output_tokens":3,"total_tokens":15}}}
        """.trimIndent())

        val response = decoder.finish()

        assertEquals("resp-1", response.providerResponseId)
        assertEquals("Hello", response.message)
        assertEquals(ModelStopReason.COMPLETE, response.stopReason)
        assertEquals(12L, response.usage?.inputTokens)
        assertEquals(3L, response.usage?.outputTokens)
        assertEquals(15L, response.usage?.totalTokens)
    }

    @Test
    fun `function call is parsed only from completed output item`() {
        val decoder = ResponsesSseDecoder()
        decoder.accept("""
            {"type":"response.output_item.done","item":{"type":"function_call","call_id":"call-7","name":"observe_screen","arguments":"{\"fresh\":true}"}}
        """.trimIndent())
        decoder.accept("""{"type":"response.completed","response":{"id":"resp-2"}}""")

        val response = decoder.finish()

        assertEquals(ModelStopReason.TOOL_CALLS, response.stopReason)
        assertNull(response.message)
        assertEquals("call-7", response.toolCalls.single().id.value)
        assertEquals("observe_screen", response.toolCalls.single().name)
        assertEquals("true", response.toolCalls.single().arguments.getValue("fresh").toString())
    }

    @Test
    fun `max output incomplete response cannot be treated as complete`() {
        val decoder = ResponsesSseDecoder()
        decoder.accept("""
            {"type":"response.incomplete","response":{"id":"resp-3","incomplete_details":{"reason":"max_output_tokens"}}}
        """.trimIndent())

        assertEquals(ModelStopReason.OUTPUT_LIMIT, decoder.finish().stopReason)
    }

    @Test
    fun `stream ending without terminal event is retryable protocol failure`() {
        val decoder = ResponsesSseDecoder()
        decoder.accept("""{"type":"response.output_text.delta","delta":"partial"}""")

        val error = assertThrows(ModelProviderException::class.java) { decoder.finish() }

        assertEquals(ModelProviderFailureKind.STREAM_PROTOCOL, error.kind)
        assertEquals(true, error.retryable)
    }

    @Test
    fun `failed event exposes classified provider failure without response body logging`() {
        val decoder = ResponsesSseDecoder()

        val error = assertThrows(ModelProviderException::class.java) {
            decoder.accept("""
                {"type":"response.failed","response":{"error":{"code":"rate_limit_exceeded","message":"Slow down"}}}
            """.trimIndent())
        }

        assertEquals(ModelProviderFailureKind.RATE_LIMIT, error.kind)
        assertEquals(true, error.retryable)
        assertEquals("Slow down", error.message)
    }
}
