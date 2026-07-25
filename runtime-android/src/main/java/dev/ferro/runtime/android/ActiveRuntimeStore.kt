package dev.ferro.runtime.android

import android.content.Context
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.TurnId
import dev.ferro.core.ToolAuthorizationHashes
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ActiveRuntimeRecord(
    val schemaVersion: Int = 2,
    val sessionId: String,
    val threadId: ThreadId,
    val turnId: TurnId,
    val goal: String,
    val providerKind: RuntimeProviderKind,
    val baseUrl: String,
    val model: String,
    val startedAtEpochMs: Long,
    val capabilityScope: TaskCapabilityScope,
    val capabilityScopeHash: String,
) {
    init {
        require(sessionId.isNotBlank()) { "Session ID must not be blank" }
        require(goal.isNotBlank()) { "Recovery goal must not be blank" }
        require(baseUrl.isNotBlank()) { "Recovery provider URL must not be blank" }
        require(model.isNotBlank()) { "Recovery model must not be blank" }
        require(capabilityScopeHash == ToolAuthorizationHashes.scope(capabilityScope)) {
            "Recovery capability scope hash does not match its content"
        }
    }

    fun snapshot() = RecoveryRuntimeSnapshot(
        sessionId,
        threadId,
        turnId,
        goal,
        providerKind,
        baseUrl,
        model,
        capabilityScope,
        capabilityScopeHash,
    )
}

internal interface ActiveRuntimeStore {
    suspend fun load(): ActiveRuntimeRecord?
    suspend fun save(record: ActiveRuntimeRecord)
    suspend fun clear(expectedSessionId: String)
}

internal class FileActiveRuntimeStore(
    private val file: File,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : ActiveRuntimeStore {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "active-agent-runtime.json"),
    )

    private val mutex = Mutex()

    override suspend fun load(): ActiveRuntimeRecord? = mutex.withLock {
        restoreBackupIfNeeded()
        if (!file.exists()) return@withLock null
        val record = runCatching {
            json.decodeFromString<ActiveRuntimeRecord>(file.readText())
        }.getOrNull()
        if (record == null) {
            quarantineCorruptFile()
            return@withLock null
        }
        if (record.schemaVersion != CURRENT_SCHEMA_VERSION) {
            quarantineCorruptFile()
            null
        } else {
            record
        }
    }

    override suspend fun save(record: ActiveRuntimeRecord) = mutex.withLock {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        val backup = File(file.parentFile, "${file.name}.bak")
        FileOutputStream(temporary, false).use { stream ->
            stream.write(json.encodeToString(record).toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        backup.delete()
        check(!file.exists() || file.renameTo(backup)) { "Could not stage active runtime metadata" }
        if (!temporary.renameTo(file)) {
            backup.renameTo(file)
            error("Could not commit active runtime metadata")
        }
        backup.delete()
        Unit
    }

    override suspend fun clear(expectedSessionId: String) = mutex.withLock {
        restoreBackupIfNeeded()
        val current = if (file.exists()) {
            runCatching { json.decodeFromString<ActiveRuntimeRecord>(file.readText()) }.getOrNull()
        } else {
            null
        }
        if (current?.sessionId == expectedSessionId) {
            file.delete()
            File(file.parentFile, "${file.name}.bak").delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        }
    }

    private fun quarantineCorruptFile() {
        val quarantine = File(file.parentFile, "${file.name}.corrupt")
        quarantine.delete()
        if (!file.renameTo(quarantine)) file.delete()
    }

    private fun restoreBackupIfNeeded() {
        val backup = File(file.parentFile, "${file.name}.bak")
        if (!file.exists() && backup.exists()) backup.renameTo(file)
    }

    private companion object {
        const val CURRENT_SCHEMA_VERSION = 2
    }
}
