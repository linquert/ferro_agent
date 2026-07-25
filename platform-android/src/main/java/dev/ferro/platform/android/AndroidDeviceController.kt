package dev.ferro.platform.android

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import dev.ferro.contracts.SettlementStatus
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.UiStateEvidence
import dev.ferro.core.ToolAuthorizationHashes
import dev.ferro.core.ToolExecutionContext
import dev.ferro.core.ToolExecutionException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AndroidDeviceController(context: Context) {
    private val applicationContext = context.applicationContext
    private val windowResolver = AndroidActionableWindowResolver(applicationContext.packageName)
    private val settlementMonitor = newAndroidUiSettlementMonitor(windowResolver)
    private val observer = AndroidScreenObserver(applicationContext, windowResolver)
    private val environmentInspector = AndroidEnvironmentInspector(applicationContext, windowResolver)
    private val actionMutex = Mutex()
    private val observationGate = ObservationLeaseGate()
    @Volatile private var latestObservation: AndroidObservation? = null

    suspend fun observe(): AndroidObservation = actionMutex.withLock { captureSettled() }

    suspend fun inspectEnvironment(appQuery: String?): AndroidEnvironmentInspection = actionMutex.withLock {
        val observation = captureSettled()
        environmentInspector.inspect(observation, appQuery)
    }

    internal fun latestObservationId(): String? = latestObservation?.id

    suspend fun tap(
        context: ToolExecutionContext,
        call: ToolCall,
        observationId: String,
        x: Double,
        y: Double,
    ): AndroidObservation = actionMutex.withLock {
        val before = requireAuthorized(context, call, observationId)
        val point = observationGate.toPixelPoint(before.lease(), x, y)
        dispatchGesture(
            path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) },
            durationMs = 80,
            onAccepted = {
                AndroidActionCueRegistry.show(AndroidActionCue.Tap(point.x, point.y))
            },
        )
        captureAfterAction()
    }

    suspend fun swipe(
        context: ToolExecutionContext,
        call: ToolCall,
        observationId: String,
        startX: Double,
        startY: Double,
        endX: Double,
        endY: Double,
        durationMs: Long,
    ): AndroidObservation = actionMutex.withLock {
        val before = requireAuthorized(context, call, observationId)
        val start = observationGate.toPixelPoint(before.lease(), startX, startY)
        val end = observationGate.toPixelPoint(before.lease(), endX, endY)
        require(durationMs in 100..3_000) { "Swipe duration must be between 100 and 3000 ms" }
        dispatchGesture(
            Path().apply {
                moveTo(start.x.toFloat(), start.y.toFloat())
                lineTo(end.x.toFloat(), end.y.toFloat())
            },
            durationMs,
            onAccepted = {
                AndroidActionCueRegistry.show(
                    AndroidActionCue.Swipe(
                        start.x,
                        start.y,
                        end.x,
                        end.y,
                        durationMs,
                    ),
                )
            },
        )
        captureAfterAction()
    }

    suspend fun typeText(
        context: ToolExecutionContext,
        call: ToolCall,
        observationId: String,
        text: String,
    ): AndroidObservation = actionMutex.withLock {
        requireAuthorized(context, call, observationId)
        require(text.isNotEmpty()) { "Text must not be empty" }
        require(text.length <= MAX_TEXT_LENGTH) { "Text is too long" }
        val service = AccessibilityServiceRegistry.requireService()
        val focused = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: error("No focused input field is available")
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        check(focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            "Focused field rejected text input"
        }
        captureAfterAction()
    }

    suspend fun keyAction(
        context: ToolExecutionContext,
        call: ToolCall,
        observationId: String,
        action: String,
    ): AndroidObservation = actionMutex.withLock {
        requireAuthorized(context, call, observationId)
        val globalAction = when (action.lowercase(Locale.US)) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            else -> error("Unsupported key action: $action")
        }
        check(AccessibilityServiceRegistry.requireService().performGlobalAction(globalAction)) {
            "Android rejected global action $action"
        }
        captureAfterAction()
    }

    suspend fun openApp(
        context: ToolExecutionContext,
        call: ToolCall,
        packageName: String,
    ): AndroidObservation = actionMutex.withLock {
        requireUnboundAuthorized(context, call)
        require(PACKAGE_NAME.matches(packageName)) { "Invalid Android package name" }
        val launchIntent = applicationContext.packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launchable app found for package $packageName")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        applicationContext.startActivity(launchIntent)
        captureAfterAction("accepted")
    }

    suspend fun waitFor(
        context: ToolExecutionContext,
        call: ToolCall,
        durationMs: Long,
    ): AndroidObservation = actionMutex.withLock {
        requireUnboundAuthorized(context, call)
        require(durationMs in 0..MAX_WAIT_MS) { "Wait must be between 0 and $MAX_WAIT_MS ms" }
        delay(durationMs)
        captureAfterAction()
    }

    private suspend fun dispatchGesture(
        path: Path,
        durationMs: Long,
        onAccepted: () -> Unit,
    ) {
        val service = AccessibilityServiceRegistry.requireService()
        suspendCancellableCoroutine { continuation ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val accepted = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                ToolExecutionException(
                                    code = "GESTURE_CANCELLED",
                                    message = "Android cancelled the gesture",
                                    dispatch = "dispatched",
                                    platformOutcome = "cancelled",
                                ),
                            )
                        }
                    }
                },
                null,
            )
            if (!accepted && continuation.isActive) {
                continuation.resumeWithException(
                    ToolExecutionException("GESTURE_REJECTED", "Android rejected the gesture"),
                )
            } else if (accepted) {
                onAccepted()
            }
        }
    }

    private fun requireCurrent(observationId: String): AndroidObservation {
        val observation = latestObservation ?: error("No screen observation is available")
        observationGate.requireCurrent(observationId, System.currentTimeMillis())
        return observation
    }

    internal suspend fun unboundEvidence(): UiStateEvidence = actionMutex.withLock {
        val generation = AndroidUiMutationRegistry.current().generation
        val window = windowResolver.resolve()
        UiStateEvidence(
            observationId = "unbound-$generation",
            actionablePackage = window?.packageName,
            uiStateFingerprint = AndroidUiStateFingerprint.create(window, 0, 0, 0),
            capturedAtEpochMs = System.currentTimeMillis(),
            eventGeneration = generation,
            settlementStatus = SettlementStatus.SETTLED,
        )
    }

    internal suspend fun resolveEvidence(call: ToolCall): UiStateEvidence = actionMutex.withLock {
        val observation = requireCurrent(call.observationId())
        val settlement = settlementMonitor.awaitSettlement()
        check(matchesCurrentUi(observation)) { "Android state changed after the referenced observation" }
        observation.toUiStateEvidence(
            settlementOverride = settlement.status,
            eventGenerationOverride = settlement.eventGeneration,
        )
    }

    internal suspend fun revalidateEvidence(call: ToolCall, evidence: UiStateEvidence): Boolean =
        actionMutex.withLock {
            val observation = runCatching { requireCurrent(call.observationId()) }.getOrNull()
                ?: return@withLock false
            evidence.observationId == observation.id &&
                evidence.uiStateFingerprint == observation.uiStateFingerprint &&
                AndroidUiEvidenceMatcher.matchesAuthorizationGeneration(
                    evidence.eventGeneration,
                    AndroidUiMutationRegistry.current(),
                ) &&
                matchesCurrentUi(observation)
        }

    private fun requireAuthorized(
        context: ToolExecutionContext,
        call: ToolCall,
        observationId: String,
    ): AndroidObservation {
        val permit = requirePermit(context, call)
        val binding = permit.binding
        val observation = requireCurrent(observationId)
        check(binding.observationId == observation.id) { "Authorization belongs to another observation" }
        check(binding.uiStateFingerprint == observation.uiStateFingerprint) { "Authorized UI state is stale" }
        check(
            AndroidUiEvidenceMatcher.matchesAuthorizationGeneration(
                permit.eventGeneration,
                AndroidUiMutationRegistry.current(),
            ),
        ) {
            "Authorized UI generation is stale"
        }
        check(matchesCurrentUi(observation)) { "Android state changed before native dispatch" }
        return observation
    }

    private fun requireUnboundAuthorized(
        context: ToolExecutionContext,
        call: ToolCall,
    ) {
        requirePermit(context, call)
    }

    private fun requirePermit(
        context: ToolExecutionContext,
        call: ToolCall,
    ): dev.ferro.core.ToolExecutionPermit {
        val permit = context.authorization ?: error("Native Android action lacks authorization permit")
        val binding = permit.binding
        check(binding.threadId == context.threadId && binding.turnId == context.turnId)
        check(binding.toolCallId == call.id) { "Authorization belongs to another tool call" }
        check(binding.canonicalArgumentsHash == ToolAuthorizationHashes.arguments(call)) {
            "Tool arguments changed after authorization"
        }
        check(binding.expiresAtEpochMs > System.currentTimeMillis()) {
            "Authorization expired before native dispatch"
        }
        return permit
    }

    private fun matchesCurrentUi(observation: AndroidObservation): Boolean {
        val window = windowResolver.resolve()
        return AndroidUiEvidenceMatcher.matchesReferencedScreen(observation, window)
    }

    private suspend fun captureSettled(): AndroidObservation = observer.capture(
        settlementMonitor.awaitSettlement(),
    ).also {
        latestObservation = it
        observationGate.register(it)
    }

    private suspend fun captureAfterAction(platformOutcome: String = "completed"): AndroidObservation = try {
        captureSettled()
    } catch (error: Throwable) {
        throw ToolExecutionException(
            code = "POST_ACTION_OBSERVATION_FAILED",
            message = error.message ?: "The action completed but its resulting screen could not be captured",
            dispatch = "dispatched",
            platformOutcome = platformOutcome,
        )
    }

    private fun AndroidObservation.lease() = ObservationLease(id, capturedAtEpochMs, width, height)

    private companion object {
        const val MAX_WAIT_MS = 8_000L
        const val MAX_TEXT_LENGTH = 2_000
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

private fun ToolCall.observationId(): String = arguments["observation_id"]
    ?.toString()
    ?.trim('"')
    ?.takeIf(String::isNotBlank)
    ?: error("observation_id must be a non-blank string")
