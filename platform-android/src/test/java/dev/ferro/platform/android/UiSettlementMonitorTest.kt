package dev.ferro.platform.android

import dev.ferro.contracts.SettlementStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiSettlementMonitorTest {
    @Test
    fun `quiet stable package settles after required evidence window`() = runTest {
        val mutations = MutableMutations()
        val packages = MutablePackage("com.example.target")
        val monitor = monitor(mutations, packages)

        val result = monitor.awaitSettlement()

        assertEquals(SettlementStatus.SETTLED, result.status)
        assertEquals("com.example.target", result.actionablePackage)
        assertTrue(result.quietPeriodMs >= 100)
        assertTrue(result.waitedMs >= 100)
    }

    @Test
    fun `content mutation restarts quiet period`() = runTest {
        val mutations = MutableMutations()
        val packages = MutablePackage("com.example.target")
        val monitor = monitor(mutations, packages)
        launch {
            delay(80)
            mutations.mutate("com.example.target")
        }

        val result = monitor.awaitSettlement()

        assertEquals(SettlementStatus.SETTLED, result.status)
        assertTrue(result.waitedMs >= 180)
        assertEquals(1, result.eventGeneration)
    }

    @Test
    fun `foreground package transition restarts stability period`() = runTest {
        val mutations = MutableMutations()
        val packages = MutablePackage("com.example.first")
        val monitor = monitor(mutations, packages)
        launch {
            delay(80)
            packages.value = "com.example.second"
        }

        val result = monitor.awaitSettlement()

        assertEquals(SettlementStatus.SETTLED, result.status)
        assertEquals("com.example.second", result.actionablePackage)
        assertTrue(result.waitedMs >= 180)
    }

    @Test
    fun `continuously changing UI returns explicit timeout`() = runTest {
        val mutations = MutableMutations()
        val packages = MutablePackage("com.example.target")
        val monitor = monitor(mutations, packages, maximumWaitMs = 260)
        launch {
            repeat(4) {
                delay(60)
                mutations.mutate("com.example.target")
            }
        }

        val result = monitor.awaitSettlement()

        assertEquals(SettlementStatus.TIMED_OUT, result.status)
        assertTrue(result.waitedMs >= 260)
    }

    private fun kotlinx.coroutines.test.TestScope.monitor(
        mutations: MutableMutations,
        packages: MutablePackage,
        maximumWaitMs: Long = 400,
    ) = UiSettlementMonitor(
        mutations = mutations,
        packageProbe = packages,
        nowElapsedMs = { testScheduler.currentTime },
        minDelayMs = 40,
        quietPeriodMs = 100,
        maximumWaitMs = maximumWaitMs,
        pollIntervalMs = 20,
    )

    private class MutableMutations : UiMutationSource {
        private var state = UiMutationState()

        fun mutate(packageName: String) {
            state = UiMutationState(state.generation + 1, packageName)
        }

        override fun current(): UiMutationState = state
    }

    private class MutablePackage(var value: String?) : ActionablePackageProbe {
        override fun currentPackage(): String? = value
    }
}
