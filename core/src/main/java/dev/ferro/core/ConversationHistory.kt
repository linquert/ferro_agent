package dev.ferro.core

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AssistantMessageRecorded
import dev.ferro.contracts.ModelInputItem
import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelToolCallInput
import dev.ferro.contracts.ModelToolResultInput
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.TurnStarted
import dev.ferro.contracts.UserInputRecorded

interface ConversationHistory {
    fun rebuild(events: List<AgentEventEnvelope>): List<ModelInputItem>
}

object EventReconstructedConversationHistory : ConversationHistory {
    override fun rebuild(events: List<AgentEventEnvelope>): List<ModelInputItem> {
        val runtimeCallIds = events.mapNotNullTo(mutableSetOf()) { event ->
            (event.payload as? ToolCallRecorded)
                ?.takeIf { it.origin == ToolCallOrigin.RUNTIME_RECOVERY }
                ?.call
                ?.id
        }
        return events.mapNotNull { event ->
            when (val payload = event.payload) {
                is TurnStarted -> ModelMessageInput(ModelMessageRole.USER, payload.goal)
                is UserInputRecorded -> ModelMessageInput(ModelMessageRole.USER, payload.text)
                is AssistantMessageRecorded -> ModelMessageInput(ModelMessageRole.ASSISTANT, payload.text)
                is ToolCallRecorded -> payload
                    .takeIf { it.origin == ToolCallOrigin.MODEL }
                    ?.let { ModelToolCallInput(it.call) }
                is ToolResultRecorded -> payload.result
                    .takeIf { it.callId !in runtimeCallIds }
                    ?.let(::ModelToolResultInput)
                else -> null
            }
        }
    }
}
