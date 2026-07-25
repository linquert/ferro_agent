package dev.ferro.core

import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ThreadStarted
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnStarted
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TurnLifecycleJournalTest {
    @Test
    fun `ensure started is idempotent for one durable turn`() = runTest {
        val store = InMemoryAgentEventStore(SequentialIdGenerator(), IncrementingClock())
        val journal = TurnLifecycleJournal(store)
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")

        journal.ensureStarted(threadId, turnId, "Complete task")
        journal.ensureStarted(threadId, turnId, "Complete task")

        val payloads = store.readThread(threadId).map { it.payload }
        assertEquals(1, payloads.filterIsInstance<ThreadStarted>().size)
        assertEquals(1, payloads.filterIsInstance<TurnStarted>().size)
    }

    @Test
    fun `new turn reuses thread lifecycle while recording its own goal`() = runTest {
        val store = InMemoryAgentEventStore(SequentialIdGenerator(), IncrementingClock())
        val journal = TurnLifecycleJournal(store)
        val threadId = ThreadId("thread")

        journal.ensureStarted(threadId, TurnId("turn-1"), "First")
        journal.ensureStarted(threadId, TurnId("turn-2"), "Second")

        val payloads = store.readThread(threadId).map { it.payload }
        assertEquals(1, payloads.filterIsInstance<ThreadStarted>().size)
        assertEquals(listOf("First", "Second"), payloads.filterIsInstance<TurnStarted>().map { it.goal })
    }
}
