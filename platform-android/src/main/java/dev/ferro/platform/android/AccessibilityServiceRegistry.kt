package dev.ferro.platform.android

import dev.ferro.contracts.ToolResultStatus
import dev.ferro.core.ToolExecutionException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AccessibilityServiceRegistry {
    private val mutableService = MutableStateFlow<FerroAccessibilityService?>(null)
    private val mutableConnected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()

    internal fun attach(service: FerroAccessibilityService) {
        mutableService.value = service
        mutableConnected.value = true
    }

    internal fun detach(service: FerroAccessibilityService) {
        if (mutableService.value === service) {
            mutableService.value = null
            mutableConnected.value = false
        }
    }

    internal fun requireService(): FerroAccessibilityService = mutableService.value
        ?: throw ToolExecutionException(
            code = "DEVICE_CONTROL_UNAVAILABLE",
            message = "Ferro accessibility service is not connected; enable device control and start a new turn",
            status = ToolResultStatus.FATAL_FAILURE,
        )

}
