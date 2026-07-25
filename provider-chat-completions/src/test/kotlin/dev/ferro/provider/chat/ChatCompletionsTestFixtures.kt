package dev.ferro.provider.chat

import dev.ferro.contracts.ModelMessageInput
import dev.ferro.contracts.ModelMessageRole
import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId

internal fun textRequest() = ModelRequest(
    threadId = ThreadId("thread-1"),
    turnId = TurnId("turn-1"),
    instructions = "Use only declared Android tools.",
    input = listOf(ModelMessageInput(ModelMessageRole.USER, "Inspect the screen")),
    tools = emptyList(),
    metadata = emptyMap(),
)
