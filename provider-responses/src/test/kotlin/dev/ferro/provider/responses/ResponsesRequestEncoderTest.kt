package dev.ferro.provider.responses

import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesRequestEncoderTest {
    @Test
    fun `request preserves message call and result order with strict tool schema`() {
        val call = ToolCall(
            id = ToolCallId("call-1"),
            name = "tap",
            arguments = buildJsonObject { put("x", 24) },
        )
        val request = ModelRequest(
            threadId = ThreadId("thread-1"),
            turnId = TurnId("turn-1"),
            instructions = "Use tools carefully.",
            input = listOf(
                ModelMessageInput(ModelMessageRole.USER, "Tap the button"),
                ModelToolCallInput(call),
                ModelToolResultInput(
                    ToolResult(call.id, ToolResultStatus.SUCCESS, buildJsonObject { put("verified", true) }),
                ),
            ),
            tools = listOf(
                ModelToolSpec(
                    name = "tap",
                    description = "Tap one point",
                    requiredArguments = setOf("x"),
                    inputSchema = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("x", buildJsonObject { put("type", "integer") })
                        })
                    },
                ),
            ),
            metadata = mapOf("context_fingerprint" to "private-local-value"),
        )

        val encoded = ResponsesRequestEncoder().encode(ResponsesProviderConfig(), request)
        val root = Json.parseToJsonElement(encoded).jsonObject
        val input = root.getValue("input").jsonArray
        val tool = root.getValue("tools").jsonArray.single().jsonObject
        val parameters = tool.getValue("parameters").jsonObject

        assertEquals(listOf("message", "function_call", "function_call_output"), input.map {
            it.jsonObject.getValue("type").jsonPrimitive.content
        })
        assertEquals("call-1", input[1].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("call-1", input[2].jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("true", tool.getValue("strict").jsonPrimitive.content)
        assertEquals("x", parameters.getValue("required").jsonArray.single().jsonPrimitive.content)
        assertEquals("false", parameters.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals("false", root.getValue("parallel_tool_calls").jsonPrimitive.content)
        assertEquals("false", root.getValue("store").jsonPrimitive.content)
        assertTrue(root.getValue("stream").jsonPrimitive.content.toBoolean())
        assertFalse(encoded.contains("private-local-value"))
    }
}
