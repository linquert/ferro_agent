package dev.ferro.runtime.android

import dev.ferro.contracts.AgentOperation
import dev.ferro.contracts.InterruptTurn
import dev.ferro.contracts.PauseTurn
import dev.ferro.contracts.ResumeTurn
import dev.ferro.contracts.AnswerUserRequest
import dev.ferro.contracts.SteerTurn
import dev.ferro.contracts.GrantToolApproval
import dev.ferro.contracts.DenyToolApproval
import dev.ferro.core.AgentSessionPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AgentRuntimeController internal constructor(
    private val scope: CoroutineScope,
    private val sessionFactory: RuntimeSessionFactory,
    private val recoveryRepository: RuntimeRecoveryRepository = RuntimeRecoveryRepository.None,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val commands = Channel<RuntimeCommand>(Channel.UNLIMITED)
    private val mutableView = MutableStateFlow(AgentRuntimeView())
    val view: StateFlow<AgentRuntimeView> = mutableView.asStateFlow()

    private var session: RuntimeSession? = null
    private var activeRecord: ActiveRuntimeRecord? = null
    private var snapshotJob: Job? = null
    private var eventsJob: Job? = null

    init {
        scope.launch {
            for (command in commands) {
                try {
                    process(command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    val recovery = activeRecord?.takeIf { session == null }?.snapshot()
                    mutableView.update { current ->
                        current.copy(
                            snapshot = AgentRuntimeSnapshot(
                                phase = if (recovery != null) {
                                    AgentRuntimePhase.RECOVERY_PAUSED
                                } else {
                                    AgentRuntimePhase.FAILED
                                },
                                session = current.snapshot.session,
                                recovery = recovery,
                                taskTitle = recovery?.goal ?: current.snapshot.taskTitle,
                                errorMessage = error.message ?: error::class.java.simpleName,
                            ),
                        )
                    }
                }
            }
        }
    }

    fun restore() {
        commands.trySend(RuntimeCommand.Restore)
    }

    fun startSession(request: StartAgentRequest) {
        commands.trySend(RuntimeCommand.Start(request))
    }

    fun resumeRecovered(apiKey: String) {
        if (apiKey.isNotBlank()) commands.trySend(RuntimeCommand.ResumeRecovery(apiKey))
    }

    fun submit(operation: AgentOperation) {
        commands.trySend(RuntimeCommand.Submit(operation))
    }

    fun submitBatch(operations: List<AgentOperation>) {
        if (operations.isNotEmpty()) commands.trySend(RuntimeCommand.SubmitBatch(operations))
    }

    fun submitCompanionInput(input: String) {
        commands.trySend(RuntimeCommand.CompanionInput(input.trim()))
    }

    fun pauseActiveTurn() {
        commands.trySend(RuntimeCommand.Pause)
    }

    fun resumeActiveTurn() {
        commands.trySend(RuntimeCommand.Resume)
    }

    fun interruptActiveTurn() {
        commands.trySend(RuntimeCommand.Interrupt)
    }

    fun approvePendingTool() {
        commands.trySend(RuntimeCommand.ApprovePendingTool)
    }

    fun denyPendingTool() {
        commands.trySend(RuntimeCommand.DenyPendingTool)
    }

    fun shutdown() {
        commands.trySend(RuntimeCommand.Shutdown)
    }

    private suspend fun process(command: RuntimeCommand) {
        when (command) {
            RuntimeCommand.Restore -> restoreActiveScope()
            is RuntimeCommand.Start -> replaceSession(command.request)
            is RuntimeCommand.ResumeRecovery -> resumeRecovery(command.apiKey)
            is RuntimeCommand.Submit -> session?.submit(command.operation)
            is RuntimeCommand.SubmitBatch -> command.operations.forEach { session?.submit(it) }
            is RuntimeCommand.CompanionInput -> submitCompanionInputToActiveSession(command.input)
            RuntimeCommand.Pause -> activeTurnOperation(::PauseTurn)?.let { session?.submit(it) }
            RuntimeCommand.Resume -> activeTurnOperation(::ResumeTurn)?.let { session?.submit(it) }
            RuntimeCommand.Interrupt -> interruptOrDiscard()
            RuntimeCommand.ApprovePendingTool -> resolvePendingApproval(approve = true)
            RuntimeCommand.DenyPendingTool -> resolvePendingApproval(approve = false)
            RuntimeCommand.Shutdown -> shutdownCurrentSession()
            is RuntimeCommand.SessionSettled -> settleSession(command.session)
        }
    }

    private suspend fun restoreActiveScope() {
        if (session != null || activeRecord != null) return
        val record = recoveryRepository.restore() ?: return
        activeRecord = record
        if (!recoveryRepository.pauseAfterRestart(record)) {
            recoveryRepository.clear(record)
            activeRecord = null
            return
        }
        observeRecoveryEvents(record)
        mutableView.update { current ->
            current.copy(
                snapshot = AgentRuntimeSnapshot(
                    phase = AgentRuntimePhase.RECOVERY_PAUSED,
                    recovery = record.snapshot(),
                    taskTitle = record.goal,
                ),
            )
        }
    }

    private suspend fun replaceSession(request: StartAgentRequest) {
        check(activeRecord == null || session != null) {
            "Discard or resume the recovered task before starting another task"
        }
        mutableView.value = AgentRuntimeView(
            snapshot = AgentRuntimeSnapshot(
                phase = AgentRuntimePhase.STARTING,
                taskTitle = request.goal.trim(),
            ),
        )
        stopObservers()
        val previousRecord = activeRecord
        session?.shutdownAndJoin()
        if (previousRecord != null) recoveryRepository.clear(previousRecord)
        session = null
        activeRecord = null

        val created = sessionFactory.create(request, scope)
        val record = created.activeRecord(request, nowEpochMs())
        var markerPersisted = false
        try {
            recoveryRepository.persist(record)
            markerPersisted = true
            created.prepareNewStart(request.goal)
        } catch (error: Throwable) {
            if (markerPersisted) {
                try {
                    recoveryRepository.abandon(record)
                    recoveryRepository.clear(record)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // The write-ahead marker remains recoverable if cleanup cannot complete.
                }
            }
            created.shutdownAndJoin()
            throw error
        }
        session = created
        activeRecord = record
        observe(created)
        try {
            created.start(request)
        } catch (error: Throwable) {
            stopObservers()
            session = null
            created.shutdownAndJoin()
            recoveryRepository.pauseAfterRestart(record)
            observeRecoveryEvents(record)
            throw error
        }
    }

    private suspend fun resumeRecovery(apiKey: String) {
        val record = activeRecord ?: return
        check(session == null) { "Recovered task is already running" }
        mutableView.update { current ->
            current.copy(
                snapshot = current.snapshot.copy(
                    phase = AgentRuntimePhase.STARTING,
                    errorMessage = null,
                ),
            )
        }
        val request = StartAgentRequest(
            goal = record.goal,
            providerKind = record.providerKind,
            baseUrl = record.baseUrl,
            model = record.model,
            apiKey = apiKey,
            capabilityScope = record.capabilityScope,
        )
        val created = sessionFactory.create(request, scope, record)
        try {
            created.prepareRecovery()
        } catch (error: Throwable) {
            created.shutdownAndJoin()
            throw error
        }
        session = created
        observe(created)
        try {
            created.start(request)
        } catch (error: Throwable) {
            stopObservers()
            session = null
            created.shutdownAndJoin()
            recoveryRepository.pauseAfterRestart(record)
            observeRecoveryEvents(record)
            throw error
        }
    }

    private suspend fun interruptOrDiscard() {
        val running = session
        if (running != null) {
            running.submit(InterruptTurn(running.turnId))
            return
        }
        val recovered = activeRecord ?: return
        try {
            recoveryRepository.abandon(recovered)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Clearing the active marker still honors an explicit discard when the audit log is unreadable.
        }
        recoveryRepository.clear(recovered)
        activeRecord = null
        stopObservers()
        mutableView.value = AgentRuntimeView()
    }

    private suspend fun submitCompanionInputToActiveSession(input: String) {
        val active = session ?: return
        val snapshot = active.snapshot.value
        when (snapshot.phase) {
            AgentSessionPhase.THINKING,
            AgentSessionPhase.ACTING,
            AgentSessionPhase.PAUSE_REQUESTED,
            -> if (input.isNotEmpty()) active.submit(SteerTurn(active.turnId, input))
            AgentSessionPhase.WAITING_FOR_USER -> {
                val request = snapshot.pendingUserRequest ?: return
                if (input.isNotEmpty()) active.submit(AnswerUserRequest(request.requestId, input))
            }
            AgentSessionPhase.WAITING_FOR_APPROVAL -> Unit
            AgentSessionPhase.PAUSED -> {
                if (input.isNotEmpty()) active.submit(SteerTurn(active.turnId, input))
                active.submit(ResumeTurn(active.turnId))
            }
            AgentSessionPhase.IDLE,
            AgentSessionPhase.INTERRUPTING,
            AgentSessionPhase.SHUTDOWN,
            -> Unit
        }
    }

    private suspend fun shutdownCurrentSession() {
        stopObservers()
        session?.shutdownAndJoin()
        activeRecord?.let { recoveryRepository.clear(it) }
        session = null
        activeRecord = null
        mutableView.value = AgentRuntimeView()
    }

    private suspend fun resolvePendingApproval(approve: Boolean) {
        val active = session ?: return
        val pending = active.snapshot.value.pendingToolApproval ?: return
        active.submit(
            if (approve) {
                GrantToolApproval(pending.requestId, pending.binding)
            } else {
                DenyToolApproval(pending.requestId, pending.binding)
            },
        )
    }

    private suspend fun settleSession(settled: RuntimeSession) {
        if (session !== settled) return
        activeRecord?.let { recoveryRepository.clear(it) }
        activeRecord = null
    }

    private fun activeTurnOperation(factory: (dev.ferro.contracts.TurnId) -> AgentOperation): AgentOperation? =
        session?.turnId?.let(factory)

    private fun observe(observed: RuntimeSession) {
        stopObservers()
        snapshotJob = scope.launch {
            observed.snapshot.collect { sessionSnapshot ->
                if (session !== observed) return@collect
                val runtimePhase = when {
                    sessionSnapshot.phase == AgentSessionPhase.IDLE && sessionSnapshot.lastOutcome == null -> {
                        mutableView.value.snapshot.phase
                    }
                    sessionSnapshot.phase == AgentSessionPhase.IDLE ||
                        sessionSnapshot.phase == AgentSessionPhase.SHUTDOWN -> AgentRuntimePhase.IDLE
                    else -> AgentRuntimePhase.ACTIVE
                }
                mutableView.update { current ->
                    current.copy(
                        snapshot = AgentRuntimeSnapshot(
                            phase = runtimePhase,
                            session = sessionSnapshot,
                            taskTitle = activeRecord?.goal,
                        ),
                    )
                }
                if ((sessionSnapshot.phase == AgentSessionPhase.IDLE && sessionSnapshot.lastOutcome != null) ||
                    sessionSnapshot.phase == AgentSessionPhase.SHUTDOWN
                ) {
                    commands.trySend(RuntimeCommand.SessionSettled(observed))
                }
            }
        }
        eventsJob = scope.launch {
            observed.events.collect { events ->
                if (session === observed) mutableView.update { it.copy(events = events) }
            }
        }
    }

    private fun observeRecoveryEvents(record: ActiveRuntimeRecord) {
        stopObservers()
        eventsJob = scope.launch {
            recoveryRepository.observeEvents(record.threadId).collect { events ->
                if (activeRecord == record && session == null) {
                    mutableView.update { it.copy(events = events) }
                }
            }
        }
    }

    private fun stopObservers() {
        snapshotJob?.cancel()
        eventsJob?.cancel()
        snapshotJob = null
        eventsJob = null
    }
}

private sealed interface RuntimeCommand {
    data object Restore : RuntimeCommand
    data class Start(val request: StartAgentRequest) : RuntimeCommand
    data class ResumeRecovery(val apiKey: String) : RuntimeCommand
    data class Submit(val operation: AgentOperation) : RuntimeCommand
    data class SubmitBatch(val operations: List<AgentOperation>) : RuntimeCommand
    data class CompanionInput(val input: String) : RuntimeCommand
    data object Pause : RuntimeCommand
    data object Resume : RuntimeCommand
    data object Interrupt : RuntimeCommand
    data object ApprovePendingTool : RuntimeCommand
    data object DenyPendingTool : RuntimeCommand
    data object Shutdown : RuntimeCommand
    data class SessionSettled(val session: RuntimeSession) : RuntimeCommand
}
