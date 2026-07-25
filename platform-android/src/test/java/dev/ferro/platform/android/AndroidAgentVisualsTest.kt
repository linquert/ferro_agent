package dev.ferro.platform.android

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAgentVisualsTest {
    @Test
    fun `closing stale cue registration cannot remove newer sink`() {
        val seen = mutableListOf<String>()
        val first = AndroidActionCueRegistry.install(AndroidActionCueSink { seen += "first" })
        val second = AndroidActionCueRegistry.install(AndroidActionCueSink { seen += "second" })

        first.close()
        AndroidActionCueRegistry.show(AndroidActionCue.Tap(1, 2))
        second.close()
        AndroidActionCueRegistry.show(AndroidActionCue.Tap(3, 4))

        assertEquals(listOf("second"), seen)
    }

    @Test
    fun `capture suppression is safe without an installed presentation guard`() = runTest {
        ScreenCaptureUiGuardRegistry.suppress().restore()
    }

    @Test
    fun `closing stale capture registration preserves newer guard`() = runTest {
        val events = mutableListOf<String>()
        val first = ScreenCaptureUiGuardRegistry.install(
            ScreenCaptureUiGuard { ScreenCaptureUiLease { events += "first" } },
        )
        val second = ScreenCaptureUiGuardRegistry.install(
            ScreenCaptureUiGuard { ScreenCaptureUiLease { events += "second" } },
        )

        first.close()
        ScreenCaptureUiGuardRegistry.suppress().restore()
        second.close()

        assertEquals(listOf("second"), events)
    }

    @Test
    fun `capture scope restores presentation after failure and cancellation`() = runTest {
        val events = mutableListOf<String>()
        val registration = ScreenCaptureUiGuardRegistry.install(
            ScreenCaptureUiGuard {
                events += "suppress"
                ScreenCaptureUiLease { events += "restore" }
            },
        )

        runCatching {
            withScreenCaptureUiSuppressed<Unit> { error("capture failed") }
        }
        runCatching {
            withScreenCaptureUiSuppressed<Unit> { throw CancellationException("stopped") }
        }
        registration.close()

        assertEquals(
            listOf("suppress", "restore", "suppress", "restore"),
            events,
        )
    }
}
