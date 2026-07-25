package dev.ferro.core

import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ThreadStarted
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnStarted
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryAgentEventStoreTest {
    @Test
    fun `append allocates monotonic per-thread sequences under concurrent callers`() = runTest {
        val store = InMemoryAgentEventStore(SequentialIdGenerator(), IncrementingClock())
        val threadId = ThreadId("thread")

        (1..40).map { index ->
            async {
                store.append(threadId, TurnId("turn"), TurnStarted("goal-$index"))
            }
        }.awaitAll()

        val events = store.readThread(threadId)
        assertEquals((1L..40L).toList(), events.map { it.sequence })
        assertEquals(40, events.map { it.eventId }.distinct().size)
    }

    @Test
    fun `sequence allocation is independent for different threads`() = runTest {
        val store = InMemoryAgentEventStore(SequentialIdGenerator(), IncrementingClock())

        val first = store.append(ThreadId("a"), null, ThreadStarted("a"))
        val second = store.append(ThreadId("b"), null, ThreadStarted("b"))

        assertEquals(1L, first.sequence)
        assertEquals(1L, second.sequence)
    }
}
