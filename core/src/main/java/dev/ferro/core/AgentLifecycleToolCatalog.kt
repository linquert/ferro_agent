package dev.ferro.core

import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentLifecycleToolCatalog {
    fun handlers(): List<ToolHandler> = listOf(CompleteTaskHandler())
}

private class CompleteTaskHandler : ToolHandler {
    override val spec = ModelToolSpec(
        name = FerroToolNames.COMPLETE_TASK,
        description = "Finish the active task only after its requested outcome is achieved. Provide a concise result and, when useful, the visible evidence that confirms it.",
        requiredArguments = setOf("summary"),
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("summary", stringProperty("Concise final result for the user"))
                put("evidence", stringProperty("Optional brief description of evidence confirming completion"))
            })
            put("additionalProperties", false)
        },
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
        val summary = call.arguments["summary"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("summary must be a non-blank string")
        return ToolResult(
            callId = call.id,
            status = ToolResultStatus.TASK_COMPLETED,
            output = buildJsonObject {
                put("summary", summary)
                call.arguments["evidence"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank)
                    ?.let { put("evidence", it) }
            },
            message = summary,
        )
    }
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}
