package dev.ferro.provider.chat

import dev.ferro.contracts.ModelRequest
import dev.ferro.contracts.ModelResponse
import dev.ferro.core.ModelProvider
import dev.ferro.core.ModelProviderException
import dev.ferro.core.ModelProviderFailureKind
import dev.ferro.core.ProviderCredentialSource
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ChatCompletionsModelProvider internal constructor(
    private val config: ChatCompletionsProviderConfig,
    private val credentials: ProviderCredentialSource,
    private val calls: Call.Factory,
) : ModelProvider {
    constructor(
        config: ChatCompletionsProviderConfig,
        credentials: ProviderCredentialSource,
    ) : this(config, credentials, defaultClient())

    private val encoder = ChatCompletionsRequestEncoder()

    override suspend fun sample(request: ModelRequest): ModelResponse {
        val token = credentials.bearerToken().trim()
        if (token.isEmpty()) {
            throw ModelProviderException(
                ModelProviderFailureKind.AUTHENTICATION,
                retryable = false,
                message = "Provider credential is empty",
            )
        }
        val httpRequest = Request.Builder()
            .url(config.completionsUrl())
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .header("User-Agent", "Ferro-Android/0.1")
            .post(encoder.encode(config, request).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return execute(httpRequest)
    }

    private suspend fun execute(request: Request): ModelResponse = suspendCancellableCoroutine { continuation ->
        val call = calls.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isActive) return
                val kind = if (e is SocketTimeoutException) {
                    ModelProviderFailureKind.TIMEOUT
                } else {
                    ModelProviderFailureKind.NETWORK
                }
                continuation.resumeWithException(
                    ModelProviderException(kind, retryable = true, e.message ?: kind.name, e),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        if (!response.isSuccessful) throw httpFailure(response.code)
                        val body = response.body ?: throw ModelProviderException(
                            ModelProviderFailureKind.STREAM_PROTOCOL,
                            retryable = true,
                            message = "Chat Completions API returned an empty body",
                        )
                        val decoder = ChatCompletionsSseDecoder()
                        val data = StringBuilder()
                        while (!body.source().exhausted()) {
                            val line = body.source().readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    decoder.accept(data.toString())
                                    data.clear()
                                }
                            } else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trimStart())
                            }
                        }
                        if (data.isNotEmpty()) decoder.accept(data.toString())
                        if (continuation.isActive) continuation.resume(decoder.finish())
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            }
        })
    }

    private fun httpFailure(code: Int): ModelProviderException {
        val kind = when (code) {
            401, 403 -> ModelProviderFailureKind.AUTHENTICATION
            408 -> ModelProviderFailureKind.TIMEOUT
            429 -> ModelProviderFailureKind.RATE_LIMIT
            in 400..499 -> ModelProviderFailureKind.INVALID_REQUEST
            else -> ModelProviderFailureKind.SERVER
        }
        return ModelProviderException(
            kind,
            retryable = code == 408 || code == 429 || code >= 500,
            message = "Chat Completions API HTTP $code",
        )
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
