package dev.ferro.provider.chat

import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
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

class ChatCompletionsRequestEncoderTest {
    @Test
    fun `encodes multimodal history calls results tools and NVIDIA reasoning options`() {
        val firstCall = ToolCall(ToolCallId("call-1"), "tap", buildJsonObject { put("x", 12) })
        val secondCall = ToolCall(ToolCallId("call-2"), "wait", buildJsonObject { put("ms", 500) })
        val base = textRequest()
        val request = ModelRequest(
            threadId = base.threadId,
            turnId = base.turnId,
            instructions = base.instructions,
            input = base.input + listOf(
                ModelImageInput("data:image/png;base64,AAA", "Current screen"),
                ModelToolCallInput(firstCall),
                ModelToolCallInput(secondCall),
                ModelToolResultInput(
                    ToolResult(firstCall.id, ToolResultStatus.SUCCESS, buildJsonObject { put("verified", true) }),
                ),
            ),
            tools = listOf(
                ModelToolSpec(
                    name = "tap",
                    description = "Tap a point",
                    requiredArguments = setOf("x"),
                    inputSchema = buildJsonObject {
                        put("properties", buildJsonObject {
                            put("x", buildJsonObject { put("type", "integer") })
                        })
                    },
                ),
            ),
            metadata = mapOf("secret_local_value" to "must-not-leak"),
        )

        val encoded = ChatCompletionsRequestEncoder().encode(ChatCompletionsProviderConfig(), request)
        val root = Json.parseToJsonElement(encoded).jsonObject
        val messages = root.getValue("messages").jsonArray
        val image = messages[2].jsonObject.getValue("content").jsonArray[1].jsonObject
        val calls = messages[3].jsonObject.getValue("tool_calls").jsonArray
        val toolResult = messages[4].jsonObject
        val parameters = root.getValue("tools").jsonArray.single().jsonObject
            .getValue("function").jsonObject.getValue("parameters").jsonObject

        assertEquals(listOf("system", "user", "user", "assistant", "tool"), messages.map {
            it.jsonObject.getValue("role").jsonPrimitive.content
        })
        assertEquals("data:image/png;base64,AAA", image.getValue("image_url").jsonObject
            .getValue("url").jsonPrimitive.content)
        assertEquals(listOf("call-1", "call-2"), calls.map {
            it.jsonObject.getValue("id").jsonPrimitive.content
        })
        assertEquals("call-1", toolResult.getValue("tool_call_id").jsonPrimitive.content)
        assertEquals("x", parameters.getValue("required").jsonArray.single().jsonPrimitive.content)
        assertEquals("false", parameters.getValue("additionalProperties").jsonPrimitive.content)
        assertEquals("4096", root.getValue("reasoning_budget").jsonPrimitive.content)
        assertTrue(root.getValue("stream").jsonPrimitive.content.toBoolean())
        assertFalse(encoded.contains("must-not-leak"))
    }
}
