package dev.ferro.runtime.android

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

internal interface CompanionOverlayActions {
    fun pause()
    fun stop()
    fun submitCompanionInput(input: String)
    fun approvePendingTool()
    fun denyPendingTool()
    fun openFerro()
}

internal enum class CompanionOverlayMode {
    ICON,
    STATUS,
    EXPANDED,
}

internal class CompanionOverlayWindow(
    context: Context,
    private val actions: CompanionOverlayActions,
) {
    private val applicationContext = context.applicationContext
    private val windowManager = applicationContext.getSystemService(WindowManager::class.java)
    private val preferences = applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private var mode = CompanionOverlayMode.STATUS
    private var presentation: CompanionOverlayPresentation? = null
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var suppressed = false
    private var draft = ""

    private var taskView: TextView? = null
    private var compactTaskView: TextView? = null
    private var statusView: TextView? = null
    private var promptView: TextView? = null
    private var inputView: EditText? = null
    private var submitButton: Button? = null
    private var pauseButton: Button? = null
    private var approveButton: Button? = null
    private var denyButton: Button? = null
    private var stopButton: Button? = null

    fun render(next: CompanionOverlayPresentation) {
        presentation = next
        if (!next.visible || suppressed) {
            detach()
            return
        }
        if (root == null) attach()
        updateContent(next)
    }

    fun hide() {
        detach()
    }

    fun suppress() {
        suppressed = true
        detach()
    }

    fun restore() {
        suppressed = false
        presentation?.let(::render)
    }

    fun close() {
        hideKeyboard()
        detach()
    }

    private fun attach() {
        val current = presentation ?: return
        val created = when (mode) {
            CompanionOverlayMode.ICON -> buildIcon()
            CompanionOverlayMode.STATUS -> buildStatus()
            CompanionOverlayMode.EXPANDED -> buildExpanded()
        }
        val layoutParams = createLayoutParams(mode)
        root = created
        params = layoutParams
        windowManager.addView(created, layoutParams)
        updateContent(current)
    }

    private fun detach() {
        val attached = root ?: return
        if (attached.isAttachedToWindow) windowManager.removeViewImmediate(attached)
        root = null
        params = null
        clearViewReferences()
    }

    private fun setMode(next: CompanionOverlayMode) {
        if (mode == next) return
        hideKeyboard()
        detach()
        mode = next
        presentation?.let(::render)
    }

    private fun buildIcon(): View = TextView(applicationContext).apply {
        text = "F"
        gravity = Gravity.CENTER
        textSize = 18f
        setTextColor(FOREGROUND)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        background = panelBackground(ICON_BACKGROUND)
        elevation = dp(8).toFloat()
        contentDescription = "Expand Ferro companion status"
        setOnTouchListener(dragListener { setMode(CompanionOverlayMode.STATUS) })
    }

    private fun buildStatus(): View = LinearLayout(applicationContext).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), 0, dp(4), 0)
        background = panelBackground(PANEL_BACKGROUND)
        elevation = dp(8).toFloat()

        addView(View(applicationContext).apply { setBackgroundColor(ACTIVE_ACCENT) }, dimensions(4, 32))
        addView(LinearLayout(applicationContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(5), dp(4), dp(5))
            addView(TextView(applicationContext).also {
                compactTaskView = it
                it.setTextColor(SECONDARY_TEXT)
                it.textSize = 10f
                it.maxLines = 1
            })
            addView(TextView(applicationContext).also {
                statusView = it
                it.setTextColor(FOREGROUND)
                it.textSize = 13f
                it.maxLines = 1
                it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(iconButton(android.R.drawable.ic_menu_more, "Expand Ferro controls") {
            setMode(CompanionOverlayMode.EXPANDED)
        })
        setOnTouchListener(dragListener { setMode(CompanionOverlayMode.EXPANDED) })
    }

    private fun buildExpanded(): View = LinearLayout(applicationContext).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(12))
        background = panelBackground(PANEL_BACKGROUND)
        elevation = dp(10).toFloat()

        addView(LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(applicationContext).also {
                it.text = "Ferro"
                it.textSize = 14f
                it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
                it.setTextColor(FOREGROUND)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(iconButton(android.R.drawable.ic_menu_view, "Open Ferro") { actions.openFerro() })
            addView(iconButton(android.R.drawable.arrow_down_float, "Collapse Ferro controls") {
                setMode(CompanionOverlayMode.STATUS)
            })
            addView(iconButton(android.R.drawable.ic_menu_close_clear_cancel, "Minimize to Ferro icon") {
                setMode(CompanionOverlayMode.ICON)
            })
            setOnTouchListener(dragListener())
        })
        addView(TextView(applicationContext).also {
            taskView = it
            it.setTextColor(SECONDARY_TEXT)
            it.textSize = 12f
            it.maxLines = 2
            it.setPadding(0, dp(4), 0, 0)
        })
        addView(TextView(applicationContext).also {
            statusView = it
            it.setTextColor(FOREGROUND)
            it.textSize = 15f
            it.setTypeface(it.typeface, android.graphics.Typeface.BOLD)
            it.setPadding(0, dp(10), 0, dp(8))
        })
        addView(TextView(applicationContext).also {
            promptView = it
            it.setTextColor(WAITING_ACCENT)
            it.textSize = 13f
            it.setPadding(0, 0, 0, dp(8))
        })
        addView(EditText(applicationContext).also {
            inputView = it
            it.setTextColor(FOREGROUND)
            it.setHintTextColor(SECONDARY_TEXT)
            it.textSize = 13f
            it.minLines = 2
            it.maxLines = 4
            it.setPadding(dp(10), dp(8), dp(10), dp(8))
            it.background = fieldBackground()
            it.setText(draft)
            it.setSelection(it.text.length)
            it.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(value: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(value: CharSequence?, start: Int, before: Int, count: Int) = Unit

                override fun afterTextChanged(value: Editable?) {
                    draft = value?.toString().orEmpty()
                    submitButton?.isEnabled = presentation?.inputMode == OverlayInputMode.RESUME ||
                        draft.isNotBlank()
                }
            })
            it.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) draft = it.text.toString()
            }
            it.setOnTouchListener { _, _ ->
                makeFocusable()
                false
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(Button(applicationContext).also {
            submitButton = it
            it.setOnClickListener {
                val input = inputView?.text?.toString().orEmpty().trim()
                val mode = presentation?.inputMode ?: OverlayInputMode.NONE
                if (input.isNotEmpty() || mode == OverlayInputMode.RESUME) {
                    actions.submitCompanionInput(input)
                    draft = ""
                    inputView?.setText("")
                    clearFocusToUnderlyingApp()
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            gravity = Gravity.END
            topMargin = dp(8)
        })
        addView(LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(applicationContext).also {
                approveButton = it
                it.text = "Approve once"
                it.setOnClickListener { actions.approvePendingTool() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(Button(applicationContext).also {
                denyButton = it
                it.text = "Deny"
                it.setOnClickListener { actions.denyPendingTool() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
        addView(LinearLayout(applicationContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Button(applicationContext).also {
                pauseButton = it
                it.text = "Pause"
                it.setOnClickListener {
                    actions.pause()
                    setMode(CompanionOverlayMode.STATUS)
                }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(Button(applicationContext).also {
                stopButton = it
                it.text = "Stop"
                it.setTextColor(Color.WHITE)
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(STOP_BACKGROUND)
                it.setOnClickListener { actions.stop() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }

    private fun updateContent(value: CompanionOverlayPresentation) {
        taskView?.text = value.taskTitle
        compactTaskView?.text = value.taskTitle
        statusView?.text = value.status
        promptView?.apply {
            text = value.prompt.orEmpty()
            visibility = if (value.prompt.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        inputView?.apply {
            visibility = if (value.inputMode == OverlayInputMode.NONE) View.GONE else View.VISIBLE
            hint = value.inputHint.orEmpty()
            isEnabled = value.inputMode != OverlayInputMode.NONE
        }
        submitButton?.apply {
            text = value.submitLabel.orEmpty()
            visibility = if (value.inputMode == OverlayInputMode.NONE) View.GONE else View.VISIBLE
            isEnabled = value.inputMode == OverlayInputMode.RESUME || draft.isNotBlank() ||
                inputView?.text?.isNotBlank() == true
        }
        pauseButton?.visibility = if (value.controlAction == OverlayControlAction.PAUSE) View.VISIBLE else View.GONE
        approveButton?.visibility = if (value.pendingApproval != null) View.VISIBLE else View.GONE
        denyButton?.visibility = if (value.pendingApproval != null) View.VISIBLE else View.GONE
        stopButton?.apply {
            visibility = if (value.canStop) View.VISIBLE else View.GONE
            isEnabled = value.canStop
        }
    }

    @Suppress("DEPRECATION")
    private fun createLayoutParams(mode: CompanionOverlayMode): WindowManager.LayoutParams {
        val width = when (mode) {
            CompanionOverlayMode.ICON -> dp(52)
            CompanionOverlayMode.STATUS -> minOf(dp(280), screenWidth() - dp(24))
            CompanionOverlayMode.EXPANDED -> minOf(dp(320), screenWidth() - dp(24))
        }
        val height = if (mode == CompanionOverlayMode.ICON) dp(52) else ViewGroup.LayoutParams.WRAP_CONTENT
        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            BASE_FLAGS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(PREF_X, screenWidth() - width - dp(12)).coerceIn(0, maxX(width))
            y = preferences.getInt(PREF_Y, screenHeight() / 4).coerceIn(0, maxY())
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
    }

    private fun dragListener(onClick: (() -> Unit)? = null) = View.OnTouchListener { _, event ->
        val layout = params ?: return@OnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartX = layout.x
                dragStartY = layout.y
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - dragStartRawX).roundToInt()
                val dy = (event.rawY - dragStartRawY).roundToInt()
                layout.x = (dragStartX + dx).coerceIn(0, maxX(layout.width))
                layout.y = (dragStartY + dy).coerceIn(0, maxY())
                root?.let { windowManager.updateViewLayout(it, layout) }
                true
            }
            MotionEvent.ACTION_UP -> {
                val moved = abs(event.rawX - dragStartRawX) > dp(6) || abs(event.rawY - dragStartRawY) > dp(6)
                if (moved) {
                    val width = layout.width.takeIf { it > 0 } ?: root?.width ?: dp(52)
                    layout.x = if (layout.x + width / 2 < screenWidth() / 2) 0 else maxX(width)
                    root?.let { windowManager.updateViewLayout(it, layout) }
                    preferences.edit().putInt(PREF_X, layout.x).putInt(PREF_Y, layout.y).apply()
                } else {
                    onClick?.invoke()
                }
                true
            }
            else -> false
        }
    }

    private fun makeFocusable() {
        val layout = params ?: return
        if (layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) return
        layout.flags = layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        root?.let { windowManager.updateViewLayout(it, layout) }
    }

    private fun clearFocusToUnderlyingApp() {
        draft = inputView?.text?.toString().orEmpty()
        inputView?.clearFocus()
        hideKeyboard()
        val layout = params ?: return
        layout.flags = layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        root?.let { windowManager.updateViewLayout(it, layout) }
    }

    private fun hideKeyboard() {
        root?.let {
            applicationContext.getSystemService(InputMethodManager::class.java)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun iconButton(icon: Int, description: String, action: () -> Unit) =
        ImageButton(applicationContext).apply {
            setImageResource(icon)
            contentDescription = description
            setColorFilter(FOREGROUND)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener { action() }
            layoutParams = dimensions(48, 48)
        }

    private fun panelBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(8).toFloat()
        setStroke(dp(1), BORDER)
    }

    private fun fieldBackground() = GradientDrawable().apply {
        setColor(FIELD_BACKGROUND)
        cornerRadius = dp(6).toFloat()
        setStroke(dp(1), BORDER)
    }

    private fun dimensions(widthDp: Int, heightDp: Int) =
        ViewGroup.LayoutParams(dp(widthDp), dp(heightDp))

    private fun clearViewReferences() {
        taskView = null
        compactTaskView = null
        statusView = null
        promptView = null
        inputView = null
        submitButton = null
        pauseButton = null
        approveButton = null
        denyButton = null
        stopButton = null
    }

    private fun dp(value: Int): Int = (value * applicationContext.resources.displayMetrics.density).roundToInt()
    private fun screenWidth(): Int = applicationContext.resources.displayMetrics.widthPixels
    private fun screenHeight(): Int = applicationContext.resources.displayMetrics.heightPixels
    private fun maxX(width: Int): Int = (screenWidth() - width.coerceAtLeast(dp(52))).coerceAtLeast(0)
    private fun maxY(): Int = (screenHeight() - dp(72)).coerceAtLeast(0)

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartX = 0
    private var dragStartY = 0

    private companion object {
        const val PREFERENCES = "ferro_companion_overlay"
        const val PREF_X = "x"
        const val PREF_Y = "y"
        const val PANEL_BACKGROUND = 0xF2202224.toInt()
        const val ICON_BACKGROUND = 0xF2336B52.toInt()
        const val FIELD_BACKGROUND = 0xFF303236.toInt()
        const val FOREGROUND = 0xFFF7F7F5.toInt()
        const val SECONDARY_TEXT = 0xFFB8BCB9.toInt()
        const val ACTIVE_ACCENT = 0xFF5CBF89.toInt()
        const val WAITING_ACCENT = 0xFFF0B44D.toInt()
        const val STOP_BACKGROUND = 0xFF9F2D2D.toInt()
        const val BORDER = 0xFF55595A.toInt()
        const val BASE_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_SECURE
    }
}
