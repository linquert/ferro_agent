package dev.ferro.platform.android

import dev.ferro.contracts.SettlementStatus
import kotlin.math.min
import kotlinx.coroutines.delay

internal data class UiMutationState(
    val generation: Long = 0,
    val packageName: String? = null,
)

internal fun interface UiMutationSource {
    fun current(): UiMutationState
}

internal fun interface ActionablePackageProbe {
    fun currentPackage(): String?
}

internal data class UiSettlement(
    val status: SettlementStatus,
    val actionablePackage: String?,
    val eventGeneration: Long,
    val quietPeriodMs: Long,
    val waitedMs: Long,
)

internal class UiSettlementMonitor(
    private val mutations: UiMutationSource,
    private val packageProbe: ActionablePackageProbe,
    private val nowElapsedMs: () -> Long,
    private val minDelayMs: Long = 180,
    private val quietPeriodMs: Long = 350,
    private val maximumWaitMs: Long = 2_500,
    private val pollIntervalMs: Long = 40,
) {
    init {
        require(minDelayMs >= 0)
        require(quietPeriodMs > 0)
        require(maximumWaitMs >= minDelayMs)
        require(pollIntervalMs > 0)
    }

    suspend fun awaitSettlement(): UiSettlement {
        val startedAt = nowElapsedMs()
        var mutation = mutations.current()
        var lastMutationAt = startedAt
        var currentPackage = packageProbe.currentPackage()
        var packageStableSince = startedAt

        while (true) {
            val now = nowElapsedMs()
            val latestMutation = mutations.current()
            if (latestMutation.generation != mutation.generation) {
                mutation = latestMutation
                lastMutationAt = now
            }
            val latestPackage = packageProbe.currentPackage()
            if (latestPackage != currentPackage) {
                currentPackage = latestPackage
                packageStableSince = now
            }
            val waited = now - startedAt
            val quietFor = now - lastMutationAt
            val packageStableFor = now - packageStableSince
            if (waited >= minDelayMs && quietFor >= quietPeriodMs && packageStableFor >= quietPeriodMs) {
                return UiSettlement(
                    SettlementStatus.SETTLED,
                    currentPackage,
                    mutation.generation,
                    min(quietFor, packageStableFor),
                    waited,
                )
            }
            if (waited >= maximumWaitMs) {
                return UiSettlement(
                    SettlementStatus.TIMED_OUT,
                    currentPackage,
                    mutation.generation,
                    min(quietFor, packageStableFor).coerceAtLeast(0),
                    waited,
                )
            }
            delay(min(pollIntervalMs, maximumWaitMs - waited).coerceAtLeast(1))
        }
    }
}
