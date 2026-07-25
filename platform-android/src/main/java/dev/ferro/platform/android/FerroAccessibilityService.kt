package dev.ferro.platform.android

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class FerroAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceRegistry.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let { AndroidUiMutationRegistry.record(it, packageName) }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        AccessibilityServiceRegistry.detach(this)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityServiceRegistry.detach(this)
        return super.onUnbind(intent)
    }
}
