package dev.ferro.core

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentOperation
import dev.ferro.contracts.AgentSubmission
import dev.ferro.contracts.AnswerUserRequest
import dev.ferro.contracts.DenyToolApproval
import dev.ferro.contracts.GrantToolApproval
import dev.ferro.contracts.InterruptTurn
import dev.ferro.contracts.PauseTurn
import dev.ferro.contracts.ResumeTurn
import dev.ferro.contracts.ShutdownSession
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.SteerTurn
import dev.ferro.contracts.SubmissionId
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnPauseRequested
import dev.ferro.contracts.TurnPaused
import dev.ferro.contracts.TurnResumed
import dev.ferro.contracts.ToolApprovalExpiryReason
import dev.ferro.contracts.ToolApprovalRequest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AgentSessionPhase {
    IDLE,
    THINKING,
    ACTING,
    PAUSE_REQUESTED,
    PAUSED,
    WAITING_FOR_USER,
    WAITING_FOR_APPROVAL,
    INTERRUPTING,
    SHUTDOWN,
}

data class AgentSessionSnapshot(
    val threadId: ThreadId,
    val phase: AgentSessionPhase,
    val activeTurnId: TurnId? = null,
    val activity: AgentActivity? = null,
    val pendingUserRequest: PendingUserRequest? = null,
    val pendingToolApproval: ToolApprovalRequest? = null,
    val lastOutcome: TurnOutcome? = null,
)

class SessionIo internal constructor(
    private val messages: Channel<SessionMessage>,
    private val actorJob: Job,
    private val submissionIds: AtomicLong,
    val snapshot: StateFlow<AgentSessionSnapshot>,
    val events: Flow<List<AgentEventEnvelope>>,
) {
    suspend fun submit(operation: AgentOperation): SubmissionId {
        val id = SubmissionId("submission-${submissionIds.incrementAndGet()}")
        messages.send(SessionMessage.Submit(AgentSubmission(id, operation)))
        return id
    }

    suspend fun shutdownAndJoin() {
        if (actorJob.isCompleted) return
        submit(ShutdownSession)
        actorJob.join()
    }
}

class AgentSession(
    private val threadId: ThreadId,
    private val turnExecutor: TurnExecutor,
    private val eventStore: AgentEventStore,
    private val userInteractionBroker: UserInteractionBroker = UserInteractionBroker(eventStore),
    private val toolApprovalBroker: ToolApprovalBroker = ToolApprovalBroker(eventStore),
) {
    fun startIn(scope: CoroutineScope): SessionIo {
        val messages = Channel<SessionMessage>(Channel.UNLIMITED)
        val snapshots = MutableStateFlow(AgentSessionSnapshot(threadId, AgentSessionPhase.IDLE))
        val actorJob = scope.launch {
            try {
                processMessages(this, messages, snapshots)
            } finally {
                snapshots.value = snapshots.value.copy(
                    phase = AgentSessionPhase.SHUTDOWN,
                    activeTurnId = null,
                    activity = null,
                    pendingUserRequest = null,
                    pendingToolApproval = null,
                )
                messages.close()
            }
        }
        val requestObserver = scope.launch {
            userInteractionBroker.pending.collect { pending ->
                messages.send(SessionMessage.UserRequestChanged(pending))
            }
        }
        val approvalObserver = scope.launch {
            toolApprovalBroker.pending.collect { pending ->
                messages.send(SessionMessage.ToolApprovalChanged(pending))
            }
        }
        actorJob.invokeOnCompletion {
            requestObserver.cancel()
            approvalObserver.cancel()
        }
        return SessionIo(
            messages = messages,
            actorJob = actorJob,
            submissionIds = AtomicLong(),
            snapshot = snapshots.asStateFlow(),
            events = eventStore.observeThread(threadId),
        )
    }

    private suspend fun processMessages(
        scope: CoroutineScope,
        messages: Channel<SessionMessage>,
        snapshots: MutableStateFlow<AgentSessionSnapshot>,
    ) {
        var activeTurn: ActiveTurn? = null
        var shutdownRequested = false

        fun startTurn(operation: StartTurn) {
            val coordinator = ActorTurnCoordinator(operation.turnId, messages)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                val outcome = try {
                    turnExecutor.run(threadId, operation.turnId, operation.goal, coordinator)
                } catch (cancelled: CancellationException) {
                    TurnOutcome.Cancelled(cancelled.message ?: "Cancelled by user")
                } catch (error: Throwable) {
                    TurnOutcome.Failed(
                        "SESSION_TURN_CRASH",
                        error.message ?: error::class.java.simpleName,
                    )
                }
                withContext(NonCancellable) {
                    messages.send(SessionMessage.TurnFinished(operation.turnId, outcome))
                }
            }
            activeTurn = ActiveTurn(operation.turnId, job)
            snapshots.value = AgentSessionSnapshot(
                threadId = threadId,
                phase = AgentSessionPhase.THINKING,
                activeTurnId = operation.turnId,
                activity = AgentActivity.Thinking,
            )
            job.start()
        }

        for (message in messages) {
            when (message) {
                is SessionMessage.Submit -> when (val operation = message.submission.operation) {
                    is StartTurn -> {
                        if (activeTurn != null || shutdownRequested) continue
                        startTurn(operation)
                    }
                    is SteerTurn -> {
                        val active = activeTurn
                        if (active?.turnId != operation.expectedTurnId ||
                            snapshots.value.phase == AgentSessionPhase.INTERRUPTING
                        ) {
                            continue
                        }
                        active.pendingInput.add(operation.input.trim())
                        toolApprovalBroker.expireTurn(active.turnId, ToolApprovalExpiryReason.STEERED)
                    }
                    is PauseTurn -> {
                        val active = activeTurn
                        if (active?.turnId != operation.expectedTurnId ||
                            snapshots.value.phase == AgentSessionPhase.PAUSED ||
                            snapshots.value.phase == AgentSessionPhase.PAUSE_REQUESTED ||
                            snapshots.value.phase == AgentSessionPhase.INTERRUPTING
                        ) {
                            continue
                        }
                        active.pauseRequested = true
                        toolApprovalBroker.expireTurn(active.turnId, ToolApprovalExpiryReason.PAUSED)
                        eventStore.append(threadId, active.turnId, TurnPauseRequested)
                        snapshots.value = snapshots.value.copy(phase = AgentSessionPhase.PAUSE_REQUESTED)
                    }
                    is ResumeTurn -> {
                        val active = activeTurn
                        if (active?.turnId != operation.expectedTurnId ||
                            snapshots.value.phase != AgentSessionPhase.PAUSED
                        ) {
                            continue
                        }
                        active.pauseRequested = false
                        eventStore.append(threadId, active.turnId, TurnResumed)
                        snapshots.value = snapshots.value.copy(
                            phase = AgentSessionPhase.THINKING,
                            activity = AgentActivity.Thinking,
                        )
                        active.checkpointWaiter?.complete(
                            TurnDirective.Recapture(
                                RecaptureReason.RESUMED,
                                active.pendingInput.drain(),
                            ),
                        )
                        active.checkpointWaiter = null
                    }
                    is InterruptTurn -> {
                        val active = activeTurn
                        if (active?.turnId != operation.expectedTurnId) continue
                        active.pendingInput.clear()
                        active.checkpointWaiter?.cancel(CancellationException("Stopped by user"))
                        active.checkpointWaiter = null
                        userInteractionBroker.cancelTurn(active.turnId, "Stopped by user")
                        toolApprovalBroker.expireTurn(active.turnId, ToolApprovalExpiryReason.INTERRUPTED)
                        snapshots.value = snapshots.value.copy(phase = AgentSessionPhase.INTERRUPTING)
                        active.job.cancel(CancellationException("Stopped by user"))
                    }
                    is AnswerUserRequest -> {
                        userInteractionBroker.answer(operation.requestId, operation.response)
                    }
                    is GrantToolApproval -> {
                        toolApprovalBroker.grant(operation.requestId, operation.expectedBinding)
                    }
                    is DenyToolApproval -> {
                        toolApprovalBroker.deny(operation.requestId, operation.expectedBinding)
                    }
                    ShutdownSession -> {
                        shutdownRequested = true
                        val active = activeTurn
                        if (active == null) return
                        active.checkpointWaiter?.cancel(CancellationException("Session shut down"))
                        userInteractionBroker.cancelTurn(active.turnId, "Session shut down")
                        toolApprovalBroker.expireTurn(active.turnId, ToolApprovalExpiryReason.INTERRUPTED)
                        snapshots.value = snapshots.value.copy(phase = AgentSessionPhase.INTERRUPTING)
                        active.job.cancel(CancellationException("Session shut down"))
                    }
                }
                is SessionMessage.Checkpoint -> {
                    val active = activeTurn
                    if (active?.turnId != message.turnId) {
                        message.reply.cancel(CancellationException("Turn is no longer active"))
                        continue
                    }
                    when {
                        active.pendingInput.isNotEmpty -> {
                            message.reply.complete(
                                TurnDirective.Recapture(
                                    RecaptureReason.USER_INPUT,
                                    active.pendingInput.drain(),
                                ),
                            )
                        }
                        active.pauseRequested -> {
                            active.checkpointWaiter = message.reply
                            active.activity = AgentActivity.Paused
                            eventStore.append(threadId, active.turnId, TurnPaused)
                            snapshots.value = snapshots.value.copy(
                                phase = AgentSessionPhase.PAUSED,
                                activity = AgentActivity.Paused,
                            )
                        }
                        else -> message.reply.complete(TurnDirective.Proceed)
                    }
                }
                is SessionMessage.ActivityChanged -> {
                    val active = activeTurn
                    if (active?.turnId != message.turnId ||
                        snapshots.value.phase == AgentSessionPhase.PAUSED ||
                        snapshots.value.phase == AgentSessionPhase.PAUSE_REQUESTED ||
                        snapshots.value.phase == AgentSessionPhase.WAITING_FOR_USER ||
                        snapshots.value.phase == AgentSessionPhase.WAITING_FOR_APPROVAL ||
                        snapshots.value.phase == AgentSessionPhase.INTERRUPTING
                    ) {
                        continue
                    }
                    active.activity = message.activity
                    snapshots.value = snapshots.value.copy(
                        phase = when (message.activity) {
                            AgentActivity.Thinking -> AgentSessionPhase.THINKING
                            is AgentActivity.UsingTool -> AgentSessionPhase.ACTING
                            is AgentActivity.WaitingForUser -> AgentSessionPhase.WAITING_FOR_USER
                            is AgentActivity.WaitingForApproval -> AgentSessionPhase.WAITING_FOR_APPROVAL
                            AgentActivity.Paused -> AgentSessionPhase.PAUSED
                        },
                        activity = message.activity,
                    )
                }
                is SessionMessage.UserRequestChanged -> {
                    val active = activeTurn
                    val pending = message.pending
                    when {
                        pending != null && active?.turnId == pending.turnId -> {
                            active.activity = AgentActivity.WaitingForUser(pending.prompt)
                            snapshots.value = snapshots.value.copy(
                                phase = AgentSessionPhase.WAITING_FOR_USER,
                                activity = active.activity,
                                pendingUserRequest = pending,
                            )
                        }
                        pending == null && snapshots.value.pendingUserRequest != null -> {
                            val recoveryActivity = AgentActivity.UsingTool("Recovering current screen")
                            active?.activity = recoveryActivity
                            snapshots.value = snapshots.value.copy(
                                phase = AgentSessionPhase.ACTING,
                                activity = recoveryActivity,
                                pendingUserRequest = null,
                            )
                        }
                    }
                }
                is SessionMessage.ToolApprovalChanged -> {
                    val active = activeTurn
                    val pending = message.pending
                    when {
                        pending != null && active?.turnId == pending.binding.turnId -> {
                            active.activity = AgentActivity.WaitingForApproval(pending.actionSummary)
                            snapshots.value = snapshots.value.copy(
                                phase = AgentSessionPhase.WAITING_FOR_APPROVAL,
                                activity = active.activity,
                                pendingToolApproval = pending,
                            )
                        }
                        pending == null && snapshots.value.pendingToolApproval != null -> {
                            val recoveryActivity = AgentActivity.UsingTool("Resolving approval")
                            active?.activity = recoveryActivity
                            snapshots.value = snapshots.value.copy(
                                phase = AgentSessionPhase.ACTING,
                                activity = recoveryActivity,
                                pendingToolApproval = null,
                            )
                        }
                    }
                }
                is SessionMessage.TurnFinished -> {
                    val active = activeTurn
                    if (active?.turnId != message.turnId) continue
                    active.checkpointWaiter?.cancel()
                    userInteractionBroker.cancelTurn(active.turnId, "Turn finished")
                    toolApprovalBroker.expireTurn(active.turnId, ToolApprovalExpiryReason.TURN_FINISHED)
                    activeTurn = null
                    snapshots.value = AgentSessionSnapshot(
                        threadId = threadId,
                        phase = if (shutdownRequested) AgentSessionPhase.SHUTDOWN else AgentSessionPhase.IDLE,
                        lastOutcome = message.outcome,
                    )
                    if (shutdownRequested) return
                }
            }
        }
    }
}

private class ActorTurnCoordinator(
    private val turnId: TurnId,
    private val messages: Channel<SessionMessage>,
) : TurnCoordinator {
    override suspend fun checkpoint(point: TurnCheckpoint): TurnDirective {
        val reply = CompletableDeferred<TurnDirective>()
        messages.send(SessionMessage.Checkpoint(turnId, point, reply))
        return reply.await()
    }

    override suspend fun updateActivity(activity: AgentActivity) {
        messages.send(SessionMessage.ActivityChanged(turnId, activity))
    }
}

internal sealed interface SessionMessage {
    data class Submit(val submission: AgentSubmission) : SessionMessage
    data class Checkpoint(
        val turnId: TurnId,
        val point: TurnCheckpoint,
        val reply: CompletableDeferred<TurnDirective>,
    ) : SessionMessage
    data class ActivityChanged(val turnId: TurnId, val activity: AgentActivity) : SessionMessage
    data class UserRequestChanged(val pending: PendingUserRequest?) : SessionMessage
    data class ToolApprovalChanged(val pending: ToolApprovalRequest?) : SessionMessage
    data class TurnFinished(val turnId: TurnId, val outcome: TurnOutcome) : SessionMessage
}
