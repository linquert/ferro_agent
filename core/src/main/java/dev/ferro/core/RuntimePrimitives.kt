package dev.ferro.core

import dev.ferro.contracts.IterationId
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.ToolCallId
import java.util.UUID

fun interface FerroClock {
    fun nowEpochMs(): Long
}

object SystemFerroClock : FerroClock {
    override fun nowEpochMs(): Long = System.currentTimeMillis()
}

interface IdGenerator {
    fun eventId(): String
    fun threadId(): ThreadId
    fun turnId(): TurnId
    fun iterationId(): IterationId
    fun toolCallId(): ToolCallId
}

class UuidIdGenerator : IdGenerator {
    override fun eventId(): String = UUID.randomUUID().toString()
    override fun threadId(): ThreadId = ThreadId("thread_${UUID.randomUUID()}")
    override fun turnId(): TurnId = TurnId("turn_${UUID.randomUUID()}")
    override fun iterationId(): IterationId = IterationId("iteration_${UUID.randomUUID()}")
    override fun toolCallId(): ToolCallId = ToolCallId("call_${UUID.randomUUID()}")
}

data class TurnBudget(
    val maxIterations: Int = 30,
    val maxToolCalls: Int = 60,
    val maxConsecutiveIdenticalFailures: Int = 3,
)
