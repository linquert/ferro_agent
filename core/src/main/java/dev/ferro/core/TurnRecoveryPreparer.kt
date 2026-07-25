package dev.ferro.core

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.IterationId
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.ToolApprovalDenied
import dev.ferro.contracts.ToolApprovalExpired
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolApprovalGranted
import dev.ferro.contracts.ToolApprovalRequested
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnCancelled
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnFailed
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnRecoveryResumed
import dev.ferro.contracts.TurnStarted
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class TurnRecoveryPreparer(
    private val eventStore: AgentEventStore,
    private val toolRouter: ToolRouter,
    private val ids: IdGenerator = UuidIdGenerator(),
) {
    private val journal = TurnRecoveryJournal(eventStore)

    suspend fun pauseAfterProcessRestart(threadId: ThreadId, turnId: TurnId): Boolean =
        journal.pauseAfterProcessRestart(threadId, turnId)

    suspend fun prepareExplicitResume(threadId: ThreadId, turnId: TurnId): ToolResult {
        val events = eventStore.readThread(threadId)
        check(events.isRecoverable(turnId)) { "Turn is no longer recoverable" }
        check(events.isRecoveryPaused(turnId)) { "Turn is not paused for recovery" }

        journal.settleOrphanedCalls(threadId, turnId, events)
        val iterationId = ids.iterationId()
        val call = ToolCall(ids.toolCallId(), "observe_screen", JsonObject(emptyMap()))
        eventStore.append(
            threadId,
            turnId,
            ToolCallRecorded(iterationId, call, ToolCallOrigin.RUNTIME_RECOVERY),
        )
        val result = toolRouter.execute(
            ToolExecutionContext(
                threadId,
                turnId,
                "recovery:${eventStore.readThread(threadId).lastOrNull()?.sequence ?: 0L}",
            ),
            call,
        )
        eventStore.append(threadId, turnId, ToolResultRecorded(iterationId, result))
        if (result.status != ToolResultStatus.SUCCESS) {
            throw RecoveryPreparationException(
                result.message ?: "Fresh screen capture failed during recovery",
            )
        }
        val observationId = result.output["observation_id"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?: throw RecoveryPreparationException("Recovery observation did not return an observation ID")
        eventStore.append(threadId, turnId, TurnRecoveryResumed(observationId))
        return result
    }

    suspend fun abandon(threadId: ThreadId, turnId: TurnId, reason: String) {
        journal.abandon(threadId, turnId, reason)
    }
}

class TurnRecoveryJournal(
    private val eventStore: AgentEventStore,
) {
    suspend fun pauseAfterProcessRestart(threadId: ThreadId, turnId: TurnId): Boolean {
        val events = eventStore.readThread(threadId)
        if (!events.isRecoverable(turnId)) return false
        settleOrphanedCalls(threadId, turnId, events)

        val current = eventStore.readThread(threadId).filter { it.turnId == turnId }
        val lastPause = current.indexOfLast { it.payload is TurnRecoveryPaused }
        val lastResume = current.indexOfLast { it.payload is TurnRecoveryResumed }
        if (lastPause <= lastResume) {
            eventStore.append(
                threadId,
                turnId,
                TurnRecoveryPaused("Android process restarted while the turn was active"),
            )
        }
        return true
    }

    suspend fun abandon(threadId: ThreadId, turnId: TurnId, reason: String) {
        val events = eventStore.readThread(threadId)
        if (events.isRecoverable(turnId)) {
            settleOrphanedCalls(threadId, turnId, events)
            eventStore.append(threadId, turnId, TurnCancelled(reason))
        }
    }

    internal suspend fun settleOrphanedCalls(
        threadId: ThreadId,
        turnId: TurnId,
        events: List<AgentEventEnvelope>,
    ) {
        val turnEvents = events.filter { it.turnId == turnId }
        val settledApprovalIds = turnEvents.mapNotNullTo(mutableSetOf()) { event ->
            when (val payload = event.payload) {
                is ToolApprovalGranted -> payload.requestId
                is ToolApprovalDenied -> payload.requestId
                is ToolApprovalExpired -> payload.requestId
                else -> null
            }
        }
        turnEvents.mapNotNull { it.payload as? ToolApprovalRequested }
            .filter { it.request.requestId !in settledApprovalIds }
            .forEach { orphan ->
                eventStore.append(
                    threadId,
                    turnId,
                    ToolApprovalExpired(
                        orphan.request.requestId,
                        orphan.request.binding,
                        ToolApprovalExpiryReason.PROCESS_RESTART,
                    ),
                )
            }
        val resultIds = turnEvents.mapNotNullTo(mutableSetOf()) {
            (it.payload as? ToolResultRecorded)?.result?.callId
        }
        turnEvents.mapNotNull { it.payload as? ToolCallRecorded }
            .filter { it.call.id !in resultIds }
            .forEach { orphan ->
                eventStore.append(
                    threadId,
                    turnId,
                    ToolResultRecorded(
                        orphan.iterationId,
                        ToolResult(
                            callId = orphan.call.id,
                            status = ToolResultStatus.CANCELLED,
                            output = buildJsonObject { put("code", "PROCESS_RESTART") },
                            message = "Call outcome was not durably recorded before process restart; it was not replayed",
                        ),
                    ),
                )
            }
    }
}

class RecoveryPreparationException(message: String) : IllegalStateException(message)

private fun List<AgentEventEnvelope>.isRecoverable(turnId: TurnId): Boolean {
    val turnEvents = filter { it.turnId == turnId }
    return turnEvents.any { it.payload is TurnStarted } && turnEvents.none {
        it.payload is TurnCompleted || it.payload is TurnFailed || it.payload is TurnCancelled
    }
}

private fun List<AgentEventEnvelope>.isRecoveryPaused(turnId: TurnId): Boolean {
    val turnEvents = filter { it.turnId == turnId }
    val lastPause = turnEvents.indexOfLast { it.payload is TurnRecoveryPaused }
    val lastResume = turnEvents.indexOfLast { it.payload is TurnRecoveryResumed }
    return lastPause >= 0 && lastPause > lastResume
}
