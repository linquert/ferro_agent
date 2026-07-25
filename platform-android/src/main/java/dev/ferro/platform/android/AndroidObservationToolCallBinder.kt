package dev.ferro.platform.android

import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.ToolCall
import dev.ferro.core.ToolCallBinder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AndroidObservationToolCallBinder(
    private val latestObservationId: () -> String?,
) : ToolCallBinder {
    override fun bind(call: ToolCall): ToolCall {
        val arguments = call.arguments.toMutableMap()
        when (call.name) {
            in SCREEN_BOUND_TOOLS -> latestObservationId()?.let {
                arguments[OBSERVATION_ID] = JsonPrimitive(it)
            }
            in SCREEN_UNBOUND_TOOLS -> arguments.remove(OBSERVATION_ID)
        }
        val bound = JsonObject(arguments)
        return if (bound == call.arguments) call else call.copy(arguments = bound)
    }

    private companion object {
        const val OBSERVATION_ID = "observation_id"
        val SCREEN_BOUND_TOOLS = setOf(
            FerroToolNames.TAP,
            FerroToolNames.SWIPE,
            FerroToolNames.TYPE_TEXT,
            FerroToolNames.KEY_ACTION,
        )
        val SCREEN_UNBOUND_TOOLS = setOf(
            FerroToolNames.OPEN_APP,
            FerroToolNames.WAIT,
        )
    }
}
