package dev.ferro.app

import android.Manifest
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.ferro.contracts.PolicyProfile
import dev.ferro.core.AgentSessionPhase
import dev.ferro.runtime.android.AgentHostUiVisibility

class MainActivity : ComponentActivity() {
    private val ferroViewModel: FerroViewModel by viewModels()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF7F7F5)) {
                    FerroScreen(ferroViewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AgentHostUiVisibility.setVisible(true)
    }

    override fun onResume() {
        super.onResume()
        ferroViewModel.refreshPermissions()
    }

    override fun onStop() {
        AgentHostUiVisibility.setVisible(false)
        super.onStop()
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FerroScreen(viewModel: FerroViewModel) {
    val state by viewModel.state.collectAsState()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        val isLandscape = maxWidth > maxHeight
        Column(Modifier.fillMaxSize()) {
            Text("Ferro", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Native Android agent",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF5F6360),
            )
            Spacer(Modifier.height(16.dp))
            if (isLandscape) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    ProviderAndTaskPane(
                        state = state,
                        viewModel = viewModel,
                        modifier = Modifier
                            .weight(1.08f)
                            .verticalScroll(rememberScrollState()),
                    )
                    SessionTimeline(
                        state = state,
                        modifier = Modifier.weight(0.92f),
                    )
                }
            } else {
                val showTimeline = state.events.isNotEmpty() || state.isRunning || state.recoveryPaused
                ProviderAndTaskPane(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (showTimeline) 0.58f else 1f)
                        .verticalScroll(rememberScrollState()),
                )
                if (showTimeline) {
                    Spacer(Modifier.height(16.dp))
                    SessionTimeline(state, Modifier.weight(0.42f))
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ProviderAndTaskPane(
    state: FerroUiState,
    viewModel: FerroViewModel,
    modifier: Modifier,
) {
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ProviderKind.entries.forEachIndexed { index, provider ->
                SegmentedButton(
                    selected = state.providerKind == provider,
                    onClick = { viewModel.updateProvider(provider) },
                    enabled = !state.isRunning && !state.recoveryPaused,
                    shape = SegmentedButtonDefaults.itemShape(index, ProviderKind.entries.size),
                    label = { Text(provider.label) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (state.accessibilityReady) "Device control ready" else "Device control unavailable",
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.accessibilityReady) Color(0xFF336B52) else Color(0xFF9F2D2D),
            )
            if (!state.accessibilityReady) {
                Button(onClick = viewModel::openAccessibilitySettings, enabled = !state.isRunning) {
                    Text("Enable")
                }
            }
        }
        if (!state.isRunning) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (state.overlayReady) "Floating controls ready" else "Floating controls unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.overlayReady) Color(0xFF336B52) else Color(0xFF9F2D2D),
                )
                if (!state.overlayReady) {
                    Button(onClick = viewModel::openOverlaySettings) {
                        Text("Enable")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.baseUrl,
                onValueChange = viewModel::updateBaseUrl,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Provider endpoint") },
                enabled = !state.recoveryPaused,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.model,
                onValueChange = viewModel::updateModel,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model") },
                enabled = !state.recoveryPaused,
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::updateApiKey,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = state.goal,
                onValueChange = viewModel::updateGoal,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Task") },
                enabled = !state.recoveryPaused,
                placeholder = { Text("Describe what Ferro should complete") },
                minLines = 2,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(
                    PolicyProfile.STRICT to "Guarded",
                    PolicyProfile.AUTONOMOUS to "Autonomous",
                ).forEachIndexed { index, (profile, label) ->
                    SegmentedButton(
                        selected = state.policyProfile == profile,
                        onClick = { viewModel.updatePolicyProfile(profile) },
                        enabled = !state.recoveryPaused,
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        label = { Text(label) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.allowedPackages,
                onValueChange = viewModel::updateAllowedPackages,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Allowed Android packages") },
                placeholder = { Text("com.example.app, com.example.second") },
                enabled = !state.recoveryPaused && state.policyProfile != PolicyProfile.AUTONOMOUS,
                minLines = 2,
            )
            if (state.policyProfile != PolicyProfile.AUTONOMOUS) {
                CapabilityToggle("Allow text entry", state.allowTextEntry, viewModel::updateAllowTextEntry)
                CapabilityToggle(
                    "Allow Android navigation",
                    state.allowSystemNavigation,
                    viewModel::updateAllowSystemNavigation,
                )
                CapabilityToggle("Allow app launch", state.allowAppLaunch, viewModel::updateAllowAppLaunch)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            state.runtimeStatus,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF7A4E00),
        )
        Spacer(Modifier.height(12.dp))
        if (!state.isRunning) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::start,
                    enabled = state.runtimeReady && state.goal.isNotBlank(),
                ) {
                    Text(if (state.recoveryPaused) "Resume recovered task" else "Start")
                }
                if (state.recoveryPaused) {
                    OutlinedButton(onClick = viewModel::cancel) {
                        Text("Discard")
                    }
                }
            }
        }
        if (state.sessionPhase != null &&
            state.sessionPhase != AgentSessionPhase.IDLE &&
            state.sessionPhase != AgentSessionPhase.SHUTDOWN
        ) {
            Spacer(Modifier.height(20.dp))
            CompanionControls(state, viewModel)
        } else {
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CompanionControls(state: FerroUiState, viewModel: FerroViewModel) {
    val acceptsInput = state.sessionPhase == AgentSessionPhase.THINKING ||
        state.sessionPhase == AgentSessionPhase.ACTING ||
        state.sessionPhase == AgentSessionPhase.PAUSED ||
        state.sessionPhase == AgentSessionPhase.WAITING_FOR_USER
    val inputLabel = when (state.sessionPhase) {
        AgentSessionPhase.THINKING, AgentSessionPhase.ACTING -> "Steer active task"
        AgentSessionPhase.PAUSED -> "Optional note before resuming"
        AgentSessionPhase.WAITING_FOR_USER -> "Your response"
        else -> "Companion instruction"
    }
    val submitLabel = when (state.sessionPhase) {
        AgentSessionPhase.THINKING, AgentSessionPhase.ACTING -> "Send"
        AgentSessionPhase.PAUSED -> "Resume"
        AgentSessionPhase.WAITING_FOR_USER -> "Respond"
        else -> "Send"
    }

    Text(
        "Companion controls",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium,
    )
    state.pendingUserRequest?.let { request ->
        Spacer(Modifier.height(4.dp))
        Text(
            buildString {
                append(request.reason ?: request.prompt)
                request.suggestedAction?.let {
                    append("\nSuggested action: ")
                    append(it)
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF336B52),
        )
    }
    state.pendingToolApproval?.let { request ->
        Spacer(Modifier.height(4.dp))
        Text(request.actionSummary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            "${request.binding.risk.name.lowercase()} risk in " +
                (request.binding.actionablePackage ?: "current Android surface"),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9F5A00),
        )
        Text(request.reason, style = MaterialTheme.typography.bodySmall, color = Color(0xFF5F6360))
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = viewModel::approvePendingTool) { Text("Approve once") }
            OutlinedButton(onClick = viewModel::denyPendingTool) { Text("Deny") }
        }
    }
    if (acceptsInput) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.companionInput,
            onValueChange = viewModel::updateCompanionInput,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(inputLabel) },
            minLines = 2,
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = viewModel::submitCompanionInput,
            enabled = state.companionInput.isNotBlank() || state.sessionPhase == AgentSessionPhase.PAUSED,
        ) {
            Text(submitLabel)
        }
    }
    if (state.sessionPhase == AgentSessionPhase.THINKING ||
        state.sessionPhase == AgentSessionPhase.ACTING
    ) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = viewModel::requestUserControl) {
                Text("Take control")
            }
            Button(
                onClick = viewModel::cancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9F2D2D)),
            ) {
                Text("Stop")
            }
        }
    }
    if (state.sessionPhase == AgentSessionPhase.PAUSED ||
        state.sessionPhase == AgentSessionPhase.WAITING_FOR_USER ||
        state.sessionPhase == AgentSessionPhase.WAITING_FOR_APPROVAL
    ) {
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = viewModel::cancel,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF9F2D2D)),
        ) {
            Text("Stop")
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun CapabilityToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
    }
}
