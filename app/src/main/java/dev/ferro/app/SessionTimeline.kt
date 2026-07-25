package dev.ferro.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ferro.contracts.AgentEventEnvelope
import dev.ferro.contracts.AgentEventPayload
import dev.ferro.contracts.AssistantMessageRecorded
import dev.ferro.contracts.AssistantReasoningRecorded
import dev.ferro.contracts.ModelIterationStarted
import dev.ferro.contracts.ModelResponseCompleted
import dev.ferro.contracts.ThreadStarted
import dev.ferro.contracts.TaskCapabilityScopeEstablished
import dev.ferro.contracts.ToolApprovalDenied
import dev.ferro.contracts.ToolApprovalExpired
import dev.ferro.contracts.ToolApprovalGranted
import dev.ferro.contracts.ToolApprovalRequested
import dev.ferro.contracts.ToolAttachmentRef
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolCallOrigin
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.TurnCancelled
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnFailed
import dev.ferro.contracts.TurnStarted
import dev.ferro.contracts.TurnPauseRequested
import dev.ferro.contracts.TurnPaused
import dev.ferro.contracts.TurnResumed
import dev.ferro.contracts.TurnRecoveryPaused
import dev.ferro.contracts.TurnRecoveryResumed
import dev.ferro.contracts.UserInputRecorded
import dev.ferro.contracts.UserRequestAnswered
import dev.ferro.contracts.UserRequestOpened
import dev.ferro.core.AgentSessionPhase
import dev.ferro.platform.android.AndroidScreenArtifactReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class SessionEventText(
    val label: String,
    val detail: String,
    val technicalDetail: String? = null,
)

internal fun sessionEventText(payload: AgentEventPayload): SessionEventText = when (payload) {
    is ThreadStarted -> SessionEventText("Session", payload.title)
    is TurnStarted -> SessionEventText("Task", payload.goal)
    is TaskCapabilityScopeEstablished -> SessionEventText(
        "Task permissions",
        "${payload.scope.policyProfile.name.lowercase()} mode, " +
            "${payload.scope.allowedPackages.size} apps, ${payload.scope.allowedTools.size} tools, " +
            "${payload.scope.maximumActions} action limit",
        "Scope: ${payload.scope.id.value}",
    )
    is ModelIterationStarted -> SessionEventText(
        "Model request",
        payload.contextSummary?.let {
            "${it.inputItems} context items, ${it.images} current screenshot, ${it.advertisedTools} tools"
        } ?: "Context prepared",
        "Context fingerprint: ${payload.contextFingerprint}",
    )
    is AssistantReasoningRecorded -> SessionEventText("Thinking", payload.text)
    is AssistantMessageRecorded -> SessionEventText("Agent", payload.text)
    is ModelResponseCompleted -> SessionEventText(
        "Model response",
        payload.usage?.let {
            "${it.inputTokens} input + ${it.outputTokens} output = ${it.totalTokens} tokens"
        } ?: "Token usage unavailable from provider",
        "Stop reason: ${payload.stopReason.name.lowercase()}",
    )
    is ToolCallRecorded -> SessionEventText(
        if (payload.origin == ToolCallOrigin.RUNTIME_RECOVERY) {
            "Recovery observation"
        } else {
            "Tool call: ${payload.call.name}"
        },
        payload.call.arguments.toString(),
        "Call: ${payload.call.id.value}",
    )
    is ToolResultRecorded -> SessionEventText(
        "Tool result: ${payload.result.status.name.lowercase().replace('_', ' ')}",
        payload.result.message ?: payload.result.output.toString(),
        buildString {
            append("Call: ${payload.result.callId.value}")
            if (payload.result.message != null && payload.result.output.isNotEmpty()) {
                append("\nOutput: ${payload.result.output}")
            }
        },
    )
    is TurnCompleted -> SessionEventText("Complete", payload.finalMessage)
    is TurnFailed -> SessionEventText("Failed", "${payload.code}: ${payload.message}")
    is TurnCancelled -> SessionEventText("Cancelled", payload.reason)
    is UserInputRecorded -> SessionEventText("You", payload.text)
    is UserRequestOpened -> SessionEventText(
        if (payload.kind == dev.ferro.contracts.UserRequestKind.CONTROL) "Your control" else "Agent question",
        payload.prompt,
    )
    is UserRequestAnswered -> SessionEventText("Response received", "Agent is continuing")
    is ToolApprovalRequested -> SessionEventText(
        "Approval required",
        payload.request.actionSummary,
        "Risk: ${payload.request.binding.risk.name.lowercase()}\nRequest: ${payload.request.requestId.value}",
    )
    is ToolApprovalGranted -> SessionEventText(
        "Approved",
        "The exact bound action may continue",
        "Request: ${payload.requestId.value}",
    )
    is ToolApprovalDenied -> SessionEventText(
        "Denied",
        "The action was not executed",
        "Request: ${payload.requestId.value}",
    )
    is ToolApprovalExpired -> SessionEventText(
        "Approval expired",
        payload.reason.name.lowercase().replace('_', ' '),
        "Request: ${payload.requestId.value}",
    )
    TurnPauseRequested -> SessionEventText("Pause requested", "Waiting for a safe boundary")
    TurnPaused -> SessionEventText("Paused", "You have control of the device")
    TurnResumed -> SessionEventText("Resumed", "Agent is rebuilding current context")
    is TurnRecoveryPaused -> SessionEventText("Recovery paused", payload.reason)
    is TurnRecoveryResumed -> SessionEventText(
        "Recovery resumed",
        "Fresh screen captured before restarting the agent loop",
        "Observation: ${payload.observationId}",
    )
}

@Composable
internal fun SessionTimeline(state: FerroUiState, modifier: Modifier) {
    Column(modifier) {
        Text(
            when (state.sessionPhase) {
                AgentSessionPhase.THINKING,
                AgentSessionPhase.ACTING,
                AgentSessionPhase.PAUSE_REQUESTED,
                AgentSessionPhase.INTERRUPTING,
                -> "Live session"
                AgentSessionPhase.PAUSED -> "Paused session"
                AgentSessionPhase.WAITING_FOR_USER -> "Waiting for you"
                else -> "Session trace"
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.events, key = { it.eventId }) { event ->
                SessionEventRow(event)
            }
        }
    }
}

@Composable
private fun SessionEventRow(event: AgentEventEnvelope) {
    val text = sessionEventText(event.payload)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, MaterialTheme.shapes.small)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(text.label, style = MaterialTheme.typography.labelMedium, color = Color(0xFF336B52))
        Text(text.detail, style = MaterialTheme.typography.bodyMedium)
        text.technicalDetail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5F6360),
                fontFamily = FontFamily.Monospace,
            )
        }
        (event.payload as? ToolResultRecorded)?.result?.attachments.orEmpty().forEach { attachment ->
            ScreenshotAttachment(
                attachment = attachment,
                callId = (event.payload as ToolResultRecorded).result.callId.value,
            )
        }
    }
}

@Composable
private fun ScreenshotAttachment(attachment: ToolAttachmentRef, callId: String) {
    val context = LocalContext.current
    val reader = remember(context) { AndroidScreenArtifactReader(context) }
    val loadState by produceState<ScreenshotLoadState>(ScreenshotLoadState.Loading, attachment.uri) {
        value = withContext(Dispatchers.IO) {
            reader.readThumbnail(attachment)?.let(ScreenshotLoadState::Ready)
                ?: ScreenshotLoadState.Missing
        }
    }
    Text(
        "Screenshot returned by this tool call ($callId)",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFF336B52),
    )
    when (val state = loadState) {
        ScreenshotLoadState.Loading -> Text(
            "Loading screenshot",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF5F6360),
        )
        ScreenshotLoadState.Missing -> Text(
            "Screenshot artifact is unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9F2D2D),
        )
        is ScreenshotLoadState.Ready -> Image(
            bitmap = state.bitmap.asImageBitmap(),
            contentDescription = "Screenshot returned by tool call $callId",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(state.bitmap.width.toFloat() / state.bitmap.height.toFloat())
                .background(Color.Black),
            contentScale = ContentScale.Fit,
        )
    }
}

private sealed interface ScreenshotLoadState {
    data object Loading : ScreenshotLoadState
    data object Missing : ScreenshotLoadState
    data class Ready(val bitmap: android.graphics.Bitmap) : ScreenshotLoadState
}
