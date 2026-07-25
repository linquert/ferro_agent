package dev.ferro.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.FerroToolNames
import dev.ferro.contracts.PolicyProfile
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ToolApprovalRequest
import dev.ferro.core.AgentActivity
import dev.ferro.core.AgentSessionPhase
import dev.ferro.core.PendingUserRequest
import dev.ferro.core.TurnOutcome
import dev.ferro.platform.android.AccessibilityServiceRegistry
import dev.ferro.runtime.android.AgentRuntimeClient
import dev.ferro.runtime.android.AgentRuntimePhase
import dev.ferro.runtime.android.AgentRuntimeView
import dev.ferro.runtime.android.RuntimeProviderKind
import dev.ferro.runtime.android.StartAgentRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ProviderKind(
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
) {
    NVIDIA_NIM(
        label = "NVIDIA NIM",
        defaultBaseUrl = "https://integrate.api.nvidia.com/v1",
        defaultModel = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
    ),
    RESPONSES(
        label = "Responses",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5.6-luna",
    ),
}

data class FerroUiState(
    val goal: String = "",
    val providerKind: ProviderKind = ProviderKind.NVIDIA_NIM,
    val baseUrl: String = ProviderKind.NVIDIA_NIM.defaultBaseUrl,
    val model: String = ProviderKind.NVIDIA_NIM.defaultModel,
    val apiKey: String = "",
    val allowedPackages: String = "",
    val policyProfile: PolicyProfile = PolicyProfile.STRICT,
    val allowTextEntry: Boolean = false,
    val allowSystemNavigation: Boolean = true,
    val allowAppLaunch: Boolean = true,
    val accessibilityReady: Boolean = false,
    val overlayReady: Boolean = false,
    val isRunning: Boolean = false,
    val sessionPhase: AgentSessionPhase? = null,
    val companionInput: String = "",
    val pendingUserRequest: PendingUserRequest? = null,
    val pendingToolApproval: ToolApprovalRequest? = null,
    val recoveryPaused: Boolean = false,
    val runtimeStatus: String = "Provider configuration required",
    val events: List<AgentEventEnvelope> = emptyList(),
) {
    val runtimeReady: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank() && accessibilityReady &&
            (recoveryPaused || policyProfile == PolicyProfile.AUTONOMOUS || parsedAllowedPackages().isNotEmpty())

    fun parsedAllowedPackages(): Set<String> = allowedPackages
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter(PACKAGE_NAME::matches)
        .toSet()

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}

class FerroViewModel(application: Application) : AndroidViewModel(application) {
    private val runtimeClient = AgentRuntimeClient(application)
    private val mutableState = MutableStateFlow(FerroUiState())
    val state: StateFlow<FerroUiState> = mutableState.asStateFlow()
    private var runtimeViewJob: Job? = null
    private var pendingStart: StartAgentRequest? = null
    private var pendingRecoveryApiKey: String? = null

    init {
        runtimeClient.bind()
        viewModelScope.launch {
            AccessibilityServiceRegistry.connected.collect { connected ->
                val current = mutableState.value
                mutableState.value = current.copy(
                    accessibilityReady = connected,
                    runtimeStatus = when {
                        current.isRunning -> current.runtimeStatus
                        !connected -> "Accessibility permission required"
                        current.apiKey.isBlank() -> "Provider configuration required"
                        else -> "Ready"
                    },
                )
            }
        }
        viewModelScope.launch {
            runtimeClient.runtime.collect { runtime ->
                runtimeViewJob?.cancel()
                if (runtime == null) return@collect
                dispatchPendingStart(runtime)
                dispatchPendingRecovery(runtime)
                runtimeViewJob = viewModelScope.launch {
                    runtime.view.collect(::applyRuntimeView)
                }
            }
        }
    }

    fun updateGoal(goal: String) {
        mutableState.value = mutableState.value.copy(goal = goal)
    }

    fun updateCompanionInput(input: String) {
        mutableState.value = mutableState.value.copy(companionInput = input)
    }

    fun updateProvider(kind: ProviderKind) {
        if (mutableState.value.recoveryPaused) return
        if (mutableState.value.providerKind == kind) return
        mutableState.value = mutableState.value.copy(
            providerKind = kind,
            baseUrl = kind.defaultBaseUrl,
            model = kind.defaultModel,
            apiKey = "",
            runtimeStatus = "Provider configuration required",
        )
    }

    fun updateBaseUrl(baseUrl: String) {
        if (mutableState.value.recoveryPaused) return
        mutableState.value = mutableState.value.copy(baseUrl = baseUrl)
    }

    fun updateModel(model: String) {
        if (mutableState.value.recoveryPaused) return
        mutableState.value = mutableState.value.copy(model = model)
    }

    fun updateApiKey(apiKey: String) {
        mutableState.value = mutableState.value.copy(
            apiKey = apiKey,
            runtimeStatus = when {
                !mutableState.value.accessibilityReady -> "Accessibility permission required"
                apiKey.isBlank() -> "Provider configuration required"
                else -> "Ready"
            },
        )
    }

    fun updateAllowedPackages(value: String) {
        if (!mutableState.value.isRunning && !mutableState.value.recoveryPaused) {
            mutableState.value = mutableState.value.copy(allowedPackages = value)
        }
    }

    fun updatePolicyProfile(profile: PolicyProfile) {
        if (!mutableState.value.isRunning && !mutableState.value.recoveryPaused) {
            val current = mutableState.value
            mutableState.value = current.copy(
                policyProfile = profile,
                allowTextEntry = profile == PolicyProfile.AUTONOMOUS || current.allowTextEntry,
                allowSystemNavigation = profile == PolicyProfile.AUTONOMOUS || current.allowSystemNavigation,
                allowAppLaunch = profile == PolicyProfile.AUTONOMOUS || current.allowAppLaunch,
            )
        }
    }

    fun updateAllowTextEntry(value: Boolean) {
        mutableState.value = mutableState.value.copy(allowTextEntry = value)
    }

    fun updateAllowSystemNavigation(value: Boolean) {
        mutableState.value = mutableState.value.copy(allowSystemNavigation = value)
    }

    fun updateAllowAppLaunch(value: Boolean) {
        mutableState.value = mutableState.value.copy(allowAppLaunch = value)
    }

    fun openAccessibilitySettings() {
        getApplication<Application>().startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun openOverlaySettings() {
        getApplication<Application>().startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${getApplication<Application>().packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun refreshPermissions() {
        val application = getApplication<Application>()
        mutableState.value = mutableState.value.copy(
            overlayReady = Settings.canDrawOverlays(application),
        )
    }

    fun start() {
        val snapshot = mutableState.value
        val goal = snapshot.goal.trim()
        if (!snapshot.runtimeReady || goal.isEmpty() || snapshot.isRunning) return

        if (snapshot.recoveryPaused) {
            pendingRecoveryApiKey = snapshot.apiKey
            mutableState.value = snapshot.copy(
                isRunning = true,
                runtimeStatus = "Capturing current screen before resuming",
            )
            runtimeClient.ensureForegroundStarted()
            runtimeClient.runtime.value?.let(::dispatchPendingRecovery)
            return
        }

        pendingStart = StartAgentRequest(
            goal = goal,
            providerKind = when (snapshot.providerKind) {
                ProviderKind.NVIDIA_NIM -> RuntimeProviderKind.CHAT_COMPLETIONS
                ProviderKind.RESPONSES -> RuntimeProviderKind.RESPONSES
            },
            baseUrl = snapshot.baseUrl.trim(),
            model = snapshot.model.trim(),
            apiKey = snapshot.apiKey,
            capabilityScope = TaskCapabilityScope(
                id = CapabilityScopeId("scope_${UUID.randomUUID()}"),
                allowedTools = FerroToolNames.all,
                allowedPackages = snapshot.parsedAllowedPackages(),
                transitPackages = setOf("com.android.systemui"),
                allowTextEntry = snapshot.policyProfile == PolicyProfile.AUTONOMOUS || snapshot.allowTextEntry,
                allowSystemNavigation = snapshot.policyProfile == PolicyProfile.AUTONOMOUS ||
                    snapshot.allowSystemNavigation,
                allowAppLaunch = snapshot.policyProfile == PolicyProfile.AUTONOMOUS || snapshot.allowAppLaunch,
                maximumActions = if (snapshot.policyProfile == PolicyProfile.AUTONOMOUS) 1_000 else 100,
                policyProfile = snapshot.policyProfile,
            ),
        )
        mutableState.value = snapshot.copy(
            isRunning = true,
            sessionPhase = null,
            companionInput = "",
            pendingUserRequest = null,
            recoveryPaused = false,
            runtimeStatus = "Starting",
            events = emptyList(),
        )
        runtimeClient.ensureForegroundStarted()
        runtimeClient.runtime.value?.let(::dispatchPendingStart)
    }

    fun cancel() {
        runtimeClient.runtime.value?.interruptActiveTurn()
    }

    fun requestUserControl() {
        runtimeClient.runtime.value?.pauseActiveTurn()
    }

    fun approvePendingTool() {
        runtimeClient.runtime.value?.approvePendingTool()
    }

    fun denyPendingTool() {
        runtimeClient.runtime.value?.denyPendingTool()
    }

    fun submitCompanionInput() {
        val snapshot = mutableState.value
        val instruction = snapshot.companionInput.trim()
        val runtime = runtimeClient.runtime.value ?: return
        val accepted = when (snapshot.sessionPhase) {
            AgentSessionPhase.THINKING,
            AgentSessionPhase.ACTING,
            AgentSessionPhase.PAUSE_REQUESTED,
            -> instruction.isNotEmpty()
            AgentSessionPhase.WAITING_FOR_USER -> instruction.isNotEmpty() && snapshot.pendingUserRequest != null
            AgentSessionPhase.WAITING_FOR_APPROVAL -> false
            AgentSessionPhase.PAUSED -> true
            AgentSessionPhase.IDLE,
            AgentSessionPhase.INTERRUPTING,
            AgentSessionPhase.SHUTDOWN,
            null,
            -> false
        }
        if (!accepted) return
        runtime.submitCompanionInput(instruction)
        mutableState.value = snapshot.copy(companionInput = "")
    }

    override fun onCleared() {
        runtimeViewJob?.cancel()
        runtimeClient.close()
        super.onCleared()
    }

    private fun dispatchPendingStart(runtime: dev.ferro.runtime.android.AgentRuntimeController) {
        val request = pendingStart ?: return
        pendingStart = null
        runtime.startSession(request)
    }

    private fun dispatchPendingRecovery(runtime: dev.ferro.runtime.android.AgentRuntimeController) {
        val apiKey = pendingRecoveryApiKey ?: return
        pendingRecoveryApiKey = null
        runtime.resumeRecovered(apiKey)
    }

    private fun applyRuntimeView(view: AgentRuntimeView) {
        val session = view.snapshot.session
        val current = mutableState.value
        if (view.snapshot.phase == AgentRuntimePhase.IDLE &&
            session == null &&
            !current.recoveryPaused &&
            !current.isRunning
        ) {
            return
        }
        mutableState.value = current.copy(
            isRunning = view.snapshot.phase == AgentRuntimePhase.STARTING ||
                view.snapshot.phase == AgentRuntimePhase.ACTIVE,
            sessionPhase = session?.phase,
            pendingUserRequest = session?.pendingUserRequest,
            pendingToolApproval = session?.pendingToolApproval,
            recoveryPaused = view.snapshot.phase == AgentRuntimePhase.RECOVERY_PAUSED,
            runtimeStatus = runtimeStatus(view),
            events = view.events,
            goal = view.snapshot.recovery?.goal ?: current.goal,
            providerKind = view.snapshot.recovery?.providerKind?.toUiProvider() ?: current.providerKind,
            baseUrl = view.snapshot.recovery?.baseUrl ?: current.baseUrl,
            model = view.snapshot.recovery?.model ?: current.model,
            allowedPackages = view.snapshot.recovery?.capabilityScope?.allowedPackages
                ?.sorted()
                ?.joinToString(", ")
                ?: current.allowedPackages,
            policyProfile = view.snapshot.recovery?.capabilityScope?.policyProfile ?: current.policyProfile,
            allowTextEntry = view.snapshot.recovery?.capabilityScope?.allowTextEntry ?: current.allowTextEntry,
            allowSystemNavigation = view.snapshot.recovery?.capabilityScope?.allowSystemNavigation
                ?: current.allowSystemNavigation,
            allowAppLaunch = view.snapshot.recovery?.capabilityScope?.allowAppLaunch ?: current.allowAppLaunch,
        )
    }

    private fun runtimeStatus(view: AgentRuntimeView): String {
        val session = view.snapshot.session
        return when (view.snapshot.phase) {
            AgentRuntimePhase.STARTING -> "Starting"
            AgentRuntimePhase.FAILED -> "Failed: ${view.snapshot.errorMessage ?: "runtime error"}"
            AgentRuntimePhase.RECOVERY_PAUSED -> view.snapshot.errorMessage
                ?.let { "Recovery paused: $it" }
                ?: "Paused after Android restarted Ferro - enter your API key to resume safely"
            AgentRuntimePhase.ACTIVE -> when (session?.phase) {
                AgentSessionPhase.THINKING -> "Thinking"
                AgentSessionPhase.ACTING -> (session.activity as? AgentActivity.UsingTool)?.heading ?: "Acting"
                AgentSessionPhase.PAUSE_REQUESTED -> "Pausing safely"
                AgentSessionPhase.PAUSED -> "Paused - you have control"
                AgentSessionPhase.WAITING_FOR_USER -> "Waiting for your response"
                AgentSessionPhase.WAITING_FOR_APPROVAL -> "Waiting for approval"
                AgentSessionPhase.INTERRUPTING -> "Stopping"
                AgentSessionPhase.SHUTDOWN -> "Stopped"
                AgentSessionPhase.IDLE, null -> "Starting"
            }
            AgentRuntimePhase.IDLE -> when (val outcome = session?.lastOutcome) {
                is TurnOutcome.Completed -> "Completed"
                is TurnOutcome.Cancelled -> "Cancelled"
                is TurnOutcome.Failed -> "Failed: ${outcome.code}"
                null -> "Ready"
            }
        }
    }
}

private fun RuntimeProviderKind.toUiProvider(): ProviderKind = when (this) {
    RuntimeProviderKind.CHAT_COMPLETIONS -> ProviderKind.NVIDIA_NIM
    RuntimeProviderKind.RESPONSES -> ProviderKind.RESPONSES
}
