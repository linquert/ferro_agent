package dev.ferro.platform.android

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class AndroidEnvironmentInspection(
    val observation: AndroidObservation,
    val facts: JsonObject,
)

internal class AndroidEnvironmentInspector(
    context: Context,
    private val windowResolver: AndroidActionableWindowResolver,
) {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val keyguardManager = applicationContext.getSystemService(KeyguardManager::class.java)

    fun inspect(observation: AndroidObservation, appQuery: String?): AndroidEnvironmentInspection {
        val service = AccessibilityServiceRegistry.requireService()
        val actionableWindow = windowResolver.resolve()
        val inputMethodObserved = service.windows.orEmpty().any {
            it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
        }
        return AndroidEnvironmentInspection(
            observation = observation,
            facts = buildJsonObject {
                actionableWindow?.let {
                    put("actionable_package", it.packageName)
                    put("window_id", it.windowId)
                    put("window_type", windowTypeName(it.windowType))
                }
                put("input_method_window_observed", inputMethodObserved)
                put("keyguard_locked", keyguardManager?.isKeyguardLocked == true)
                put("screenshot_callback", "success")
                put("mean_luminance", observation.meanLuminance)
                put("near_black_percent", observation.nearBlackPercent)
                appQuery?.trim()?.takeIf(String::isNotBlank)?.let { query ->
                    put("app_query", query)
                    put("matching_apps", matchingLaunchableApps(query))
                }
            },
        )
    }

    private fun matchingLaunchableApps(query: String) = buildJsonArray {
        launchableActivities()
            .asSequence()
            .map { info ->
                val label = info.loadLabel(packageManager).toString()
                val packageName = info.activityInfo.packageName
                Triple(label, packageName, info.activityInfo.name)
            }
            .filter { (label, packageName) ->
                label.contains(query, ignoreCase = true) || packageName.contains(query, ignoreCase = true)
            }
            .distinctBy { it.second }
            .sortedWith(compareBy<Triple<String, String, String>> { it.first.lowercase() }.thenBy { it.second })
            .take(MAX_APP_MATCHES)
            .forEach { (label, packageName, activityName) ->
                add(buildJsonObject {
                    put("label", label)
                    put("package", packageName)
                    put("activity", activityName)
                    put("launchable", true)
                })
            }
    }

    @Suppress("DEPRECATION")
    private fun launchableActivities() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).let { intent ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
    }

    private fun windowTypeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_screen_divider"
        else -> "unknown_$type"
    }

    private companion object {
        const val MAX_APP_MATCHES = 8
    }
}
