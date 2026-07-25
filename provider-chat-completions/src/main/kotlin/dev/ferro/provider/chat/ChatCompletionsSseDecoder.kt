package dev.ferro.provider.chat

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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal class ChatCompletionsSseDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private data class PendingToolCall(
        var id: String? = null,
        var name: String? = null,
        val arguments: StringBuilder = StringBuilder(),
    )

    private val text = StringBuilder()
    private val reasoning = StringBuilder()
    private val pendingToolCalls = sortedMapOf<Int, PendingToolCall>()
    private var responseId: String? = null
    private var usage: ModelUsage? = null
    private var finishReason: String? = null
    private var done = false

    fun accept(data: String) {
        if (data.isBlank()) return
        if (data == "[DONE]") {
            done = true
            return
        }
        val event = parseObject(data, "Malformed Chat Completions stream event")
        (event["error"] as? JsonObject)?.let { throw providerFailure(it) }
        event.string("id")?.let { responseId = it }
        (event["usage"] as? JsonObject)?.let { value ->
            usage = ModelUsage(
                inputTokens = value.long("prompt_tokens"),
                outputTokens = value.long("completion_tokens"),
                totalTokens = value.long("total_tokens"),
            )
        }
        val choices = event["choices"] as? JsonArray ?: return
        choices.forEach choiceLoop@ { choiceElement ->
            val choice = choiceElement as? JsonObject ?: return@choiceLoop
            choice.string("finish_reason")?.let { finishReason = it }
            val delta = choice["delta"] as? JsonObject ?: return@choiceLoop
            delta.string("content")?.let(text::append)
            delta.string("reasoning_content")?.let(reasoning::append)
            (delta["tool_calls"] as? JsonArray).orEmpty().forEach callLoop@ { callElement ->
                val fragment = callElement as? JsonObject ?: return@callLoop
                val index = fragment["index"]?.jsonPrimitive?.intOrNull
                    ?: throw streamError("Tool call fragment is missing its index")
                val pending = pendingToolCalls.getOrPut(index) { PendingToolCall() }
                fragment.string("id")?.let { id ->
                    if (pending.id != null && pending.id != id) {
                        throw streamError("Tool call $index changed id during streaming")
                    }
                    pending.id = id
                }
                val function = fragment["function"] as? JsonObject
                function?.string("name")?.let { name ->
                    if (pending.name != null && pending.name != name) {
                        throw streamError("Tool call $index changed name during streaming")
                    }
                    pending.name = name
                }
                function?.string("arguments")?.let(pending.arguments::append)
            }
        }
    }

    fun finish(): ModelResponse {
        if (!done) throw streamError("Chat Completions stream ended without [DONE]").retryable()
        val reason = finishReason ?: throw streamError("Chat Completions stream has no finish reason")
        val calls = pendingToolCalls.map { (index, pending) ->
            val id = pending.id ?: throw streamError("Tool call $index has no id")
            val name = pending.name ?: throw streamError("Tool call $index has no name")
            val arguments = parseObject(
                pending.arguments.toString().ifBlank { "{}" },
                "Invalid arguments for $name",
            )
            ToolCall(ToolCallId(id), name, arguments)
        }
        return ModelResponse(
            providerResponseId = responseId,
            reasoning = reasoning.toString().takeIf(String::isNotBlank),
            message = text.toString().takeIf(String::isNotBlank),
            toolCalls = calls,
            stopReason = when (reason) {
                "stop" -> if (calls.isEmpty()) ModelStopReason.COMPLETE else ModelStopReason.TOOL_CALLS
                "tool_calls", "function_call" -> ModelStopReason.TOOL_CALLS
                "length" -> ModelStopReason.OUTPUT_LIMIT
                else -> ModelStopReason.ERROR
            },
            usage = usage,
        )
    }

    private fun providerFailure(error: JsonObject): ModelProviderException {
        val code = error.string("code")
        val message = error.string("message") ?: "Chat Completions API returned an error"
        val kind = when (code) {
            "invalid_api_key", "unauthorized" -> ModelProviderFailureKind.AUTHENTICATION
            "rate_limit_exceeded", "too_many_requests" -> ModelProviderFailureKind.RATE_LIMIT
            "invalid_request_error", "bad_request" -> ModelProviderFailureKind.INVALID_REQUEST
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
    private fun JsonObject.long(key: String): Long = get(key)?.jsonPrimitive?.longOrNull ?: 0L

    private fun streamError(message: String) = ModelProviderException(
        ModelProviderFailureKind.STREAM_PROTOCOL,
        retryable = false,
        message = message,
    )

    private fun ModelProviderException.retryable() = ModelProviderException(kind, true, message ?: kind.name, this)

    private companion object {
        val RETRYABLE_FAILURES = setOf(ModelProviderFailureKind.RATE_LIMIT, ModelProviderFailureKind.SERVER)
    }
}
