package dev.ferro.runtime.android

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.ThreadId
import dev.ferro.core.AgentEventStore
import dev.ferro.core.TurnRecoveryJournal
import kotlinx.coroutines.flow.Flow

internal interface RuntimeRecoveryRepository {
    suspend fun restore(): ActiveRuntimeRecord?
    suspend fun persist(record: ActiveRuntimeRecord)
    suspend fun pauseAfterRestart(record: ActiveRuntimeRecord): Boolean
    suspend fun abandon(record: ActiveRuntimeRecord)
    suspend fun clear(record: ActiveRuntimeRecord)
    fun observeEvents(threadId: ThreadId): Flow<List<AgentEventEnvelope>>

    data object None : RuntimeRecoveryRepository {
        override suspend fun restore(): ActiveRuntimeRecord? = null
        override suspend fun persist(record: ActiveRuntimeRecord) = Unit
        override suspend fun pauseAfterRestart(record: ActiveRuntimeRecord) = true
        override suspend fun abandon(record: ActiveRuntimeRecord) = Unit
        override suspend fun clear(record: ActiveRuntimeRecord) = Unit
        override fun observeEvents(threadId: ThreadId): Flow<List<AgentEventEnvelope>> =
            kotlinx.coroutines.flow.flowOf(emptyList())
    }
}

internal class AndroidRuntimeRecoveryRepository(
    private val activeStore: ActiveRuntimeStore,
    private val eventStore: AgentEventStore,
) : RuntimeRecoveryRepository {
    private val journal = TurnRecoveryJournal(eventStore)

    override suspend fun restore(): ActiveRuntimeRecord? = activeStore.load()

    override suspend fun persist(record: ActiveRuntimeRecord) = activeStore.save(record)

    override suspend fun pauseAfterRestart(record: ActiveRuntimeRecord): Boolean =
        journal.pauseAfterProcessRestart(record.threadId, record.turnId)

    override suspend fun abandon(record: ActiveRuntimeRecord) {
        journal.abandon(record.threadId, record.turnId, "Recovered task discarded by user")
    }

    override suspend fun clear(record: ActiveRuntimeRecord) {
        activeStore.clear(record.sessionId)
    }

    override fun observeEvents(threadId: ThreadId): Flow<List<AgentEventEnvelope>> =
        eventStore.observeThread(threadId)
}
