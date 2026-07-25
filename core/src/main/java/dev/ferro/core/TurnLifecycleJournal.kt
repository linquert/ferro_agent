package dev.ferro.core

import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ThreadStarted
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnStarted

class TurnLifecycleJournal(
    private val eventStore: AgentEventStore,
) {
    suspend fun ensureStarted(threadId: ThreadId, turnId: TurnId, goal: String) {
        require(goal.isNotBlank()) { "Goal must not be blank" }
        val events = eventStore.readThread(threadId)
        if (events.none { it.payload is ThreadStarted }) {
            eventStore.append(threadId, null, ThreadStarted(goal.trim().take(80)))
        }
        if (events.none { it.turnId == turnId && it.payload is TurnStarted }) {
            eventStore.append(threadId, turnId, TurnStarted(goal.trim()))
        }
    }
}
