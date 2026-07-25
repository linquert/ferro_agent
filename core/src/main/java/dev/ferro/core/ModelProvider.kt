package dev.ferro.core

import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelResponse

enum class ModelProviderFailureKind {
    AUTHENTICATION,
    RATE_LIMIT,
    INVALID_REQUEST,
    TIMEOUT,
    NETWORK,
    SERVER,
    STREAM_PROTOCOL,
}

class ModelProviderException(
    val kind: ModelProviderFailureKind,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun interface ModelProvider {
    suspend fun sample(request: ModelRequest): ModelResponse
}
