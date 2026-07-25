package dev.ferro.runtime.android

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.ferro.platform.android.AndroidActionCueRegistry
import dev.ferro.platform.android.AndroidActionCueSink
import dev.ferro.platform.android.ScreenCaptureUiGuard
import dev.ferro.platform.android.ScreenCaptureUiGuardRegistry
import dev.ferro.platform.android.ScreenCaptureUiLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal class CompanionOverlayController(
    private val service: Service,
    private val runtime: AgentRuntimeController,
) : AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayWindow = CompanionOverlayWindow(
        service,
        object : CompanionOverlayActions {
            override fun pause() = runtime.pauseActiveTurn()
            override fun stop() = runtime.interruptActiveTurn()
            override fun submitCompanionInput(input: String) = runtime.submitCompanionInput(input)
            override fun approvePendingTool() = runtime.approvePendingTool()
            override fun denyPendingTool() = runtime.denyPendingTool()
            override fun openFerro() = openFerroActivity()
        },
    )
    private val cueWindow = AgentActionCueWindow(service)
    private var latestView = AgentRuntimeView()
    private var hostVisible = false
    private var suppressionDepth = 0
    private var closed = false

    private val hostRegistration = AgentHostUiVisibility.observe { visible ->
        mainHandler.post {
            hostVisible = visible
            render()
        }
    }
    private val cueRegistration = AndroidActionCueRegistry.install(
        AndroidActionCueSink { cue ->
            mainHandler.post {
                if (canPresentOutsideHost()) runCatching { cueWindow.show(cue) }
            }
        },
    )
    private val captureRegistration = ScreenCaptureUiGuardRegistry.install(
        ScreenCaptureUiGuard { suppressForCapture() },
    )

    fun update(view: AgentRuntimeView) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Overlay updates must run on the main thread" }
        latestView = view
        render()
    }

    override fun close() {
        closed = true
        captureRegistration.close()
        cueRegistration.close()
        hostRegistration.close()
        overlayWindow.close()
        cueWindow.close()
    }

    private fun render() {
        if (!canPresentOutsideHost()) {
            overlayWindow.hide()
            return
        }
        runCatching { overlayWindow.render(CompanionOverlayPolicy.from(latestView)) }
            .onFailure { overlayWindow.hide() }
    }

    private fun canPresentOutsideHost(): Boolean =
        !closed && suppressionDepth == 0 && !hostVisible && Settings.canDrawOverlays(service)

    private suspend fun suppressForCapture(): ScreenCaptureUiLease = withContext(NonCancellable) {
        withContext(Dispatchers.Main.immediate) {
            if (!closed) {
                suppressionDepth++
                overlayWindow.suppress()
                cueWindow.suppress()
            }
        }
        if (!closed) delay(CAPTURE_SURFACE_RELEASE_MS)
        ScreenCaptureUiLease {
            withContext(Dispatchers.Main.immediate + NonCancellable) {
                if (!closed) {
                    suppressionDepth = (suppressionDepth - 1).coerceAtLeast(0)
                    cueWindow.restore()
                    if (suppressionDepth == 0) {
                        overlayWindow.restore()
                        render()
                    }
                }
            }
        }
    }

    private fun openFerroActivity() {
        service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { intent ->
            service.startActivity(
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private companion object {
        const val CAPTURE_SURFACE_RELEASE_MS = 48L
    }
}
