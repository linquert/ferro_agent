package dev.ferro.contracts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
enum class ModelMessageRole {
    USER,
    ASSISTANT,
}

@Serializable
sealed interface ModelInputItem

@Serializable
data class ModelMessageInput(
    val role: ModelMessageRole,
    val text: String,
) : ModelInputItem

@Serializable
enum class ModelImageDetail {
    AUTO,
    LOW,
    HIGH,
}

@Serializable
data class ModelImageInput(
    val imageUrl: String,
    val prompt: String? = null,
    val detail: ModelImageDetail = ModelImageDetail.AUTO,
    val sourceToolCallId: ToolCallId? = null,
    val sourceObservationId: String? = null,
    val isFromLatestToolResult: Boolean = false,
) : ModelInputItem {
    init {
        require(imageUrl.isNotBlank()) { "Image URL must not be blank" }
    }
}

@Serializable
data class ModelToolCallInput(
    val call: ToolCall,
) : ModelInputItem

@Serializable
data class ModelToolResultInput(
    val result: ToolResult,
) : ModelInputItem

@Serializable
data class ModelToolSpec(
    val name: String,
    val description: String,
    val requiredArguments: Set<String> = emptySet(),
    val inputSchema: JsonObject = emptyObjectSchema(),
)

@Serializable
data class ModelRequest(
    val threadId: ThreadId,
    val turnId: TurnId,
    val instructions: String,
    val input: List<ModelInputItem>,
    val tools: List<ModelToolSpec>,
    val metadata: Map<String, String>,
)

@Serializable
data class ModelUsage(
    val inputTokens: Long,
    val outputTokens: Long,
    val totalTokens: Long,
)

@Serializable
enum class ModelStopReason {
    COMPLETE,
    TOOL_CALLS,
    OUTPUT_LIMIT,
    ABORTED,
    ERROR,
}

@Serializable
data class ModelResponse(
    val providerResponseId: String? = null,
    val reasoning: String? = null,
    val message: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val stopReason: ModelStopReason,
    val usage: ModelUsage? = null,
)

fun emptyArguments(): JsonObject = JsonObject(emptyMap())

fun emptyObjectSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
}
