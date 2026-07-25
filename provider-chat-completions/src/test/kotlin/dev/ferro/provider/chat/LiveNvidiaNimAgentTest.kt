package dev.ferro.provider.chat

import dev.ferro.contracts.ModelToolSpec
import dev.ferro.contracts.CapabilityScopeId
import dev.ferro.contracts.TaskCapabilityScope
import dev.ferro.contracts.ToolRisk
import dev.ferro.contracts.ThreadId
import dev.ferro.contracts.ToolCall
import dev.ferro.contracts.ToolCallRecorded
import dev.ferro.contracts.ToolResult
import dev.ferro.contracts.ToolResultRecorded
import dev.ferro.contracts.ToolResultStatus
import dev.ferro.contracts.TurnCompleted
import dev.ferro.contracts.TurnId
import dev.ferro.core.AgentTurnLoop
import dev.ferro.core.EventSourcedModelContextBuilder
import dev.ferro.core.InMemoryAgentEventStore
import dev.ferro.core.ProviderCredentialSource
import dev.ferro.core.ToolExecutionContext
import dev.ferro.core.ToolApprovalBroker
import dev.ferro.core.ToolAuthorizationGate
import dev.ferro.core.ToolAuthorizationPolicy
import dev.ferro.core.ToolRiskClassifier
import dev.ferro.core.RiskAssessment
import dev.ferro.core.unboundAuthorizationEvidence
import dev.ferro.core.ToolHandler
import dev.ferro.core.ToolRegistry
import dev.ferro.core.ToolRouter
import dev.ferro.core.TurnOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveNvidiaNimAgentTest {
    @Test
    fun `live model completes a real call result continuation loop`() = runBlocking {
        val key = System.getenv("FERRO_NVIDIA_API_KEY").orEmpty()
        assumeTrue("FERRO_NVIDIA_API_KEY is not configured", key.isNotBlank())
        val eventStore = InMemoryAgentEventStore()
        val probe = ProbeToolHandler()
        val toolRouter = ToolRouter(ToolRegistry(listOf(probe)))
        val provider = ChatCompletionsModelProvider(
            config = ChatCompletionsProviderConfig(
                baseUrl = System.getenv("FERRO_NVIDIA_BASE_URL")
                    ?: "https://integrate.api.nvidia.com/v1",
                model = System.getenv("FERRO_NVIDIA_MODEL")
                    ?: "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                enableThinking = false,
                reasoningBudget = 0,
                maxTokens = 512,
                temperature = 0.0,
            ),
            credentials = ProviderCredentialSource { key },
        )
        val runner = AgentTurnLoop(
            eventStore = eventStore,
            contextBuilder = EventSourcedModelContextBuilder(eventStore, toolRouter),
            provider = provider,
            authorizationGate = ToolAuthorizationGate(
                scope = TaskCapabilityScope(
                    id = CapabilityScopeId("live-probe-scope"),
                    allowedTools = setOf("report_probe"),
                    allowedPackages = emptySet(),
                ),
                evidenceProvider = dev.ferro.core.ToolAuthorizationEvidenceProvider {
                    unboundAuthorizationEvidence()
                },
                approvalBroker = ToolApprovalBroker(eventStore),
                toolRouter = toolRouter,
                policy = ToolAuthorizationPolicy(
                    ToolRiskClassifier { _, _ ->
                        RiskAssessment(ToolRisk.OBSERVATION, "Report test probe", "Test-only observation")
                    },
                ),
            ),
        )
        val threadId = ThreadId("live-nvidia-thread")
        val turnId = TurnId("live-nvidia-turn")

        val outcome = runner.run(
            threadId,
            turnId,
            "Call report_probe exactly once with nonce FERRO_PROBE_7. After its successful result, " +
                "reply with exactly FERRO_NVIDIA_AGENT_OK and no other text.",
        )
        val events = eventStore.readThread(threadId)
        val calls = events.mapNotNull { it.payload as? ToolCallRecorded }
        val results = events.mapNotNull { it.payload as? ToolResultRecorded }

        assertEquals(TurnOutcome.Completed("FERRO_NVIDIA_AGENT_OK"), outcome)
        assertEquals(1, calls.size)
        assertEquals("report_probe", calls.single().call.name)
        assertEquals("FERRO_PROBE_7", calls.single().call.arguments.getValue("nonce").jsonPrimitive.content)
        assertEquals(ToolResultStatus.SUCCESS, results.single().result.status)
        assertTrue(events.last().payload is TurnCompleted)
    }

    private class ProbeToolHandler : ToolHandler {
        override val spec = ModelToolSpec(
            name = "report_probe",
            description = "Return a deterministic success result for the supplied test nonce.",
            requiredArguments = setOf("nonce"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("nonce", buildJsonObject {
                        put("type", "string")
                        put("description", "The exact nonce requested by the user")
                    })
                })
                put("additionalProperties", false)
            },
        )

        override suspend fun execute(context: ToolExecutionContext, call: ToolCall): ToolResult {
            val nonce = call.arguments["nonce"]?.jsonPrimitive?.content
            return if (nonce == "FERRO_PROBE_7") {
                ToolResult(
                    callId = call.id,
                    status = ToolResultStatus.SUCCESS,
                    output = buildJsonObject {
                        put("accepted", true)
                        put("nonce", nonce)
                    },
                    message = "Probe accepted",
                )
            } else {
                ToolResult(
                    callId = call.id,
                    status = ToolResultStatus.RECOVERABLE_FAILURE,
                    output = buildJsonObject { put("accepted", false) },
                    message = "Unexpected nonce",
                )
            }
        }
    }
}
