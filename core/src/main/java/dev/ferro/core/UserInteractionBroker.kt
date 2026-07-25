package dev.ferro.core

import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.UserRequestAnswered
import dev.ferro.contracts.UserRequestId
import dev.ferro.contracts.UserRequestKind
import dev.ferro.contracts.UserRequestOpened
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class PendingUserRequest(
    val threadId: ThreadId,
    val turnId: TurnId,
    val requestId: UserRequestId,
    val kind: UserRequestKind,
    val prompt: String,
    val reason: String? = null,
    val suggestedAction: String? = null,
)

class UserInteractionBroker(
    private val eventStore: AgentEventStore,
) {
    private val ids = AtomicLong()
    private val mutex = Mutex()
    private var active: ActiveRequest? = null
    private val mutablePending = MutableStateFlow<PendingUserRequest?>(null)
    val pending: StateFlow<PendingUserRequest?> = mutablePending.asStateFlow()

    suspend fun request(
        context: ToolExecutionContext,
        kind: UserRequestKind,
        prompt: String,
        reason: String? = null,
        suggestedAction: String? = null,
    ): String {
        val pending = PendingUserRequest(
            threadId = context.threadId,
            turnId = context.turnId,
            requestId = UserRequestId("request-${ids.incrementAndGet()}"),
            kind = kind,
            prompt = prompt,
            reason = reason,
            suggestedAction = suggestedAction,
        )
        val response = CompletableDeferred<String>()
        mutex.withLock {
            check(active == null) { "Another user request is already pending" }
            active = ActiveRequest(pending, response)
            mutablePending.value = pending
        }
        eventStore.append(
            context.threadId,
            context.turnId,
            UserRequestOpened(pending.requestId, kind, prompt, reason, suggestedAction),
        )
        return response.await()
    }

    suspend fun answer(requestId: UserRequestId, response: String): Boolean {
        val request = mutex.withLock {
            active?.takeIf { it.pending.requestId == requestId }?.also {
                active = null
                mutablePending.value = null
            }
        } ?: return false
        eventStore.append(
            request.pending.threadId,
            request.pending.turnId,
            UserRequestAnswered(requestId),
        )
        request.response.complete(response)
        return true
    }

    suspend fun cancelTurn(turnId: TurnId, reason: String) {
        val request = mutex.withLock {
            active?.takeIf { it.pending.turnId == turnId }?.also {
                active = null
                mutablePending.value = null
            }
        } ?: return
        request.response.cancel(CancellationException(reason))
    }
}

private data class ActiveRequest(
    val pending: PendingUserRequest,
    val response: CompletableDeferred<String>,
)

fun interface UserControlRecovery {
    suspend fun recover(
        context: ToolExecutionContext,
        call: ToolCall,
        userNote: String,
    ): ToolResult
}

class UserInteractionToolCatalog(
    private val broker: UserInteractionBroker,
    private val userControlRecovery: UserControlRecovery = UserControlRecovery { _, call, note ->
        ToolResult(
            callId = call.id,
            status = ToolResultStatus.SUCCESS,
            output = buildJsonObject {
                put("user_note", note)
                put("fresh_observation_required", true)
            },
            message = "User returned control; observe the screen before acting",
        )
    },
) {
    fun handlers(): List<ToolHandler> = listOf(
        RequestUserInputHandler(broker),
        RequestUserControlHandler(broker, userControlRecovery),
    )
}

private class RequestUserInputHandler(
    private val broker: UserInteractionBroker,
) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "request_user_input",
        description = "Ask the user for information that is required to continue the active task.",
        requiredArguments = setOf("prompt"),
        inputSchema = promptSchema(),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
        val response = broker.request(context, UserRequestKind.INPUT, call.prompt())
        return ToolResult(
            callId = call.id,
            status = ToolResultStatus.SUCCESS,
            output = buildJsonObject { put("response", response) },
            message = "User responded",
        )
    }
}

private class RequestUserControlHandler(
    private val broker: UserInteractionBroker,
    private val recovery: UserControlRecovery,
) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "request_user_control",
        description = "Hand Android control to the user when a manual action is required. Waits for the user, then returns their note with a fresh screenshot.",
        requiredArguments = setOf("reason", "suggested_action"),
        inputSchema = controlRequestSchema(),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
        val reason = call.requiredString("reason")
        val suggestedAction = call.requiredString("suggested_action")
        val prompt = "$reason\nSuggested action: $suggestedAction"
        val response = broker.request(
            context,
            UserRequestKind.CONTROL,
            prompt,
            reason,
            suggestedAction,
        )
        return recovery.recover(context, call, response)
    }
}

private fun ToolCall.prompt(): String = arguments["prompt"]?.jsonPrimitive?.contentOrNull
    ?.takeIf(String::isNotBlank) ?: error("prompt must be a non-blank string")

private fun ToolCall.requiredString(name: String): String = arguments[name]?.jsonPrimitive?.contentOrNull
    ?.takeIf(String::isNotBlank) ?: error("$name must be a non-blank string")

private fun promptSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("prompt", buildJsonObject {
            put("type", "string")
            put("description", "Clear instruction or question for the user")
        })
    })
    put("additionalProperties", false)
}

private fun controlRequestSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        put("reason", buildJsonObject {
            put("type", "string")
            put("description", "Why Ferro needs the user to manipulate the device")
        })
        put("suggested_action", buildJsonObject {
            put("type", "string")
            put("description", "One clear action the user can take before returning control")
        })
    })
    put("additionalProperties", false)
}
