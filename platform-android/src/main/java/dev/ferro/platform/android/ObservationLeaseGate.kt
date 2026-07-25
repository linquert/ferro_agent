package dev.ferro.platform.android

import kotlin.math.roundToInt

internal data class ObservationLease(
    val id: String,
    val capturedAtEpochMs: Long,
    val width: Int,
    val height: Int,
)

internal data class ScreenPoint(val x: Int, val y: Int)

internal class ObservationLeaseGate(
    private val maxAgeMs: Long = 30_000L,
) {
    private var latest: ObservationLease? = null

    fun register(observation: AndroidObservation) {
        latest = ObservationLease(
            observation.id,
            observation.capturedAtEpochMs,
            observation.width,
            observation.height,
        )
    }

    fun requireCurrent(observationId: String, nowEpochMs: Long): ObservationLease {
        val observation = latest ?: error("No screen observation is available")
        require(observation.id == observationId) { "Observation is stale; observe the screen again" }
        require(nowEpochMs - observation.capturedAtEpochMs in 0..maxAgeMs) {
            "Observation is too old; observe the screen again"
        }
        return observation
    }

    fun toPixelPoint(observation: ObservationLease, x: Double, y: Double): ScreenPoint {
        require(x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0) {
            "Normalized coordinates must be finite values from 0.0 to 1.0"
        }
        return ScreenPoint(
            x = (x * (observation.width - 1)).roundToInt(),
            y = (y * (observation.height - 1)).roundToInt(),
        )
    }
}
