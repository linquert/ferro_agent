package dev.ferro.platform.android

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.ToolAttachmentRef
import dev.ferro.core.ToolExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AndroidObservation(
    val id: String,
    val capturedAtEpochMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val foregroundPackage: String?,
    val uiStateFingerprint: String,
    val eventGeneration: Long,
    val settlementStatus: SettlementStatus,
    val settlementQuietPeriodMs: Long,
    val settlementWaitedMs: Long,
    val screenshot: ToolAttachmentRef,
    val meanLuminance: Double = 0.0,
    val nearBlackPercent: Double = 0.0,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("observation_id", id)
        put("width", width)
        put("height", height)
        put("rotation", rotation)
        foregroundPackage?.let { put("foreground_package", it) }
        put("settlement_status", settlementStatus.name.lowercase())
    }
}

internal class AndroidScreenObserver(
    context: android.content.Context,
    private val windowResolver: AndroidActionableWindowResolver =
        AndroidActionableWindowResolver(context.applicationContext.packageName),
) {
    private val artifactStore = ScreenArtifactStore(context.applicationContext)
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mutex = Mutex()

    internal suspend fun capture(settlement: UiSettlement): AndroidObservation = mutex.withLock {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            error("Accessibility screenshots require Android 11 or newer")
        }
        withScreenCaptureUiSuppressed {
            val service = AccessibilityServiceRegistry.requireService()
            val generationBefore = AndroidUiMutationRegistry.current().generation
            val windowBefore = windowResolver.resolve()
            val bitmap = takeScreenshotApi30(service)
            val generationAfter = AndroidUiMutationRegistry.current().generation
            val windowAfter = windowResolver.resolve()
            val id = "screen_${System.currentTimeMillis().toString(36)}_${ids.incrementAndGet().toString(36)}"
            try {
                val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
                    ?: Surface.ROTATION_0
                val remainsSettled = settlement.status == SettlementStatus.SETTLED &&
                    generationBefore == settlement.eventGeneration &&
                    generationAfter == generationBefore &&
                    windowBefore == windowAfter &&
                    windowAfter?.packageName == settlement.actionablePackage
                val measurements = bitmap.measureImage()
                AndroidObservation(
                    id = id,
                    capturedAtEpochMs = System.currentTimeMillis(),
                    width = bitmap.width,
                    height = bitmap.height,
                    rotation = rotation,
                    foregroundPackage = windowAfter?.packageName,
                    uiStateFingerprint = AndroidUiStateFingerprint.create(
                        windowAfter,
                        bitmap.width,
                        bitmap.height,
                        rotation,
                    ),
                    eventGeneration = generationAfter,
                    settlementStatus = if (remainsSettled) SettlementStatus.SETTLED else SettlementStatus.TIMED_OUT,
                    settlementQuietPeriodMs = settlement.quietPeriodMs,
                    settlementWaitedMs = settlement.waitedMs,
                    screenshot = artifactStore.writePng(id, bitmap),
                    meanLuminance = measurements.meanLuminance,
                    nearBlackPercent = measurements.nearBlackPercent,
                )
            } finally {
                bitmap.recycle()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotApi30(service: FerroAccessibilityService): Bitmap =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val hardwareBuffer = result.hardwareBuffer
                        try {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                            val software = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            if (software == null) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        ToolExecutionException(
                                            "SCREENSHOT_BUFFER_UNAVAILABLE",
                                            "Android returned no readable screenshot buffer",
                                        ),
                                    )
                                }
                            } else if (continuation.isActive) {
                                continuation.resume(software)
                            } else {
                                software.recycle()
                            }
                        } finally {
                            hardwareBuffer.close()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ToolExecutionException(
                                    "SCREENSHOT_CAPTURE_FAILED",
                                    "Android screenshot callback failed with code $errorCode",
                                ),
                            )
                        }
                    }
                },
            )
    }

    private companion object {
        val ids = AtomicLong()
    }

}

private data class ImageMeasurements(
    val meanLuminance: Double,
    val nearBlackPercent: Double,
)

private fun Bitmap.measureImage(): ImageMeasurements {
    val xStep = (width / 96).coerceAtLeast(1)
    val yStep = (height / 96).coerceAtLeast(1)
    var luminanceTotal = 0.0
    var nearBlack = 0L
    var samples = 0L
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val color = getPixel(x, y)
            val red = android.graphics.Color.red(color) / 255.0
            val green = android.graphics.Color.green(color) / 255.0
            val blue = android.graphics.Color.blue(color) / 255.0
            val luminance = 0.2126 * red + 0.7152 * green + 0.0722 * blue
            luminanceTotal += luminance
            if (luminance <= 0.02) nearBlack++
            samples++
            x += xStep
        }
        y += yStep
    }
    return ImageMeasurements(
        meanLuminance = luminanceTotal / samples.coerceAtLeast(1),
        nearBlackPercent = nearBlack * 100.0 / samples.coerceAtLeast(1),
    )
}
