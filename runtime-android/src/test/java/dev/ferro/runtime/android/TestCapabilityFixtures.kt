package dev.ferro.runtime.android

import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.TaskCapabilityScope

internal fun testCapabilityScope(id: String = "scope-test") = TaskCapabilityScope(
    id = CapabilityScopeId(id),
    allowedTools = FerroToolNames.all,
    allowedPackages = setOf("com.example.target"),
    transitPackages = setOf("com.android.systemui"),
    allowTextEntry = true,
    allowSystemNavigation = true,
    allowAppLaunch = true,
)
