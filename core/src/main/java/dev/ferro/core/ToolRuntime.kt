package dev.ferro.core

import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolExecutionContext(
    val threadId: ThreadId,
    val turnId: TurnId,
    val contextFingerprint: String,
    val authorization: ToolExecutionPermit? = null,
)

interface ToolHandler {
    val spec: ModelToolSpec
    suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult
}

fun interface ToolCallBinder {
    fun bind(call: ToolCall): ToolCall

    companion object {
        val IDENTITY = ToolCallBinder { it }
    }
}

class ToolExecutionException(
    val code: String,
    override val message: String,
    val status: ToolResultStatus = ToolResultStatus.RECOVERABLE_FAILURE,
    val dispatch: String = "not_dispatched",
    val platformOutcome: String = "rejected",
) : IllegalArgumentException(message)

class ToolRegistry(handlers: List<ToolHandler>) {
    private val handlersByName = handlers.associateBy { it.spec.name }

    init {
        require(handlersByName.size == handlers.size) { "Tool names must be unique" }
    }

    val specs: List<ModelToolSpec> = handlers.map { it.spec }.sortedBy { it.name }
    val catalogVersion: String = specs.joinToString("|") { it.name }.hashCode().toString(16)

    fun handler(name: String): ToolHandler? = handlersByName[name]
}

class ToolRouter(private val registry: ToolRegistry) {
    val specs: List<ModelToolSpec> get() = registry.specs
    val catalogVersion: String get() = registry.catalogVersion

    internal suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
        val handler = registry.handler(call.name)
            ?: return failure(call, "UNKNOWN_TOOL", "Unknown tool: ${call.name}")
        return executeValidated(context, call, handler)
    }

    private suspend fun executeValidated(
        context: ToolExecutionContext,
        call: ToolCall,
        handler: ToolHandler,
    ): ToolResult {
        val missing = handler.spec.requiredArguments - call.arguments.keys
        if (missing.isNotEmpty()) {
            return failure(
                call,
                "MISSING_ARGUMENTS",
                "Missing required arguments: ${missing.sorted().joinToString()}",
            )
        }
        return try {
            handler.execute(context, call)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ToolExecutionException) {
            failure(call, error.code, error.message, error.status, error.dispatch, error.platformOutcome)
        } catch (error: Throwable) {
            failure(call, "TOOL_EXCEPTION", error.message ?: error::class.java.simpleName)
        }
    }

    private fun failure(
        call: ToolCall,
        code: String,
        message: String,
        status: ToolResultStatus = ToolResultStatus.RECOVERABLE_FAILURE,
        dispatch: String = "not_dispatched",
        platformOutcome: String = "rejected",
    ): ToolResult = ToolResult(
        callId = call.id,
        status = status,
        output = buildJsonObject {
            put("code", code)
            put("dispatch", dispatch)
            put("platform_outcome", platformOutcome)
        },
        message = message,
    )
}
