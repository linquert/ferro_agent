package dev.ferro.core

import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.TaskCapabilityScopeEstablished
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCapabilityJournalTest {
    private val ids = SequentialIdGenerator()
    private val store = InMemoryAgentEventStore(ids, IncrementingClock())
    private val journal = TaskCapabilityJournal(store)
    private val threadId = ThreadId("thread")
    private val turnId = TurnId("turn")

    @Test
    fun `scope is durably established exactly once`() = runTest {
        val scope = scope()

        journal.ensureEstablished(threadId, turnId, scope)
        journal.ensureEstablished(threadId, turnId, scope)

        val events = store.readThread(threadId).mapNotNull { it.payload as? TaskCapabilityScopeEstablished }
        assertEquals(1, events.size)
        assertEquals(scope, events.single().scope)
        assertEquals(ToolAuthorizationHashes.scope(scope), events.single().scopeHash)
    }

    @Test
    fun `recovery rejects metadata authority that differs from durable event`() = runTest {
        journal.ensureEstablished(threadId, turnId, scope())

        val error = runCatching {
            journal.ensureEstablished(threadId, turnId, scope().copy(allowTextEntry = true))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    private fun scope() = TaskCapabilityScope(
        id = CapabilityScopeId("scope"),
        allowedTools = FerroToolNames.all,
        allowedPackages = setOf("com.example.target"),
    )
}
