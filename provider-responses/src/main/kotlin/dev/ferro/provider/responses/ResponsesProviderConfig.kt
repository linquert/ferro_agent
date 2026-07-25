package dev.ferro.provider.responses

data class ResponsesProviderConfig(
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-5.6-luna",
    val reasoningEffort: String = "low",
) {
    init {
        require(baseUrl.isNotBlank()) { "Provider base URL must not be blank" }
        require(model.isNotBlank()) { "Provider model must not be blank" }
        require(reasoningEffort in REASONING_EFFORTS) { "Unsupported reasoning effort" }
    }

    fun responsesUrl(): String = "${baseUrl.trimEnd('/')}/responses"

    private companion object {
        val REASONING_EFFORTS = setOf("none", "low", "medium", "high", "xhigh", "max")
    }
}
