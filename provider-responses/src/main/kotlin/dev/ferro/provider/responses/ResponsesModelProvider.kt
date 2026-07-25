package dev.ferro.provider.responses

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

class ResponsesModelProvider(
    private val config: ResponsesProviderConfig,
    private val credentials: ProviderCredentialSource,
) : ModelProvider {
    private val calls: Call.Factory = defaultClient()
    private val encoder = ResponsesRequestEncoder()

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
            .url(config.responsesUrl())
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
                    ModelProviderException(kind, retryable = true, message = e.message ?: kind.name, cause = e),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        if (!response.isSuccessful) throw httpFailure(response)
                        val body = response.body ?: throw ModelProviderException(
                            ModelProviderFailureKind.STREAM_PROTOCOL,
                            retryable = true,
                            message = "Responses API returned an empty body",
                        )
                        val decoder = ResponsesSseDecoder()
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

    private fun httpFailure(response: Response): ModelProviderException {
        val kind = when (response.code) {
            401, 403 -> ModelProviderFailureKind.AUTHENTICATION
            408 -> ModelProviderFailureKind.TIMEOUT
            429 -> ModelProviderFailureKind.RATE_LIMIT
            in 400..499 -> ModelProviderFailureKind.INVALID_REQUEST
            else -> ModelProviderFailureKind.SERVER
        }
        return ModelProviderException(
            kind = kind,
            retryable = response.code == 408 || response.code == 429 || response.code >= 500,
            message = "Responses API HTTP ${response.code}",
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
