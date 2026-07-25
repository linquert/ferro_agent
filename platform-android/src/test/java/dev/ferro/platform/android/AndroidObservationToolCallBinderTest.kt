package dev.ferro.platform.android

import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidObservationToolCallBinderTest {
    @Test
    fun `screen action receives latest runtime observation when model omits it`() {
        val binder = AndroidObservationToolCallBinder { "screen-current" }
        val call = call(FerroToolNames.TAP, buildJsonObject {
            put("x", 0.25)
            put("y", 0.75)
        })

        val bound = binder.bind(call)

        assertEquals("\"screen-current\"", bound.arguments["observation_id"].toString())
        assertEquals(call.id, bound.id)
        assertEquals(call.name, bound.name)
        assertEquals(call.arguments["x"], bound.arguments["x"])
        assertEquals(call.arguments["y"], bound.arguments["y"])
    }

    @Test
    fun `runtime replaces model supplied stale observation rather than trusting it`() {
        val binder = AndroidObservationToolCallBinder { "screen-current" }
        val call = call(FerroToolNames.TYPE_TEXT, buildJsonObject {
            put("observation_id", "screen-stale")
            put("text", "hello")
        })

        val bound = binder.bind(call)

        assertEquals("\"screen-current\"", bound.arguments["observation_id"].toString())
    }

    @Test
    fun `state independent calls carry only model intent arguments`() {
        val binder = AndroidObservationToolCallBinder { "screen-current" }
        val openApp = call(FerroToolNames.OPEN_APP, buildJsonObject {
            put("observation_id", "screen-stale")
            put("package_name", "com.example.target")
        })
        val wait = call(FerroToolNames.WAIT, buildJsonObject {
            put("observation_id", "screen-stale")
            put("duration_ms", 500)
        })

        val boundOpenApp = binder.bind(openApp)
        val boundWait = binder.bind(wait)

        assertNull(boundOpenApp.arguments["observation_id"])
        assertNull(boundWait.arguments["observation_id"])
        assertEquals("\"com.example.target\"", boundOpenApp.arguments["package_name"].toString())
        assertEquals("500", boundWait.arguments["duration_ms"].toString())
    }

    @Test
    fun `missing runtime observation remains unbound for truthful authorization failure`() {
        val binder = AndroidObservationToolCallBinder { null }
        val call = call(FerroToolNames.SWIPE, buildJsonObject {
            put("start_x", 0.5)
            put("start_y", 0.8)
            put("end_x", 0.5)
            put("end_y", 0.2)
            put("duration_ms", 500)
        })

        val bound = binder.bind(call)

        assertFalse("observation_id" in bound.arguments)
    }

    private fun call(name: String, arguments: kotlinx.serialization.json.JsonObject) =
        ToolCall(ToolCallId("call-1"), name, arguments)
}
