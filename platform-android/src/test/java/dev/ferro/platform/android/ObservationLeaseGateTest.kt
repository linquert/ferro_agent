package dev.ferro.platform.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationLeaseGateTest {
    private val gate = ObservationLeaseGate(maxAgeMs = 1_000)

    @Test
    fun `accepts exact live lease and maps normalized display boundary`() {
        gate.register(observation(id = "obs-new", capturedAt = 5_000))

        val lease = gate.requireCurrent("obs-new", nowEpochMs = 6_000)

        assertEquals(ScreenPoint(0, 0), gate.toPixelPoint(lease, 0.0, 0.0))
        assertEquals(ScreenPoint(1079, 2239), gate.toPixelPoint(lease, 1.0, 1.0))
        assertEquals(ScreenPoint(540, 1120), gate.toPixelPoint(lease, 0.5, 0.5))
        assertEquals("obs-new", lease.id)
    }

    @Test
    fun `rejects absent replaced expired future and out of bounds authority`() {
        assertThrows(IllegalStateException::class.java) {
            gate.requireCurrent("missing", nowEpochMs = 0)
        }
        gate.register(observation(id = "obs-old", capturedAt = 5_000))
        gate.register(observation(id = "obs-new", capturedAt = 6_000))
        assertThrows(IllegalArgumentException::class.java) {
            gate.requireCurrent("obs-old", nowEpochMs = 6_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gate.requireCurrent("obs-new", nowEpochMs = 7_001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            gate.requireCurrent("obs-new", nowEpochMs = 5_999)
        }
        val lease = gate.requireCurrent("obs-new", nowEpochMs = 6_000)
        listOf(-0.01 to 0.0, 0.0 to -0.01, 1.01 to 0.5, 0.5 to 1.01).forEach { (x, y) ->
            assertThrows(IllegalArgumentException::class.java) { gate.toPixelPoint(lease, x, y) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            gate.toPixelPoint(lease, Double.NaN, 0.5)
        }
    }

    @Test
    fun `model observation omits internal authorization and timing material`() {
        val output = observation(id = "screen-short", capturedAt = 5_000).toJson()

        assertEquals("\"screen-short\"", output["observation_id"].toString())
        assertEquals("\"dev.ferro.app\"", output["foreground_package"].toString())
        assertNull(output["ui_state_fingerprint"])
        assertNull(output["ui_event_generation"])
        assertNull(output["captured_at_epoch_ms"])
        assertNull(output["settlement_waited_ms"])
    }

    @Test
    fun `window fingerprint ignores event churn but detects actionable window changes`() {
        val target = AndroidActionableWindow("com.example.target", windowId = 7, windowType = 1)
        val sameBeforeEvents = AndroidUiStateFingerprint.create(target, 1080, 2240, 0)
        val sameAfterEvents = AndroidUiStateFingerprint.create(target, 1080, 2240, 0)

        assertEquals(sameBeforeEvents, sameAfterEvents)
        assertNotEquals(
            sameBeforeEvents,
            AndroidUiStateFingerprint.create(target.copy(windowId = 8), 1080, 2240, 0),
        )
        assertNotEquals(
            sameBeforeEvents,
            AndroidUiStateFingerprint.create(target.copy(packageName = "com.example.other"), 1080, 2240, 0),
        )
    }

    @Test
    fun `authorization evidence can rebind generation without changing referenced screen`() {
        val observed = observation(id = "screen-live", capturedAt = 5_000)

        val evidence = observed.toUiStateEvidence(eventGenerationOverride = 19)

        assertEquals("screen-live", evidence.observationId)
        assertEquals(observed.uiStateFingerprint, evidence.uiStateFingerprint)
        assertEquals(19, evidence.eventGeneration)
    }

    @Test
    fun `referenced screen survives event churn only while package and window identity remain stable`() {
        val window = AndroidActionableWindow("com.example.target", windowId = 7, windowType = 1)
        val observed = observation(
            id = "screen-live",
            capturedAt = 5_000,
            foregroundPackage = window.packageName,
            fingerprint = AndroidUiStateFingerprint.create(window, 1080, 2240, 0),
            eventGeneration = 4,
        )

        assertEquals(true, AndroidUiEvidenceMatcher.matchesReferencedScreen(observed, window))
        assertEquals(
            false,
            AndroidUiEvidenceMatcher.matchesReferencedScreen(
                observed,
                window.copy(packageName = "com.example.other"),
            ),
        )
        assertEquals(
            false,
            AndroidUiEvidenceMatcher.matchesReferencedScreen(observed, window.copy(windowId = 8)),
        )
        assertEquals(true, AndroidUiEvidenceMatcher.matchesAuthorizationGeneration(9, UiMutationState(9)))
        assertEquals(false, AndroidUiEvidenceMatcher.matchesAuthorizationGeneration(9, UiMutationState(10)))
    }

    private fun observation(
        id: String,
        capturedAt: Long,
        foregroundPackage: String = "dev.ferro.app",
        fingerprint: String = "fingerprint-$id",
        eventGeneration: Long = 1,
    ) = AndroidObservation(
        id = id,
        capturedAtEpochMs = capturedAt,
        width = 1080,
        height = 2240,
        rotation = 0,
        foregroundPackage = foregroundPackage,
        uiStateFingerprint = fingerprint,
        eventGeneration = eventGeneration,
        settlementStatus = dev.ferro.contracts.SettlementStatus.SETTLED,
        settlementQuietPeriodMs = 350,
        settlementWaitedMs = 350,
        screenshot = dev.ferro.contracts.ToolAttachmentRef(
            dev.ferro.contracts.ToolAttachmentKind.IMAGE,
            "ferro-screen://$id.png",
            "image/png",
        ),
    )
}
