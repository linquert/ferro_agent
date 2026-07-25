package dev.ferro.core

import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.InterruptTurn
import dev.ferro.contracts.ModelResponse
import dev.ferro.contracts.ModelStopReason
import dev.ferro.contracts.PauseTurn
import dev.ferro.contracts.ResumeTurn
import dev.ferro.contracts.ShutdownSession
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.SteerTurn
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.TurnPaused
import dev.ferro.contracts.TurnStarted
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentSessionTest {
    @Test
    fun `session drives a real turn runner to a durable terminal outcome`() = runTest {
        val fixture = Fixture()
        val router = ToolRouter(ToolRegistry(emptyList()))
        val runner = AgentTurnLoop(
            eventStore = fixture.store,
            contextBuilder = EventSourcedModelContextBuilder(fixture.store, router),
            provider = ModelProvider {
                ModelResponse(message = "Finished", stopReason = ModelStopReason.COMPLETE)
            },
            authorizationGate = testAuthorizationGate(router, fixture.store),
            ids = fixture.ids,
        )
        val io = fixture.session(runner).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Complete the task"))
        advanceUntilIdle()

        assertEquals(AgentSessionPhase.IDLE, io.snapshot.value.phase)
        assertEquals(TurnOutcome.Completed("Finished"), io.snapshot.value.lastOutcome)
        val payloads = fixture.payloads()
        assertTrue(payloads.any { it is TurnStarted })
        assertTrue(payloads.last() is TurnCompleted)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `a second start cannot replace the active turn`() = runTest {
        val fixture = Fixture()
        val startedTurns = mutableListOf<TurnId>()
        val executor = TurnExecutor { _, turnId, _, _ ->
            startedTurns += turnId
            awaitCancellation()
        }
        val io = fixture.session(executor).startIn(this)

        io.submit(StartTurn(fixture.turnId, "First task"))
        io.submit(StartTurn(TurnId("other-turn"), "Second task"))
        advanceUntilIdle()

        assertEquals(listOf(fixture.turnId), startedTurns)
        assertEquals(fixture.turnId, io.snapshot.value.activeTurnId)

        io.submit(InterruptTurn(fixture.turnId))
        advanceUntilIdle()
        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `steering is scoped to active turn and forces context recapture`() = runTest {
        val fixture = Fixture()
        val allowCheckpoint = CompletableDeferred<Unit>()
        val directives = mutableListOf<TurnDirective>()
        val executor = TurnExecutor { _, _, _, coordinator ->
            allowCheckpoint.await()
            directives += coordinator.checkpoint(TurnCheckpoint.AFTER_MODEL_RESPONSE)
            TurnOutcome.Completed("steered")
        }
        val io = fixture.session(executor).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Initial task"))
        advanceUntilIdle()
        io.submit(SteerTurn(TurnId("stale-turn"), "Ignored"))
        io.submit(SteerTurn(fixture.turnId, "Use the second option"))
        allowCheckpoint.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, directives.size)
        val recapture = directives.single() as TurnDirective.Recapture
        assertEquals(RecaptureReason.USER_INPUT, recapture.reason)
        assertEquals(listOf("Use the second option"), recapture.inputs)
        assertEquals(AgentSessionPhase.IDLE, io.snapshot.value.phase)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `pause waits for checkpoint and resume continues the same turn`() = runTest {
        val fixture = Fixture()
        val allowCheckpoint = CompletableDeferred<Unit>()
        var directive: TurnDirective? = null
        val executor = TurnExecutor { _, _, _, coordinator ->
            allowCheckpoint.await()
            directive = coordinator.checkpoint(TurnCheckpoint.BEFORE_TOOL)
            TurnOutcome.Completed("resumed")
        }
        val io = fixture.session(executor).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Initial task"))
        advanceUntilIdle()
        io.submit(PauseTurn(fixture.turnId))
        advanceUntilIdle()
        assertEquals(AgentSessionPhase.PAUSE_REQUESTED, io.snapshot.value.phase)

        allowCheckpoint.complete(Unit)
        advanceUntilIdle()
        assertEquals(AgentSessionPhase.PAUSED, io.snapshot.value.phase)
        assertEquals(fixture.turnId, io.snapshot.value.activeTurnId)
        assertTrue(fixture.payloads().any { it is TurnPaused })

        io.submit(ResumeTurn(fixture.turnId))
        advanceUntilIdle()
        assertEquals(
            TurnDirective.Recapture(RecaptureReason.RESUMED),
            directive,
        )
        assertEquals(AgentSessionPhase.IDLE, io.snapshot.value.phase)
        assertEquals(TurnOutcome.Completed("resumed"), io.snapshot.value.lastOutcome)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `stale pause and interrupt cannot affect active turn`() = runTest {
        val fixture = Fixture()
        val executor = TurnExecutor { _, _, _, _ -> awaitCancellation() }
        val io = fixture.session(executor).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Wait"))
        advanceUntilIdle()
        io.submit(PauseTurn(TurnId("stale-turn")))
        io.submit(InterruptTurn(TurnId("stale-turn")))
        advanceUntilIdle()

        assertEquals(AgentSessionPhase.THINKING, io.snapshot.value.phase)
        assertEquals(fixture.turnId, io.snapshot.value.activeTurnId)

        io.submit(InterruptTurn(fixture.turnId))
        advanceUntilIdle()
        assertEquals(TurnOutcome.Cancelled("Stopped by user"), io.snapshot.value.lastOutcome)

        io.submit(ShutdownSession)
        advanceUntilIdle()
    }

    @Test
    fun `shutdown cancels active work and closes the session after settlement`() = runTest {
        val fixture = Fixture()
        val executor = TurnExecutor { _, _, _, _ -> awaitCancellation() }
        val io = fixture.session(executor).startIn(this)

        io.submit(StartTurn(fixture.turnId, "Long task"))
        advanceUntilIdle()
        io.submit(ShutdownSession)
        advanceUntilIdle()

        assertEquals(AgentSessionPhase.SHUTDOWN, io.snapshot.value.phase)
        assertEquals(null, io.snapshot.value.activeTurnId)
        assertEquals(TurnOutcome.Cancelled("Session shut down"), io.snapshot.value.lastOutcome)
    }

    private class Fixture {
        val ids = SequentialIdGenerator()
        val store = InMemoryAgentEventStore(ids, IncrementingClock())
        val threadId = ThreadId("thread")
        val turnId = TurnId("turn")

        fun session(executor: TurnExecutor) = AgentSession(threadId, executor, store)

        suspend fun payloads(): List<AgentEventPayload> =
            store.readThread(threadId).map { it.payload }
    }
}
