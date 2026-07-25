package dev.ferro.runtime.android

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentOperation
import dev.ferro.contracts.InterruptTurn
import dev.ferro.contracts.PauseTurn
import dev.ferro.contracts.ResumeTurn
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.SteerTurn
import dev.ferro.contracts.SubmissionId
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ThreadStarted
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.AnswerUserRequest
import dev.ferro.contracts.UserRequestId
import dev.ferro.contracts.UserRequestKind
import dev.ferro.core.AgentActivity
import dev.ferro.core.AgentSessionPhase
import dev.ferro.core.AgentSessionSnapshot
import dev.ferro.core.TurnOutcome
import dev.ferro.core.PendingUserRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRuntimeControllerTest {
    @Test
    fun `start creates one session forwards typed start and mirrors its state and events`() = runTest {
        val session = FakeRuntimeSession("turn-1")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(session))

        controller.startSession(request("Inspect settings"))
        runCurrent()

        assertEquals(listOf(StartTurn(TurnId("turn-1"), "Inspect settings")), session.operations)
        session.snapshot.value = activeSnapshot("thread-1", "turn-1", AgentSessionPhase.ACTING)
        session.events.value = listOf(event("thread-1"))
        runCurrent()

        assertEquals(AgentRuntimePhase.ACTIVE, controller.view.value.snapshot.phase)
        assertEquals(AgentSessionPhase.ACTING, controller.view.value.snapshot.session?.phase)
        assertEquals(1, controller.view.value.events.size)
    }

    @Test
    fun `replacement shuts down previous owner and ignores its later emissions`() = runTest {
        val first = FakeRuntimeSession("turn-1")
        val second = FakeRuntimeSession("turn-2")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(first, second))

        controller.startSession(request("First"))
        controller.startSession(request("Second"))
        runCurrent()

        assertTrue(first.shutdown)
        assertFalse(second.shutdown)
        first.snapshot.value = activeSnapshot("old-thread", "turn-1", AgentSessionPhase.PAUSED)
        first.events.value = listOf(event("old-thread"))
        second.snapshot.value = activeSnapshot("new-thread", "turn-2", AgentSessionPhase.THINKING)
        second.events.value = listOf(event("new-thread"))
        runCurrent()

        assertEquals(ThreadId("new-thread"), controller.view.value.snapshot.session?.threadId)
        assertEquals(ThreadId("new-thread"), controller.view.value.events.single().threadId)
    }

    @Test
    fun `notification commands target only the active turn through typed operations`() = runTest {
        val session = FakeRuntimeSession("turn-7")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(session))
        controller.startSession(request("Control test"))
        session.snapshot.value = activeSnapshot("thread-7", "turn-7", AgentSessionPhase.THINKING)
        runCurrent()

        controller.pauseActiveTurn()
        controller.resumeActiveTurn()
        controller.interruptActiveTurn()
        runCurrent()

        assertEquals(
            listOf(
                StartTurn(TurnId("turn-7"), "Control test"),
                PauseTurn(TurnId("turn-7")),
                ResumeTurn(TurnId("turn-7")),
                InterruptTurn(TurnId("turn-7")),
            ),
            session.operations,
        )
    }

    @Test
    fun `terminal session becomes idle while retaining outcome and trace`() = runTest {
        val session = FakeRuntimeSession("turn-9")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(session))
        controller.startSession(request("Finish"))
        session.events.value = listOf(event("thread-9"))
        session.snapshot.value = AgentSessionSnapshot(
            threadId = ThreadId("thread-9"),
            phase = AgentSessionPhase.IDLE,
            lastOutcome = TurnOutcome.Completed("Done"),
        )
        runCurrent()

        assertEquals(AgentRuntimePhase.IDLE, controller.view.value.snapshot.phase)
        assertEquals(TurnOutcome.Completed("Done"), controller.view.value.snapshot.session?.lastOutcome)
        assertEquals(1, controller.view.value.events.size)
    }

    @Test
    fun `ordered batch keeps steer before resume at the session boundary`() = runTest {
        val session = FakeRuntimeSession("turn-11")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(session))
        controller.startSession(request("Batch"))
        runCurrent()

        controller.submitBatch(
            listOf(
                SteerTurn(TurnId("turn-11"), "Use the second account"),
                ResumeTurn(TurnId("turn-11")),
            ),
        )
        runCurrent()

        assertEquals(
            listOf(
                StartTurn(TurnId("turn-11"), "Batch"),
                SteerTurn(TurnId("turn-11"), "Use the second account"),
                ResumeTurn(TurnId("turn-11")),
            ),
            session.operations,
        )
    }

    @Test
    fun `companion input is routed by authoritative session phase`() = runTest {
        val session = FakeRuntimeSession("turn-companion")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(session))
        controller.startSession(request("Companion routing"))
        runCurrent()

        session.snapshot.value = activeSnapshot("thread-turn-companion", "turn-companion", AgentSessionPhase.THINKING)
        controller.submitCompanionInput("Use the work account")
        runCurrent()

        val pending = PendingUserRequest(
            session.threadId,
            session.turnId,
            UserRequestId("request-1"),
            UserRequestKind.INPUT,
            "Which account?",
        )
        session.snapshot.value = AgentSessionSnapshot(
            threadId = session.threadId,
            phase = AgentSessionPhase.WAITING_FOR_USER,
            activeTurnId = session.turnId,
            activity = AgentActivity.WaitingForUser(pending.prompt),
            pendingUserRequest = pending,
        )
        controller.submitCompanionInput("Work")
        runCurrent()

        session.snapshot.value = activeSnapshot("thread-turn-companion", "turn-companion", AgentSessionPhase.PAUSED)
        controller.submitCompanionInput("Continue carefully")
        runCurrent()

        assertEquals(
            listOf(
                StartTurn(session.turnId, "Companion routing"),
                SteerTurn(session.turnId, "Use the work account"),
                AnswerUserRequest(UserRequestId("request-1"), "Work"),
                SteerTurn(session.turnId, "Continue carefully"),
                ResumeTurn(session.turnId),
            ),
            session.operations,
        )
    }

    @Test
    fun `one rejected command reports failure without killing later runtime commands`() = runTest {
        val session = FakeRuntimeSession("turn-12")
        val controller = AgentRuntimeController(backgroundScope, QueueSessionFactory(session))
        controller.startSession(request("Recover command actor"))
        runCurrent()
        session.nextFailure = IllegalStateException("closed session channel")

        controller.pauseActiveTurn()
        runCurrent()
        assertEquals(AgentRuntimePhase.FAILED, controller.view.value.snapshot.phase)
        assertEquals("closed session channel", controller.view.value.snapshot.errorMessage)

        controller.resumeActiveTurn()
        runCurrent()
        assertTrue(session.operations.last() is ResumeTurn)
    }

    @Test
    fun `restore exposes durable scope paused without constructing or starting a session`() = runTest {
        val record = recoveryRecord()
        val repository = FakeRecoveryRepository(record)
        val factory = QueueSessionFactory(FakeRuntimeSession("unused"))
        val controller = AgentRuntimeController(backgroundScope, factory, repository)

        controller.restore()
        runCurrent()
        repository.events.value = listOf(event(record.threadId.value))
        runCurrent()

        assertEquals(1, repository.pauseCalls)
        assertEquals(AgentRuntimePhase.RECOVERY_PAUSED, controller.view.value.snapshot.phase)
        assertEquals(record.snapshot(), controller.view.value.snapshot.recovery)
        assertEquals(record.threadId, controller.view.value.events.single().threadId)
        assertEquals(0, factory.createCalls)
    }

    @Test
    fun `explicit recovery prepares fresh screen before restarting exact durable turn`() = runTest {
        val record = recoveryRecord()
        val repository = FakeRecoveryRepository(record)
        val recoveredSession = FakeRuntimeSession(
            turn = record.turnId.value,
            thread = record.threadId.value,
            session = record.sessionId,
        )
        val factory = QueueSessionFactory(recoveredSession)
        val controller = AgentRuntimeController(backgroundScope, factory, repository)
        controller.restore()
        runCurrent()

        controller.resumeRecovered("memory-only-key")
        runCurrent()

        assertEquals(record, factory.recoveredRecords.single())
        assertTrue(recoveredSession.recoveryPrepared)
        assertEquals(
            StartTurn(record.turnId, record.goal),
            recoveredSession.operations.single(),
        )
        assertTrue(repository.persisted.isEmpty())
    }

    @Test
    fun `stale metadata is cleared instead of presenting a terminal turn as recoverable`() = runTest {
        val record = recoveryRecord()
        val repository = FakeRecoveryRepository(record).apply { recoverable = false }
        val controller = AgentRuntimeController(
            backgroundScope,
            QueueSessionFactory(FakeRuntimeSession("unused")),
            repository,
        )

        controller.restore()
        runCurrent()

        assertEquals(listOf(record.sessionId), repository.clearedSessionIds)
        assertEquals(AgentRuntimePhase.IDLE, controller.view.value.snapshot.phase)
        assertEquals(null, controller.view.value.snapshot.recovery)
    }

    @Test
    fun `discarding recovered task records abandonment and clears only its metadata`() = runTest {
        val record = recoveryRecord()
        val repository = FakeRecoveryRepository(record)
        val controller = AgentRuntimeController(
            backgroundScope,
            QueueSessionFactory(FakeRuntimeSession("unused")),
            repository,
        )
        controller.restore()
        runCurrent()

        controller.interruptActiveTurn()
        runCurrent()

        assertEquals(listOf(record), repository.abandoned)
        assertEquals(listOf(record.sessionId), repository.clearedSessionIds)
        assertEquals(AgentRuntimePhase.IDLE, controller.view.value.snapshot.phase)
    }

    @Test
    fun `new session metadata is durable before start and clears on terminal settlement`() = runTest {
        val lifecycle = mutableListOf<String>()
        val session = FakeRuntimeSession("turn-durable", lifecycle = lifecycle)
        val repository = FakeRecoveryRepository(lifecycle = lifecycle)
        val controller = AgentRuntimeController(
            backgroundScope,
            QueueSessionFactory(session),
            repository,
            nowEpochMs = { 77L },
        )

        controller.startSession(request("Durable start"))
        runCurrent()

        val persisted = repository.persisted.single()
        assertEquals(session.sessionId, persisted.sessionId)
        assertEquals(77L, persisted.startedAtEpochMs)
        assertEquals(StartTurn(session.turnId, "Durable start"), session.operations.single())
        assertEquals(listOf("persist", "prepare", "submit"), lifecycle.take(3))

        session.snapshot.value = AgentSessionSnapshot(
            threadId = session.threadId,
            phase = AgentSessionPhase.IDLE,
            lastOutcome = TurnOutcome.Completed("done"),
        )
        runCurrent()
        assertEquals(listOf(session.sessionId), repository.clearedSessionIds)
    }

    @Test
    fun `metadata failure never creates a turn and shuts down unowned session`() = runTest {
        val session = FakeRuntimeSession("turn-write-failure")
        val repository = FakeRecoveryRepository().apply {
            persistFailure = IllegalStateException("disk full")
        }
        val controller = AgentRuntimeController(
            backgroundScope,
            QueueSessionFactory(session),
            repository,
        )

        controller.startSession(request("Must be durable"))
        runCurrent()

        assertFalse(session.newStartPrepared)
        assertTrue(session.shutdown)
        assertTrue(repository.abandoned.isEmpty())
        assertTrue(session.operations.isEmpty())
        assertEquals(AgentRuntimePhase.FAILED, controller.view.value.snapshot.phase)
    }

    @Test
    fun `fresh-screen recovery failure remains paused and can be retried`() = runTest {
        val record = recoveryRecord()
        val repository = FakeRecoveryRepository(record)
        val session = FakeRuntimeSession(
            record.turnId.value,
            record.threadId.value,
            record.sessionId,
        ).apply {
            recoveryFailure = IllegalStateException("accessibility unavailable")
        }
        val controller = AgentRuntimeController(
            backgroundScope,
            QueueSessionFactory(session),
            repository,
        )
        controller.restore()
        runCurrent()

        controller.resumeRecovered("memory-key")
        runCurrent()

        assertTrue(session.shutdown)
        assertTrue(session.operations.isEmpty())
        assertEquals(AgentRuntimePhase.RECOVERY_PAUSED, controller.view.value.snapshot.phase)
        assertEquals("accessibility unavailable", controller.view.value.snapshot.errorMessage)
        assertEquals(record.snapshot(), controller.view.value.snapshot.recovery)
    }

    @Test
    fun `recovered start submission failure is shut down and re-paused for retry`() = runTest {
        val record = recoveryRecord()
        val repository = FakeRecoveryRepository(record)
        val session = FakeRuntimeSession(
            record.turnId.value,
            record.threadId.value,
            record.sessionId,
        ).apply {
            nextFailure = IllegalStateException("session channel unavailable")
        }
        val controller = AgentRuntimeController(
            backgroundScope,
            QueueSessionFactory(session),
            repository,
        )
        controller.restore()
        runCurrent()

        controller.resumeRecovered("memory-key")
        runCurrent()

        assertTrue(session.recoveryPrepared)
        assertTrue(session.shutdown)
        assertTrue(session.operations.isEmpty())
        assertEquals(2, repository.pauseCalls)
        assertEquals(AgentRuntimePhase.RECOVERY_PAUSED, controller.view.value.snapshot.phase)
        assertEquals("session channel unavailable", controller.view.value.snapshot.errorMessage)
        assertEquals(record.snapshot(), controller.view.value.snapshot.recovery)
    }

    private fun request(goal: String) = StartAgentRequest(
        goal,
        RuntimeProviderKind.CHAT_COMPLETIONS,
        "https://example.test/v1",
        "test-model",
        "memory-only-key",
        testCapabilityScope(),
    )

    private fun recoveryRecord() = ActiveRuntimeRecord(
        sessionId = "session-recovery",
        threadId = ThreadId("thread-recovery"),
        turnId = TurnId("turn-recovery"),
        goal = "Continue recovered task",
        providerKind = RuntimeProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.test/v1",
        model = "test-model",
        startedAtEpochMs = 10,
        capabilityScope = testCapabilityScope(),
        capabilityScopeHash = dev.ferro.core.ToolAuthorizationHashes.scope(testCapabilityScope()),
    )

    private fun activeSnapshot(
        thread: String,
        turn: String,
        phase: AgentSessionPhase,
    ) = AgentSessionSnapshot(
        threadId = ThreadId(thread),
        phase = phase,
        activeTurnId = TurnId(turn),
        activity = if (phase == AgentSessionPhase.ACTING) AgentActivity.UsingTool("Testing tool") else AgentActivity.Thinking,
    )

    private fun event(thread: String) = AgentEventEnvelope(
        eventId = "event-$thread",
        threadId = ThreadId(thread),
        sequence = 1,
        timestampEpochMs = 1,
        payload = ThreadStarted("Test"),
    )
}

private class QueueSessionFactory(vararg sessions: FakeRuntimeSession) : RuntimeSessionFactory {
    private val queue = ArrayDeque(sessions.toList())
    val recoveredRecords = mutableListOf<ActiveRuntimeRecord?>()
    var createCalls = 0

    override fun create(
        request: StartAgentRequest,
        scope: kotlinx.coroutines.CoroutineScope,
        recovered: ActiveRuntimeRecord?,
    ): RuntimeSession =
        queue.removeFirst().also {
            createCalls++
            recoveredRecords += recovered
        }
}

private class FakeRuntimeSession(
    turn: String,
    thread: String = "thread-$turn",
    session: String = "session-$turn",
    private val lifecycle: MutableList<String>? = null,
) : RuntimeSession {
    override val sessionId = session
    override val threadId = ThreadId(thread)
    override val turnId = TurnId(turn)
    override val snapshot = MutableStateFlow(
        AgentSessionSnapshot(ThreadId("thread-$turn"), AgentSessionPhase.IDLE),
    )
    override val events = MutableStateFlow<List<AgentEventEnvelope>>(emptyList())
    val operations = mutableListOf<AgentOperation>()
    var shutdown = false
    var nextFailure: Throwable? = null
    var recoveryPrepared = false
    var newStartPrepared = false
    var recoveryFailure: Throwable? = null

    override suspend fun submit(operation: AgentOperation): SubmissionId {
        nextFailure?.let { failure ->
            nextFailure = null
            throw failure
        }
        lifecycle?.add("submit")
        operations += operation
        return SubmissionId("submission-${operations.size}")
    }

    override suspend fun shutdownAndJoin() {
        shutdown = true
    }

    override suspend fun prepareRecovery() {
        recoveryFailure?.let { throw it }
        recoveryPrepared = true
    }

    override suspend fun prepareNewStart(goal: String) {
        lifecycle?.add("prepare")
        newStartPrepared = true
    }
}

private class FakeRecoveryRepository(
    private val restored: ActiveRuntimeRecord? = null,
    private val lifecycle: MutableList<String>? = null,
) : RuntimeRecoveryRepository {
    val events = MutableStateFlow<List<AgentEventEnvelope>>(emptyList())
    val persisted = mutableListOf<ActiveRuntimeRecord>()
    val abandoned = mutableListOf<ActiveRuntimeRecord>()
    val clearedSessionIds = mutableListOf<String>()
    var pauseCalls = 0
    var recoverable = true
    var persistFailure: Throwable? = null

    override suspend fun restore(): ActiveRuntimeRecord? = restored

    override suspend fun persist(record: ActiveRuntimeRecord) {
        persistFailure?.let { throw it }
        lifecycle?.add("persist")
        persisted += record
    }

    override suspend fun pauseAfterRestart(record: ActiveRuntimeRecord): Boolean {
        pauseCalls++
        return recoverable
    }

    override suspend fun abandon(record: ActiveRuntimeRecord) {
        abandoned += record
    }

    override suspend fun clear(record: ActiveRuntimeRecord) {
        clearedSessionIds += record.sessionId
    }

    override fun observeEvents(threadId: ThreadId) = events
}
