package dev.ferro.platform.android

import dev.ferro.contracts.AgentEventEnvelope
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class JsonlAgentEventFile(
    private val file: File,
    private val json: Json,
) {
    fun load(): List<AgentEventEnvelope> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        val events = mutableListOf<AgentEventEnvelope>()
        var lineStart = 0
        bytes.forEachIndexed { index, byte ->
            if (byte == NEWLINE) {
                decodeCompletedLine(bytes, lineStart, index, events)
                lineStart = index + 1
            }
        }
        if (lineStart < bytes.size) {
            val tail = bytes.copyOfRange(lineStart, bytes.size)
            if (tail.any { !it.toInt().toChar().isWhitespace() }) {
                val decoded = runCatching { decode(tail) }.getOrNull()
                if (decoded == null) {
                    quarantineAndTruncate(tail, lineStart.toLong())
                } else {
                    events += decoded
                    appendMissingNewline()
                }
            }
        }
        return events
    }

    fun append(event: AgentEventEnvelope) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { stream ->
            stream.write(json.encodeToString(event).toByteArray(Charsets.UTF_8))
            stream.write(NEWLINE.toInt())
            stream.fd.sync()
        }
    }

    private fun decodeCompletedLine(
        bytes: ByteArray,
        start: Int,
        end: Int,
        events: MutableList<AgentEventEnvelope>,
    ) {
        val line = bytes.copyOfRange(start, end)
        if (line.all { it.toInt().toChar().isWhitespace() }) return
        val event = runCatching { decode(line) }.getOrElse { error ->
            throw CorruptAgentEventLogException(
                "Malformed completed event record at byte $start",
                error,
            )
        }
        events += event
    }

    private fun decode(bytes: ByteArray): AgentEventEnvelope =
        json.decodeFromString(AgentEventEnvelope.serializer(), bytes.toString(Charsets.UTF_8))

    private fun quarantineAndTruncate(tail: ByteArray, validLength: Long) {
        val quarantine = File(file.parentFile, "${file.name}.corrupt-tail")
        FileOutputStream(quarantine, false).use { stream ->
            stream.write(tail)
            stream.fd.sync()
        }
        RandomAccessFile(file, "rw").use { random ->
            random.setLength(validLength)
            random.fd.sync()
        }
    }

    private fun appendMissingNewline() {
        FileOutputStream(file, true).use { stream ->
            stream.write(NEWLINE.toInt())
            stream.fd.sync()
        }
    }

    private companion object {
        const val NEWLINE: Byte = 10
    }
}

internal class CorruptAgentEventLogException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)
