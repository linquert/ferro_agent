package dev.ferro.core

import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnId
import dev.ferro.contracts.emptyArguments
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolRouterTest {
    @Test(expected = CancellationException::class)
    fun `tool cancellation propagates to stop the active turn`() = runTest {
        val handler = object : ToolHandler {
            override val spec = ModelToolSpec("wait", "Wait")
            override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
                throw CancellationException("Stopped by user")
            }
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))

        router.execute(context, ToolCall(ToolCallId("call"), "wait", emptyArguments()))
    }

    @Test
    fun `typed execution failures preserve stable repair code`() = runTest {
        val handler = object : ToolHandler {
            override val spec = ModelToolSpec("tap", "Tap")
            override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
                throw ToolExecutionException("INVALID_ARGUMENT", "x must be a finite number")
            }
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))

        val call = ToolCall(ToolCallId("call"), "tap", emptyArguments())
        val result = router.execute(context, call)

        assertEquals(ToolResultStatus.RECOVERABLE_FAILURE, result.status)
        assertEquals("\"INVALID_ARGUMENT\"", result.output["code"].toString())
        assertEquals("\"not_dispatched\"", result.output["dispatch"].toString())
        assertEquals("\"rejected\"", result.output["platform_outcome"].toString())
        assertEquals("x must be a finite number", result.message)
    }

    @Test
    fun `fatal execution failures preserve terminal status`() = runTest {
        val handler = object : ToolHandler {
            override val spec = ModelToolSpec("observe", "Observe")
            override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
                throw ToolExecutionException(
                    "DEVICE_CONTROL_UNAVAILABLE",
                    "Device control disconnected",
                    ToolResultStatus.FATAL_FAILURE,
                )
            }
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))
        val call = ToolCall(ToolCallId("call"), "observe", emptyArguments())

        val result = router.execute(context, call)

        assertEquals(ToolResultStatus.FATAL_FAILURE, result.status)
        assertEquals("\"DEVICE_CONTROL_UNAVAILABLE\"", result.output["code"].toString())
    }
    private val context = ToolExecutionContext(ThreadId("thread"), TurnId("turn"), "fingerprint")

    @Test
    fun `unknown tool produces recoverable result with matching call id`() = runTest {
        val router = ToolRouter(ToolRegistry(emptyList()))
        val call = ToolCall(ToolCallId("call"), "missing", emptyArguments())

        val result = router.execute(context, call)

        assertEquals(call.id, result.callId)
        assertEquals(ToolResultStatus.RECOVERABLE_FAILURE, result.status)
        assertEquals("Unknown tool: missing", result.message)
    }

    @Test
    fun `missing required arguments are rejected before handler execution`() = runTest {
        var executions = 0
        val handler = object : ToolHandler {
            override val spec = ModelToolSpec("tap", "Tap", setOf("x", "y"))
            override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
                executions++
                return ToolResult(call.id, ToolResultStatus.SUCCESS)
            }
        }
        val router = ToolRouter(ToolRegistry(listOf(handler)))
        val call = ToolCall(
            ToolCallId("call"),
            "tap",
            buildJsonObject { put("x", 10) },
        )

        val result = router.execute(context, call)

        assertEquals(0, executions)
        assertEquals(ToolResultStatus.RECOVERABLE_FAILURE, result.status)
        assertEquals("Missing required arguments: y", result.message)
        assertEquals("\"not_dispatched\"", result.output["dispatch"].toString())
    }

}
