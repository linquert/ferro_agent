package dev.ferro.app

import dev.ferro.contracts.PolicyProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FerroUiStateTest {
    @Test
    fun `autonomous mode can start without a package allowlist`() {
        val configured = FerroUiState(
            baseUrl = "https://example.test/v1",
            model = "model",
            apiKey = "key",
            accessibilityReady = true,
        )

        assertFalse(configured.runtimeReady)
        assertTrue(configured.copy(policyProfile = PolicyProfile.AUTONOMOUS).runtimeReady)
    }
}
