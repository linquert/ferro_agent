package dev.ferro.runtime.android

import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TurnId
import dev.ferro.core.ToolAuthorizationHashes
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ActiveRuntimeStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trip preserves active scope without any credential field`() = runTest {
        val file = temporaryFolder.newFile("active.json")
        file.delete()
        val store = FileActiveRuntimeStore(file)
        val record = record("session-current")

        store.save(record)

        assertEquals(record, store.load())
        val durableText = file.readText()
        assertFalse(durableText.contains("apiKey", ignoreCase = true))
        assertFalse(durableText.contains("credential", ignoreCase = true))
    }

    @Test
    fun `stale clear cannot delete a newer active session`() = runTest {
        val file = temporaryFolder.newFile("active.json")
        file.delete()
        val store = FileActiveRuntimeStore(file)
        store.save(record("session-new"))

        store.clear("session-old")
        assertEquals("session-new", store.load()?.sessionId)

        store.clear("session-new")
        assertNull(store.load())
    }

    @Test
    fun `corrupt metadata is quarantined and recovery falls back to idle`() = runTest {
        val file = temporaryFolder.newFile("active.json")
        file.writeText("{not-json")
        val store = FileActiveRuntimeStore(file)

        assertNull(store.load())
        assertFalse(file.exists())
        assertTrue(File(file.parentFile, "active.json.corrupt").exists())
    }

    @Test
    fun `interrupted replacement restores the last complete backup`() = runTest {
        val file = temporaryFolder.newFile("active.json")
        file.delete()
        val backup = File(file.parentFile, "active.json.bak")
        backup.writeText(Json.encodeToString(record("session-backup")))

        val restored = FileActiveRuntimeStore(file).load()

        assertEquals("session-backup", restored?.sessionId)
        assertTrue(file.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun `clear removes stale backup that could otherwise resurrect discarded work`() = runTest {
        val file = temporaryFolder.newFile("active.json")
        file.delete()
        val store = FileActiveRuntimeStore(file)
        store.save(record("session-current"))
        val backup = File(file.parentFile, "active.json.bak")
        backup.writeText(Json.encodeToString(record("session-older")))

        store.clear("session-current")

        assertNull(store.load())
        assertFalse(backup.exists())
    }

    private fun record(sessionId: String) = ActiveRuntimeRecord(
        sessionId = sessionId,
        threadId = ThreadId("thread"),
        turnId = TurnId("turn"),
        goal = "Complete the Android task",
        providerKind = RuntimeProviderKind.CHAT_COMPLETIONS,
        baseUrl = "https://example.test/v1",
        model = "test-model",
        startedAtEpochMs = 1234,
        capabilityScope = testCapabilityScope(),
        capabilityScopeHash = ToolAuthorizationHashes.scope(testCapabilityScope()),
    )
}
