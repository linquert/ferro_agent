package dev.ferro.core

import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.TaskCapabilityScopeEstablished
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId

class TaskCapabilityJournal(
    private val eventStore: AgentEventStore,
) {
    suspend fun ensureEstablished(
        threadId: ThreadId,
        turnId: TurnId,
        scope: TaskCapabilityScope,
    ) {
        val expectedHash = ToolAuthorizationHashes.scope(scope)
        val existing = eventStore.readThread(threadId)
            .filter { it.turnId == turnId }
            .mapNotNull { it.payload as? TaskCapabilityScopeEstablished }
        check(existing.size <= 1) { "Multiple capability scopes were recorded for one turn" }
        val established = existing.singleOrNull()
        if (established == null) {
            eventStore.append(
                threadId,
                turnId,
                TaskCapabilityScopeEstablished(scope, expectedHash),
            )
            return
        }
        check(established.scopeHash == expectedHash && established.scope == scope) {
            "Durable capability scope does not match active runtime metadata"
        }
    }
}
