package dev.ferro.platform.android

import android.content.Context
import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.core.AgentEventStore
import dev.ferro.core.FerroClock
import dev.ferro.core.IdGenerator
import dev.ferro.core.SystemFerroClock
import dev.ferro.core.UuidIdGenerator
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class AndroidJsonlAgentEventStore(
    context: Context,
    private val ids: IdGenerator = UuidIdGenerator(),
    private val clock: FerroClock = SystemFerroClock,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "eventType"
    },
) : AgentEventStore {
    private val file = File(context.filesDir, "agent-events.jsonl")
    private val eventFile = JsonlAgentEventFile(file, json)
    private val mutex = Mutex()
    private val state = MutableStateFlow<Map<ThreadId, List<AgentEventEnvelope>>>(emptyMap())
    private var loaded = false

    override suspend fun append(
        threadId: ThreadId,
        turnId: TurnId?,
        payload: AgentEventPayload,
    ): AgentEventEnvelope = mutex.withLock {
        ensureLoadedLocked()
        val current = state.value[threadId].orEmpty()
        val event = AgentEventEnvelope(
            eventId = ids.eventId(),
            threadId = threadId,
            turnId = turnId,
            sequence = (current.lastOrNull()?.sequence ?: 0L) + 1L,
            timestampEpochMs = clock.nowEpochMs(),
            payload = payload,
        )
        eventFile.append(event)
        state.value = state.value + (threadId to (current + event))
        event
    }

    override suspend fun readThread(threadId: ThreadId): List<AgentEventEnvelope> = mutex.withLock {
        ensureLoadedLocked()
        state.value[threadId].orEmpty()
    }

    override fun observeThread(threadId: ThreadId): Flow<List<AgentEventEnvelope>> =
        state.map { it[threadId].orEmpty() }

    suspend fun load() = mutex.withLock { ensureLoadedLocked() }

    private fun ensureLoadedLocked() {
        if (loaded) return
        val events = eventFile.load()
        state.value = events.groupBy { it.threadId }
        loaded = true
    }

}
