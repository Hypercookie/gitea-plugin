package com.github.hypercookie.giteaplugin.api

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class GiteaApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Thin Gitea REST API client. Deliberately minimal for now — just enough to
 * back "Verify connection" in Settings (Phase 1). Expands with PR/review
 * endpoints in Phase 2, once the swagger spike on the local dev instance
 * confirms exact request/response shapes.
 */
class GiteaApiClient(serverUrl: String, private val token: String) {

    private val baseUrl = serverUrl.trimEnd('/')

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getCurrentUser(): GiteaUser = get("/api/v1/user")

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "token $token")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() !in 200..299) {
            throw GiteaApiException(
                response.statusCode(),
                "Gitea API request failed: ${response.statusCode()} ${response.body()}",
            )
        }

        json.decodeFromString(response.body())
    }
}
