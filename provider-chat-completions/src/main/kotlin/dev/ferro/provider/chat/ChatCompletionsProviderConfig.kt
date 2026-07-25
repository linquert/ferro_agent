package dev.ferro.provider.chat

data class ChatCompletionsProviderConfig(
    val baseUrl: String = "https://integrate.api.nvidia.com/v1",
    val model: String = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
    val maxTokens: Int = 8_192,
    val reasoningBudget: Int = 4_096,
    val temperature: Double = 0.6,
    val topP: Double = 0.95,
    val enableThinking: Boolean = true,
) {
    init {
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank" }
        require(model.isNotBlank()) { "Provider model must not be blank" }
        require(maxTokens > 0) { "Maximum tokens must be positive" }
        require(reasoningBudget in 0..maxTokens) { "Reasoning budget must be between zero and maximum tokens" }
        require(temperature in 0.0..2.0) { "Temperature must be between zero and two" }
        require(topP > 0.0 && topP <= 1.0) { "Top-p must be greater than zero and at most one" }
    }

    fun completionsUrl(): String = "${baseUrl.trimEnd('/')}/chat/completions"
}
