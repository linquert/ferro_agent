package dev.ferro.platform.android

import android.content.Context
import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.core.ToolExecutionContext
import dev.ferro.core.ToolExecutionException
import dev.ferro.core.ToolHandler
import dev.ferro.core.ToolCallBinder
import dev.ferro.core.UserControlRecovery
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class AndroidDeviceToolCatalog(context: Context) {
    private val controller = AndroidDeviceController(context.applicationContext)

    fun handlers(): List<ToolHandler> = listOf(
        ObserveHandler(controller),
        TapHandler(controller),
        SwipeHandler(controller),
        TypeTextHandler(controller),
        KeyActionHandler(controller),
        OpenAppHandler(controller),
        WaitHandler(controller),
        InspectAndroidEnvironmentHandler(controller),
    )

    fun userControlRecovery(): UserControlRecovery = UserControlRecovery { _, call, userNote ->
        val observation = controller.observe()
        ToolResult(
            callId = call.id,
            status = ToolResultStatus.SUCCESS,
            output = buildJsonObject {
                observation.toJson().forEach { (key, value) -> put(key, value) }
                put("user_note", userNote)
                put("dispatch", "not_applicable")
                put("platform_outcome", "completed")
            },
            message = "User returned control; fresh screen captured",
            attachments = listOf(observation.screenshot),
        )
    }

    fun authorizationEvidenceProvider(): dev.ferro.core.ToolAuthorizationEvidenceProvider =
        AndroidToolAuthorizationEvidenceProvider(controller)

    fun toolCallBinder(): ToolCallBinder = AndroidObservationToolCallBinder(controller::latestObservationId)
}

private class InspectAndroidEnvironmentHandler(
    private val controller: AndroidDeviceController,
) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "inspect_android_environment",
        description = "Inspect Android-reported facts when the screenshot is ambiguous or an installed app package is unknown. Optionally search launchable app labels and packages. Returns a fresh screenshot.",
        inputSchema = objectSchema(
            "app_query" to stringProperty("Optional app label or package fragment to search for"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
        val query = call.arguments["app_query"]?.jsonPrimitive?.contentOrNull
        val inspection = controller.inspectEnvironment(query)
        return ToolResult(
            callId = call.id,
            status = ToolResultStatus.SUCCESS,
            output = buildJsonObject {
                inspection.observation.toJson().forEach { (key, value) -> put(key, value) }
                inspection.facts.forEach { (key, value) -> put(key, value) }
                put("dispatch", "not_applicable")
                put("platform_outcome", "completed")
            },
            message = "Android environment inspected",
            attachments = listOf(inspection.observation.screenshot),
        )
    }
}

private class ObserveHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "observe_screen",
        description = "Capture the current Android screen. Always call this before choosing an action.",
        inputSchema = objectSchema(),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult =
        observationResult(call, controller.observe(), "Screen captured", dispatched = false)
}

private class TapHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "tap",
        description = "Tap one point from the latest screenshot. Returns a fresh screenshot after the tap.",
        requiredArguments = setOf("x", "y"),
        inputSchema = objectSchema(
            "x" to normalizedCoordinateProperty("Horizontal position from 0.0 at left to 1.0 at right"),
            "y" to normalizedCoordinateProperty("Vertical position from 0.0 at top to 1.0 at bottom"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult = observationResult(
        call,
        controller.tap(context, call, call.string("observation_id"), call.number("x"), call.number("y")),
        "Tap completed",
    )
}

private class SwipeHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "swipe",
        description = "Swipe between screenshot coordinates. Returns a fresh screenshot after the swipe.",
        requiredArguments = setOf("start_x", "start_y", "end_x", "end_y", "duration_ms"),
        inputSchema = objectSchema(
            "start_x" to normalizedCoordinateProperty("Starting horizontal position from 0.0 to 1.0"),
            "start_y" to normalizedCoordinateProperty("Starting vertical position from 0.0 to 1.0"),
            "end_x" to normalizedCoordinateProperty("Ending horizontal position from 0.0 to 1.0"),
            "end_y" to normalizedCoordinateProperty("Ending vertical position from 0.0 to 1.0"),
            "duration_ms" to integerProperty("Gesture duration from 100 to 3000 milliseconds"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult = observationResult(
        call,
        controller.swipe(
            context,
            call,
            call.string("observation_id"),
            call.number("start_x"),
            call.number("start_y"),
            call.number("end_x"),
            call.number("end_y"),
            call.long("duration_ms"),
        ),
        "Swipe completed",
    )
}

private class TypeTextHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "type_text",
        description = "Replace text in the currently focused input field. Returns a fresh screenshot.",
        requiredArguments = setOf("text"),
        inputSchema = objectSchema(
            "text" to stringProperty("Exact text to enter"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult = observationResult(
        call,
        controller.typeText(context, call, call.string("observation_id"), call.string("text")),
        "Text entered",
    )
}

private class KeyActionHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "key_action",
        description = "Perform one Android global action: back, home, recents, or notifications.",
        requiredArguments = setOf("action"),
        inputSchema = objectSchema(
            "action" to buildJsonObject {
                put("type", "string")
                put("enum", kotlinx.serialization.json.buildJsonArray {
                    listOf("back", "home", "recents", "notifications").forEach {
                        add(JsonPrimitive(it))
                    }
                })
            },
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult = observationResult(
        call,
        controller.keyAction(context, call, call.string("observation_id"), call.string("action")),
        "Key action completed",
    )
}

private class OpenAppHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "open_app",
        description = "Open an installed Android app by package name. Returns a fresh screenshot.",
        requiredArguments = setOf("package_name"),
        inputSchema = objectSchema(
            "package_name" to stringProperty("Android package name such as com.android.settings"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult = observationResult(
        call,
        controller.openApp(context, call, call.string("package_name")),
        "App launch requested",
        platformOutcome = "accepted",
    )
}

private class WaitHandler(private val controller: AndroidDeviceController) : ToolHandler {
    override val spec = ModelToolSpec(
        name = "wait",
        description = "Wait for the current UI to settle, then return a fresh screenshot.",
        requiredArguments = setOf("duration_ms"),
        inputSchema = objectSchema(
            "duration_ms" to integerProperty("Wait duration from 0 to 8000 milliseconds"),
        ),
    )

    override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult = observationResult(
        call,
        controller.waitFor(context, call, call.long("duration_ms")),
        "Wait completed",
        dispatched = false,
    )
}

private fun observationResult(
    call: ToolCall,
    observation: AndroidObservation,
    message: String,
    dispatched: Boolean = true,
    platformOutcome: String = "completed",
) = ToolResult(
    callId = call.id,
    status = ToolResultStatus.SUCCESS,
    output = buildJsonObject {
        observation.toJson().forEach { (key, value) -> put(key, value) }
        put("dispatch", if (dispatched) "dispatched" else "not_applicable")
        put("platform_outcome", platformOutcome)
    },
    message = message,
    attachments = listOf(observation.screenshot),
)

private fun ToolCall.string(name: String): String = arguments[name]?.jsonPrimitive?.contentOrNull
    ?.takeIf(String::isNotBlank) ?: error("$name must be a non-blank string")

private fun ToolCall.number(name: String): Double = arguments[name]?.jsonPrimitive?.doubleOrNull
    ?.takeIf(Double::isFinite)
    ?: throw ToolExecutionException("INVALID_ARGUMENT", "$name must be a finite number")

private fun ToolCall.long(name: String): Long = arguments[name]?.jsonPrimitive?.longOrNull
    ?: error("$name must be an integer")

private fun objectSchema(vararg properties: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { properties.forEach { (name, schema) -> put(name, schema) } })
    put("additionalProperties", false)
}

private fun stringProperty(description: String): JsonObject = buildJsonObject {
    put("type", "string")
    put("description", description)
}

private fun integerProperty(description: String): JsonObject = buildJsonObject {
    put("type", "integer")
    put("description", description)
}

private fun normalizedCoordinateProperty(description: String): JsonObject = buildJsonObject {
    put("type", "number")
    put("minimum", 0.0)
    put("maximum", 1.0)
    put("description", description)
}
