package dev.ferro.platform.android

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal object AndroidUiMutationRegistry : UiMutationSource {
    private val generation = AtomicLong()
    private val state = AtomicReference(UiMutationState())

    fun record(event: AccessibilityEvent, ferroPackage: String) {
        val packageName = event.packageName?.toString()
        if (packageName == ferroPackage || event.eventType !in RELEVANT_EVENTS) return
        state.set(UiMutationState(generation.incrementAndGet(), packageName))
    }

    override fun current(): UiMutationState = state.get()

    private val RELEVANT_EVENTS = setOf(
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_CLICKED,
    )
}

internal data class AndroidActionableWindow(
    val packageName: String,
    val windowId: Int,
    val windowType: Int,
)

internal class AndroidActionableWindowResolver(
    private val ferroPackage: String,
    private val serviceProvider: () -> FerroAccessibilityService = AccessibilityServiceRegistry::requireService,
) : ActionablePackageProbe {
    fun resolve(): AndroidActionableWindow? {
        val service = serviceProvider()
        val candidates = service.windows.orEmpty()
            .asSequence()
            .filterNot { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            .mapNotNull { window ->
                val packageName = window.root?.packageName?.toString()
                packageName?.takeUnless { it == ferroPackage }?.let {
                    window to AndroidActionableWindow(it, window.id, window.type)
                }
            }
            .sortedWith(
                compareByDescending<Pair<AccessibilityWindowInfo, AndroidActionableWindow>> { it.first.isActive }
                    .thenByDescending { it.first.isFocused }
                    .thenByDescending { it.first.layer },
            )
            .map { it.second }
            .toList()
        return candidates.firstOrNull() ?: service.rootInActiveWindow?.let { root ->
            root.packageName?.toString()
                ?.takeUnless { it == ferroPackage }
                ?.let { AndroidActionableWindow(it, root.windowId, AccessibilityWindowInfo.TYPE_APPLICATION) }
        }
    }

    override fun currentPackage(): String? = runCatching { resolve()?.packageName }.getOrNull()
}

internal fun newAndroidUiSettlementMonitor(resolver: AndroidActionableWindowResolver) = UiSettlementMonitor(
    mutations = AndroidUiMutationRegistry,
    packageProbe = resolver,
    nowElapsedMs = SystemClock::elapsedRealtime,
)

internal object AndroidUiStateFingerprint {
    fun create(
        window: AndroidActionableWindow?,
        width: Int,
        height: Int,
        rotation: Int,
    ): String {
        val material = listOf(
            window?.packageName.orEmpty(),
            window?.windowId ?: -1,
            window?.windowType ?: -1,
            width,
            height,
            rotation,
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

internal object AndroidUiEvidenceMatcher {
    fun matchesReferencedScreen(
        observation: AndroidObservation,
        currentWindow: AndroidActionableWindow?,
    ): Boolean = currentWindow?.packageName == observation.foregroundPackage &&
        AndroidUiStateFingerprint.create(
            currentWindow,
            observation.width,
            observation.height,
            observation.rotation,
        ) == observation.uiStateFingerprint

    fun matchesAuthorizationGeneration(
        authorizedGeneration: Long,
        currentMutation: UiMutationState,
    ): Boolean = authorizedGeneration == currentMutation.generation
}
