package dev.ferro.provider.responses

import dev.ferro.contracts.ModelResponse
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.ModelUsage
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.core.ModelProviderException
import dev.ferro.core.ModelProviderFailureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ResponsesSseDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val outputText = mutableListOf<String>()
    private val textDeltas = StringBuilder()
    private val reasoningDeltas = StringBuilder()
    private val toolCalls = mutableListOf<ToolCall>()
    private var responseId: String? = null
    private var usage: ModelUsage? = null
    private var terminalReason: ModelStopReason? = null

    fun accept(data: String) {
        if (data == "[DONE]" || data.isBlank()) return
        val event = parseObject(data, "Malformed Responses stream event")
        when (event.string("type")) {
            "response.output_text.delta" -> event.string("delta")?.let(textDeltas::append)
            "response.reasoning_summary_text.delta" -> event.string("delta")?.let(reasoningDeltas::append)
            "response.output_item.done" -> decodeOutputItem(event["item"] as? JsonObject)
            "response.completed" -> decodeCompleted(event["response"] as? JsonObject)
            "response.incomplete" -> decodeIncomplete(event["response"] as? JsonObject)
            "response.failed", "error" -> throw providerFailure(event)
        }
    }

    fun finish(): ModelResponse {
        val stopReason = terminalReason ?: throw ModelProviderException(
            ModelProviderFailureKind.STREAM_PROTOCOL,
            retryable = true,
            message = "Responses stream ended before a terminal event",
        )
        val message = outputText.takeIf { it.isNotEmpty() }?.joinToString("\n")
            ?: textDeltas.toString().takeIf(String::isNotBlank)
        return ModelResponse(
            providerResponseId = responseId,
            reasoning = reasoningDeltas.toString().takeIf(String::isNotBlank),
            message = message,
            toolCalls = toolCalls.toList(),
            stopReason = if (stopReason == ModelStopReason.COMPLETE && toolCalls.isNotEmpty()) {
                ModelStopReason.TOOL_CALLS
            } else {
                stopReason
            },
            usage = usage,
        )
    }

    private fun decodeOutputItem(item: JsonObject?) {
        if (item == null) return
        when (item.string("type")) {
            "message" -> (item["content"] as? JsonArray).orEmpty().forEach { content ->
                val value = content as? JsonObject ?: return@forEach
                if (value.string("type") == "output_text") {
                    value.string("text")?.takeIf(String::isNotBlank)?.let(outputText::add)
                }
            }
            "function_call" -> {
                val callId = item.requiredString("call_id")
                val name = item.requiredString("name")
                val arguments = parseObject(item.requiredString("arguments"), "Invalid arguments for $name")
                toolCalls += ToolCall(ToolCallId(callId), name, arguments)
            }
        }
    }

    private fun decodeCompleted(response: JsonObject?) {
        response ?: throw streamError("Completed event did not contain a response")
        responseId = response.string("id")
        usage = (response["usage"] as? JsonObject)?.let { value ->
            ModelUsage(
                inputTokens = value.long("input_tokens"),
                outputTokens = value.long("output_tokens"),
                totalTokens = value.long("total_tokens"),
            )
        }
        terminalReason = ModelStopReason.COMPLETE
    }

    private fun decodeIncomplete(response: JsonObject?) {
        responseId = response?.string("id")
        val reason = (response?.get("incomplete_details") as? JsonObject)?.string("reason")
        terminalReason = if (reason == "max_output_tokens") ModelStopReason.OUTPUT_LIMIT else ModelStopReason.ERROR
    }

    private fun providerFailure(event: JsonObject): ModelProviderException {
        val response = event["response"] as? JsonObject
        val error = (response?.get("error") ?: event["error"]) as? JsonObject
        val code = error?.string("code")
        val message = error?.string("message") ?: "Responses API returned a failed event"
        val kind = when (code) {
            "invalid_api_key" -> ModelProviderFailureKind.AUTHENTICATION
            "rate_limit_exceeded", "insufficient_quota" -> ModelProviderFailureKind.RATE_LIMIT
            "invalid_request_error", "invalid_prompt" -> ModelProviderFailureKind.INVALID_REQUEST
            else -> ModelProviderFailureKind.SERVER
        }
        return ModelProviderException(kind, kind in RETRYABLE_FAILURES, message)
    }

    private fun parseObject(value: String, message: String): JsonObject = try {
        json.parseToJsonElement(value).jsonObject
    } catch (error: Throwable) {
        throw ModelProviderException(ModelProviderFailureKind.STREAM_PROTOCOL, false, message, error)
    }

    private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.requiredString(key: String): String = string(key) ?: throw streamError("Missing $key")
    private fun JsonObject.long(key: String): Long = get(key)?.jsonPrimitive?.longOrNull ?: 0L

    private fun streamError(message: String) = ModelProviderException(
        ModelProviderFailureKind.STREAM_PROTOCOL,
        retryable = false,
        message = message,
    )

    private companion object {
        val RETRYABLE_FAILURES = setOf(ModelProviderFailureKind.RATE_LIMIT, ModelProviderFailureKind.SERVER)
    }
}
