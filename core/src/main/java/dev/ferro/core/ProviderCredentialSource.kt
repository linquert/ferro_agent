package dev.ferro.core

fun interface ProviderCredentialSource {
    suspend fun bearerToken(): String
}
