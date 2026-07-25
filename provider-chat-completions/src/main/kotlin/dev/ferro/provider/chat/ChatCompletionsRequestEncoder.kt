package dev.ferro.provider.chat

import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ModelInputItem
import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ToolCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ChatCompletionsRequestEncoder {
    fun encode(config: ChatCompletionsProviderConfig, request: ModelRequest): String = buildJsonObject {
        put("model", config.model)
        put("messages", JsonArray(encodeMessages(request)))
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray { request.tools.forEach { add(encodeTool(it)) } })
            put("tool_choice", "auto")
            put("parallel_tool_calls", false)
        }
        put("max_tokens", config.maxTokens)
        put("reasoning_budget", config.reasoningBudget)
        put("temperature", config.temperature)
        put("top_p", config.topP)
        put("chat_template_kwargs", buildJsonObject {
            put("enable_thinking", config.enableThinking)
        })
        put("stream", true)
        put("stream_options", buildJsonObject { put("include_usage", true) })
    }.toString()

    private fun encodeMessages(request: ModelRequest): List<JsonObject> = buildList {
        request.instructions.takeIf(String::isNotBlank)?.let { instructions ->
            add(buildJsonObject {
                put("role", "system")
                put("content", instructions)
            })
        }

        var index = 0
        while (index < request.input.size) {
            when (val item = request.input[index]) {
                is ModelMessageInput -> add(buildJsonObject {
                    put("role", item.role.name.lowercase())
                    put("content", item.text)
                })
                is ModelImageInput -> add(encodeImage(item))
                is ModelToolResultInput -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", item.result.callId.value)
                    put("content", buildJsonObject {
                        put("status", item.result.status.name)
                        item.result.message?.let { put("message", it) }
                        put("data", item.result.output)
                    }.toString())
                })
                is ModelToolCallInput -> {
                    val calls = mutableListOf<ToolCall>()
                    while (index < request.input.size) {
                        val call = request.input[index] as? ModelToolCallInput ?: break
                        calls += call.call
                        index += 1
                    }
                    add(buildJsonObject {
                        put("role", "assistant")
                        put("tool_calls", buildJsonArray { calls.forEach { add(encodeToolCall(it)) } })
                    })
                    continue
                }
            }
            index += 1
        }
    }

    private fun encodeImage(input: ModelImageInput): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", buildJsonArray {
            input.prompt?.takeIf(String::isNotBlank)?.let { prompt ->
                add(buildJsonObject {
                    put("type", "text")
                    put("text", prompt)
                })
            }
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", input.imageUrl) })
            })
        })
    }

    private fun encodeToolCall(call: ToolCall): JsonObject = buildJsonObject {
        put("id", call.id.value)
        put("type", "function")
        put("function", buildJsonObject {
            put("name", call.name)
            put("arguments", call.arguments.toString())
        })
    }

    private fun encodeTool(spec: ModelToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", normalizedSchema(spec))
        })
    }

    private fun normalizedSchema(spec: ModelToolSpec): JsonObject {
        val values = spec.inputSchema.toMutableMap()
        values.putIfAbsent("type", JsonPrimitive("object"))
        values.putIfAbsent("properties", JsonObject(emptyMap()))
        values["required"] = JsonArray(spec.requiredArguments.sorted().map(::JsonPrimitive))
        values.putIfAbsent("additionalProperties", JsonPrimitive(false))
        return JsonObject(values)
    }
}
