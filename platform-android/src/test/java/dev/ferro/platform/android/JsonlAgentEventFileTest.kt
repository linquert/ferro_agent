package dev.ferro.platform.android

import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ThreadStarted
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonlAgentEventFileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "eventType"
    }

    @Test
    fun `truncated unterminated tail is quarantined without losing valid prefix`() {
        val file = temporaryFolder.newFile("events.jsonl")
        val first = event("event-1", 1)
        file.writeText(json.encodeToString(first) + "\n" + "{\"partial\":")
        val eventFile = JsonlAgentEventFile(file, json)

        assertEquals(listOf(first), eventFile.load())
        assertEquals(json.encodeToString(first) + "\n", file.readText())
        assertEquals("{\"partial\":", File(file.parentFile, "events.jsonl.corrupt-tail").readText())

        val second = event("event-2", 2)
        eventFile.append(second)
        assertEquals(listOf(first, second), eventFile.load())
    }

    @Test
    fun `complete final record missing only newline is retained and normalized`() {
        val file = temporaryFolder.newFile("events.jsonl")
        val event = event("event-1", 1)
        file.writeText(json.encodeToString(event))

        assertEquals(listOf(event), JsonlAgentEventFile(file, json).load())
        assertTrue(file.readText().endsWith("\n"))
        assertFalse(File(file.parentFile, "events.jsonl.corrupt-tail").exists())
    }

    @Test
    fun `malformed completed record is a hard failure rather than silently skipped`() {
        val file = temporaryFolder.newFile("events.jsonl")
        file.writeText("{not-json}\n")

        assertThrows(CorruptAgentEventLogException::class.java) {
            JsonlAgentEventFile(file, json).load()
        }
    }

    private fun event(id: String, sequence: Long) = AgentEventEnvelope(
        eventId = id,
        threadId = ThreadId("thread"),
        sequence = sequence,
        timestampEpochMs = sequence,
        payload = ThreadStarted("Task"),
    )
}
