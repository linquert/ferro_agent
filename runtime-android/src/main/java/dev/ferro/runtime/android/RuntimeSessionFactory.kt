package dev.ferro.runtime.android

import android.content.Context
import dev.ferro.contracts.AgentOperation
import dev.ferro.contracts.StartTurn
import dev.ferro.contracts.SubmissionId
import dev.ferro.contracts.TurnId
import dev.ferro.core.AgentSession
import dev.ferro.core.AgentLifecycleToolCatalog
import dev.ferro.core.AgentSessionSnapshot
import dev.ferro.core.AgentTurnLoop
import dev.ferro.core.EventSourcedModelContextBuilder
import dev.ferro.core.ProviderCredentialSource
import dev.ferro.core.SessionIo
import dev.ferro.core.TaskCapabilityJournal
import dev.ferro.core.ToolApprovalBroker
import dev.ferro.core.ToolAuthorizationGate
import dev.ferro.core.ToolRegistry
import dev.ferro.core.ToolRouter
import dev.ferro.core.ToolAuthorizationHashes
import dev.ferro.core.TurnRecoveryPreparer
import dev.ferro.core.TurnLifecycleJournal
import dev.ferro.core.UserInteractionBroker
import dev.ferro.core.UserInteractionToolCatalog
import dev.ferro.core.UuidIdGenerator
import dev.ferro.platform.android.AndroidDeviceToolCatalog
import dev.ferro.platform.android.AndroidJsonlAgentEventStore
import dev.ferro.platform.android.AndroidModelAttachmentResolver
import dev.ferro.provider.chat.ChatCompletionsModelProvider
import dev.ferro.provider.chat.ChatCompletionsProviderConfig
import dev.ferro.provider.responses.ResponsesModelProvider
import dev.ferro.provider.responses.ResponsesProviderConfig
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

internal interface RuntimeSession {
    val sessionId: String
    val threadId: dev.ferro.contracts.ThreadId
    val turnId: TurnId
    val snapshot: StateFlow<AgentSessionSnapshot>
    val events: Flow<List<dev.ferro.contracts.AgentEventEnvelope>>
    suspend fun submit(operation: AgentOperation): SubmissionId
    suspend fun prepareNewStart(goal: String)
    suspend fun prepareRecovery()
    suspend fun shutdownAndJoin()
}

internal interface RuntimeSessionFactory {
    fun create(
        request: StartAgentRequest,
        scope: CoroutineScope,
        recovered: ActiveRuntimeRecord? = null,
    ): RuntimeSession
}

internal class AndroidRuntimeSessionFactory(
    context: Context,
    private val eventStore: AndroidJsonlAgentEventStore,
    private val ids: UuidIdGenerator,
) : RuntimeSessionFactory {
    private val applicationContext = context.applicationContext

    override fun create(
        request: StartAgentRequest,
        scope: CoroutineScope,
        recovered: ActiveRuntimeRecord?,
    ): RuntimeSession {
        val sessionId = recovered?.sessionId ?: "session_${UUID.randomUUID()}"
        val threadId = recovered?.threadId ?: ids.threadId()
        val turnId = recovered?.turnId ?: ids.turnId()
        val credentials = ProviderCredentialSource { request.apiKey }
        val provider = when (request.providerKind) {
            RuntimeProviderKind.CHAT_COMPLETIONS -> ChatCompletionsModelProvider(
                ChatCompletionsProviderConfig(request.baseUrl.trim(), request.model.trim()),
                credentials,
            )
            RuntimeProviderKind.RESPONSES -> ResponsesModelProvider(
                ResponsesProviderConfig(request.baseUrl.trim(), request.model.trim()),
                credentials,
            )
        }
        val broker = UserInteractionBroker(eventStore)
        val approvalBroker = ToolApprovalBroker(eventStore)
        val androidTools = AndroidDeviceToolCatalog(applicationContext)
        val toolRouter = ToolRouter(
            ToolRegistry(
                androidTools.handlers() +
                    UserInteractionToolCatalog(broker, androidTools.userControlRecovery()).handlers() +
                    AgentLifecycleToolCatalog().handlers(),
            ),
        )
        val authorizationGate = ToolAuthorizationGate(
            scope = request.capabilityScope,
            evidenceProvider = androidTools.authorizationEvidenceProvider(),
            approvalBroker = approvalBroker,
            toolRouter = toolRouter,
        )
        val turnLoop = AgentTurnLoop(
            eventStore = eventStore,
            contextBuilder = EventSourcedModelContextBuilder(
                eventStore,
                toolRouter,
                AndroidModelAttachmentResolver(applicationContext),
            ),
            provider = provider,
            authorizationGate = authorizationGate,
            toolCallBinder = androidTools.toolCallBinder(),
            ids = ids,
        )
        val io = AgentSession(threadId, turnLoop, eventStore, broker, approvalBroker).startIn(scope)
        return SessionIoAdapter(
            sessionId,
            threadId,
            turnId,
            io,
            TurnLifecycleJournal(eventStore),
            TaskCapabilityJournal(eventStore),
            request.capabilityScope,
            TurnRecoveryPreparer(eventStore, toolRouter, ids),
        )
    }
}

private class SessionIoAdapter(
    override val sessionId: String,
    override val threadId: dev.ferro.contracts.ThreadId,
    override val turnId: TurnId,
    private val delegate: SessionIo,
    private val lifecycleJournal: TurnLifecycleJournal,
    private val capabilityJournal: TaskCapabilityJournal,
    private val capabilityScope: dev.ferro.contracts.TaskCapabilityScope,
    private val recoveryPreparer: TurnRecoveryPreparer,
) : RuntimeSession {
    override val snapshot = delegate.snapshot
    override val events = delegate.events

    override suspend fun submit(operation: AgentOperation) = delegate.submit(operation)

    override suspend fun prepareNewStart(goal: String) {
        lifecycleJournal.ensureStarted(threadId, turnId, goal)
        capabilityJournal.ensureEstablished(threadId, turnId, capabilityScope)
    }

    override suspend fun prepareRecovery() {
        capabilityJournal.ensureEstablished(threadId, turnId, capabilityScope)
        recoveryPreparer.prepareExplicitResume(threadId, turnId)
    }

    override suspend fun shutdownAndJoin() = delegate.shutdownAndJoin()
}

internal suspend fun RuntimeSession.start(request: StartAgentRequest) {
    submit(StartTurn(turnId, request.goal.trim()))
}

internal fun RuntimeSession.activeRecord(request: StartAgentRequest, startedAtEpochMs: Long) =
    ActiveRuntimeRecord(
        sessionId = sessionId,
        threadId = threadId,
        turnId = turnId,
        goal = request.goal.trim(),
        providerKind = request.providerKind,
        baseUrl = request.baseUrl.trim(),
        model = request.model.trim(),
        startedAtEpochMs = startedAtEpochMs,
        capabilityScope = request.capabilityScope,
        capabilityScopeHash = ToolAuthorizationHashes.scope(request.capabilityScope),
    )
