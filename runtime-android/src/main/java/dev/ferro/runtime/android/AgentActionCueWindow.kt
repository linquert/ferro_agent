package dev.ferro.runtime.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.ferro.platform.android.AndroidActionCue

internal class AgentActionCueWindow(context: Context) {
    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)
    private var view: CueView? = null
    private var suppressed = false

    fun show(cue: AndroidActionCue) {
        if (suppressed) return
        detach()
        val created = CueView(applicationContext) { finished ->
            if (view === finished) detach()
        }.apply { play(cue) }
        view = created
        windowManager.addView(created, layoutParams())
    }

    fun suppress() {
        suppressed = true
        detach()
    }

    fun restore() {
        suppressed = false
    }

    fun close() {
        detach()
    }

    private fun detach() {
        val attached = view ?: return
        attached.cancel()
        if (attached.isAttachedToWindow) windowManager.removeViewImmediate(attached)
        view = null
    }

    private fun layoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    }
}

private class CueView(
    context: Context,
    private val onFinished: (CueView) -> Unit,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.FILL
    }
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4f * density
    }
    private var cue: AndroidActionCue? = null
    private var startedAtMs = 0L
    private var cancelled = false

    fun play(value: AndroidActionCue) {
        cue = value
        startedAtMs = SystemClock.uptimeMillis()
        cancelled = false
        invalidate()
    }

    fun cancel() {
        cancelled = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cancelled) return
        val value = cue ?: return
        val elapsed = SystemClock.uptimeMillis() - startedAtMs
        val complete = when (value) {
            is AndroidActionCue.Tap -> drawTap(canvas, value, elapsed)
            is AndroidActionCue.Swipe -> drawSwipe(canvas, value, elapsed)
        }
        if (complete) {
            post { if (!cancelled) onFinished(this) }
        } else {
            postInvalidateOnAnimation()
        }
    }

    private fun drawTap(canvas: Canvas, cue: AndroidActionCue.Tap, elapsedMs: Long): Boolean {
        val progress = (elapsedMs / TAP_DURATION_MS.toFloat()).coerceIn(0f, 1f)
        val alpha = ((1f - progress) * 255).toInt()
        val radius = density * (10f + 30f * easeOut(progress))
        ring.alpha = alpha
        center.alpha = ((1f - progress) * 190).toInt()
        canvas.drawCircle(cue.xPx.toFloat(), cue.yPx.toFloat(), radius, ring)
        canvas.drawCircle(cue.xPx.toFloat(), cue.yPx.toFloat(), density * 5f, center)
        return progress >= 1f
    }

    private fun drawSwipe(canvas: Canvas, cue: AndroidActionCue.Swipe, elapsedMs: Long): Boolean {
        val movementDuration = cue.durationMs.coerceIn(180L, MAX_SWIPE_DURATION_MS)
        val totalDuration = movementDuration + SWIPE_FADE_MS
        val movement = (elapsedMs / movementDuration.toFloat()).coerceIn(0f, 1f)
        val fade = if (elapsedMs <= movementDuration) 1f else {
            1f - ((elapsedMs - movementDuration) / SWIPE_FADE_MS.toFloat()).coerceIn(0f, 1f)
        }
        val x = cue.startXPx + (cue.endXPx - cue.startXPx) * movement
        val y = cue.startYPx + (cue.endYPx - cue.startYPx) * movement
        path.alpha = (fade * 210).toInt()
        center.alpha = (fade * 230).toInt()
        ring.alpha = (fade * 180).toInt()
        canvas.drawLine(cue.startXPx.toFloat(), cue.startYPx.toFloat(), x, y, path)
        canvas.drawCircle(x, y, density * 7f, center)
        canvas.drawCircle(x, y, density * 15f, ring)
        return elapsedMs >= totalDuration
    }

    private fun easeOut(value: Float): Float = 1f - (1f - value) * (1f - value)

    private companion object {
        const val ACCENT = Color.WHITE
        const val TAP_DURATION_MS = 520L
        const val SWIPE_FADE_MS = 260L
        const val MAX_SWIPE_DURATION_MS = 3_000L
    }
}
