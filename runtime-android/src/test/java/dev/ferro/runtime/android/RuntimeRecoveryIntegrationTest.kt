package dev.ferro.runtime.android

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentOperation
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.SubmissionId
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.IterationId
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnRecoveryResumed
import dev.ferro.core.AgentSessionPhase
import dev.ferro.core.AgentSessionSnapshot
import dev.ferro.core.FerroClock
import dev.ferro.core.IdGenerator
import dev.ferro.core.InMemoryAgentEventStore
import dev.ferro.core.ToolExecutionContext
import dev.ferro.core.ToolHandler
import dev.ferro.core.ToolRegistry
import dev.ferro.core.ToolRouter
import dev.ferro.core.TurnLifecycleJournal
import dev.ferro.core.TurnRecoveryPreparer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeRecoveryIntegrationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `new controller reconstructs paused scope then resumes same ids after fresh observation`() = runTest {
        val metadataFile = temporaryFolder.newFile("active.json").also { it.delete() }
        val activeStore = FileActiveRuntimeStore(metadataFile)
        val ids = IntegrationIds()
        val events = InMemoryAgentEventStore(ids, IntegrationClock())
        val repository = AndroidRuntimeRecoveryRepository(activeStore, events)
        val firstSession = RecoveryTestSession(
            "session-stable",
            ThreadId("thread-stable"),
            TurnId("turn-stable"),
            events,
            ids,
        )
        val firstController = AgentRuntimeController(
            backgroundScope,
            SingleSessionFactory(firstSession),
            repository,
            nowEpochMs = { 100L },
        )
        val request = StartAgentRequest(
            "Finish settings task",
            RuntimeProviderKind.CHAT_COMPLETIONS,
            "https://example.test/v1",
            "model",
            "first-memory-key",
            testCapabilityScope("scope-recovery"),
        )
        firstController.startSession(request)
        runCurrent()
        assertNotNull(activeStore.load())

        val recoveredSession = RecoveryTestSession(
            "session-stable",
            ThreadId("thread-stable"),
            TurnId("turn-stable"),
            events,
            ids,
        )
        val resumedController = AgentRuntimeController(
            backgroundScope,
            SingleSessionFactory(recoveredSession),
            repository,
        )
        resumedController.restore()
        runCurrent()

        assertEquals(AgentRuntimePhase.RECOVERY_PAUSED, resumedController.view.value.snapshot.phase)
        assertEquals("Finish settings task", resumedController.view.value.snapshot.recovery?.goal)
        resumedController.resumeRecovered("replacement-memory-key")
        runCurrent()

        assertTrue(recoveredSession.recoveryPrepared)
        assertEquals(StartTurn(TurnId("turn-stable"), "Finish settings task"), recoveredSession.operations.single())
        val payloads = events.readThread(ThreadId("thread-stable")).map { it.payload }
        assertEquals(1, payloads.filterIsInstance<TurnRecoveryPaused>().size)
        assertEquals(1, payloads.filterIsInstance<TurnRecoveryResumed>().size)
        assertEquals("session-stable", activeStore.load()?.sessionId)
    }
}

private class SingleSessionFactory(
    private val session: RuntimeSession,
) : RuntimeSessionFactory {
    override fun create(
        request: StartAgentRequest,
        scope: CoroutineScope,
        recovered: ActiveRuntimeRecord?,
    ): RuntimeSession = session
}

private class RecoveryTestSession(
    override val sessionId: String,
    override val threadId: ThreadId,
    override val turnId: TurnId,
    private val eventStore: InMemoryAgentEventStore,
    ids: IntegrationIds,
) : RuntimeSession {
    override val snapshot = MutableStateFlow(
        AgentSessionSnapshot(threadId, AgentSessionPhase.IDLE),
    )
    override val events = MutableStateFlow<List<AgentEventEnvelope>>(emptyList())
    val operations = mutableListOf<AgentOperation>()
    var recoveryPrepared = false
    private val lifecycle = TurnLifecycleJournal(eventStore)
    private val recovery = TurnRecoveryPreparer(
        eventStore,
        ToolRouter(
            ToolRegistry(
                listOf(
                    object : ToolHandler {
                        override val spec = ModelToolSpec(
                            "observe_screen",
                            "capture",
                            inputSchema = buildJsonObject { put("type", "object") },
                        )

                        override suspend fun execute(
                            context: ToolExecutionContext,
                            call: ToolCall,
                        ) = ToolResult(
                            call.id,
                            ToolResultStatus.SUCCESS,
                            output = buildJsonObject { put("observation_id", "fresh-after-restart") },
                        )
                    },
                ),
            ),
        ),
        ids,
    )

    override suspend fun submit(operation: AgentOperation): SubmissionId {
        operations += operation
        return SubmissionId("submission-${operations.size}")
    }

    override suspend fun prepareNewStart(goal: String) {
        lifecycle.ensureStarted(threadId, turnId, goal)
    }

    override suspend fun prepareRecovery() {
        recovery.prepareExplicitResume(threadId, turnId)
        recoveryPrepared = true
    }

    override suspend fun shutdownAndJoin() = Unit
}

private class IntegrationIds : IdGenerator {
    private var next = 0
    private fun id(prefix: String) = "$prefix-${++next}"

    override fun eventId() = id("event")
    override fun threadId() = ThreadId(id("thread"))
    override fun turnId() = TurnId(id("turn"))
    override fun iterationId() = IterationId(id("iteration"))
    override fun toolCallId() = ToolCallId(id("call"))
}

private class IntegrationClock : FerroClock {
    private var now = 0L
    override fun nowEpochMs(): Long = ++now
}
