package dev.ferro.platform.android

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface AndroidActionCue {
    data class Tap(
        val xPx: Int,
        val yPx: Int,
    ) : AndroidActionCue

    data class Swipe(
        val startXPx: Int,
        val startYPx: Int,
        val endXPx: Int,
        val endYPx: Int,
        val durationMs: Long,
    ) : AndroidActionCue
}

fun interface AndroidActionCueSink {
    fun show(cue: AndroidActionCue)
}

object AndroidActionCueRegistry {
    private val sink = AtomicReference<AndroidActionCueSink?>()

    fun install(value: AndroidActionCueSink): AutoCloseable {
        sink.set(value)
        return AutoCloseable { sink.compareAndSet(value, null) }
    }

    internal fun show(cue: AndroidActionCue) {
        sink.get()?.show(cue)
    }
}

fun interface ScreenCaptureUiLease {
    suspend fun restore()
}

fun interface ScreenCaptureUiGuard {
    suspend fun suppress(): ScreenCaptureUiLease
}

object ScreenCaptureUiGuardRegistry {
    private val guard = AtomicReference<ScreenCaptureUiGuard?>()

    fun install(value: ScreenCaptureUiGuard): AutoCloseable {
        guard.set(value)
        return AutoCloseable { guard.compareAndSet(value, null) }
    }

    internal suspend fun suppress(): ScreenCaptureUiLease =
        guard.get()?.suppress() ?: ScreenCaptureUiLease { }
}

internal suspend fun <T> withScreenCaptureUiSuppressed(block: suspend () -> T): T {
    val lease = ScreenCaptureUiGuardRegistry.suppress()
    return try {
        block()
    } finally {
        withContext(NonCancellable) { lease.restore() }
    }
}
