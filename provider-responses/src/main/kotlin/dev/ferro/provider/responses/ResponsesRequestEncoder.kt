package dev.ferro.provider.responses

import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelImageInput
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ModelToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ResponsesRequestEncoder {
    fun encode(config: ResponsesProviderConfig, request: ModelRequest): String = buildJsonObject {
        put("model", config.model)
        put("instructions", request.instructions)
        put("input", buildJsonArray { request.input.forEach { add(encodeInput(it)) } })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray { request.tools.forEach { add(encodeTool(it)) } })
            put("tool_choice", "auto")
        }
        put("parallel_tool_calls", false)
        put("reasoning", buildJsonObject { put("effort", config.reasoningEffort) })
        put("store", false)
        put("stream", true)
    }.toString()

    private fun encodeInput(input: dev.ferro.contracts.ModelInputItem): JsonObject = when (input) {
        is ModelMessageInput -> buildJsonObject {
            put("type", "message")
            put("role", input.role.name.lowercase())
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", if (input.role == ModelMessageRole.USER) "input_text" else "output_text")
                    put("text", input.text)
                })
            })
        }
        is ModelImageInput -> buildJsonObject {
            put("type", "message")
            put("role", "user")
            put("content", buildJsonArray {
                input.prompt?.takeIf(String::isNotBlank)?.let { prompt ->
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", prompt)
                    })
                }
                add(buildJsonObject {
                    put("type", "input_image")
                    put("image_url", input.imageUrl)
                    put("detail", input.detail.name.lowercase())
                })
            })
        }
        is ModelToolCallInput -> buildJsonObject {
            put("type", "function_call")
            put("call_id", input.call.id.value)
            put("name", input.call.name)
            put("arguments", input.call.arguments.toString())
        }
        is ModelToolResultInput -> buildJsonObject {
            put("type", "function_call_output")
            put("call_id", input.result.callId.value)
            put("output", buildJsonObject {
                put("status", input.result.status.name)
                input.result.message?.let { put("message", it) }
                put("data", input.result.output)
            }.toString())
        }
    }

    private fun encodeTool(spec: ModelToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", spec.name)
        put("description", spec.description)
        put("parameters", normalizedSchema(spec))
        put("strict", true)
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
