package dev.ferro.runtime.android

import java.util.concurrent.CopyOnWriteArraySet

object AgentHostUiVisibility {
    private val listeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    @Volatile private var visible = false

    fun setVisible(value: Boolean) {
        if (visible == value) return
        visible = value
        listeners.forEach { it(value) }
    }

    internal fun observe(listener: (Boolean) -> Unit): AutoCloseable {
        listeners += listener
        listener(visible)
        return AutoCloseable { listeners -= listener }
    }
}
