package dev.ferro.core

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AgentEventStore {
    suspend fun append(
        threadId: ThreadId,
        turnId: TurnId?,
        payload: AgentEventPayload,
    ): AgentEventEnvelope

    suspend fun readThread(threadId: ThreadId): List<AgentEventEnvelope>
    fun observeThread(threadId: ThreadId): Flow<List<AgentEventEnvelope>>
}

class InMemoryAgentEventStore(
    private val ids: IdGenerator = UuidIdGenerator(),
    private val clock: FerroClock = SystemFerroClock,
) : AgentEventStore {
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<ThreadId, List<AgentEventEnvelope>>>(emptyMap())

    override suspend fun append(
        threadId: ThreadId,
        turnId: TurnId?,
        payload: AgentEventPayload,
    ): AgentEventEnvelope = mutex.withLock {
        val current = state.value[threadId].orEmpty()
        val event = AgentEventEnvelope(
            eventId = ids.eventId(),
            threadId = threadId,
            turnId = turnId,
            sequence = (current.lastOrNull()?.sequence ?: 0L) + 1L,
            timestampEpochMs = clock.nowEpochMs(),
            payload = payload,
        )
        state.value = state.value + (threadId to (current + event))
        event
    }

    override suspend fun readThread(threadId: ThreadId): List<AgentEventEnvelope> =
        state.value[threadId].orEmpty()

    override fun observeThread(threadId: ThreadId): Flow<List<AgentEventEnvelope>> =
        state.map { it[threadId].orEmpty() }
}
