package com.github.hypercookie.giteaplugin.api

import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import com.github.hypercookie.giteaplugin.api.dto.CreateIssueCommentOption
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewCommentReplyOptions
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.IssueComment
import com.github.hypercookie.giteaplugin.api.dto.PullReview
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

class GiteaApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Gitea REST API client (`/api/v1`). Endpoint shapes confirmed against a live
 * Gitea 1.27.3 instance's `/swagger.v1.json` — see
 * docs/plan/pr-review-feature-plan.md Phase 2 for the spike notes.
 */
class GiteaApiClient(serverUrl: String, private val token: String) {

    private val baseUrl = serverUrl.trimEnd('/')

    // Shared across all instances: each java.net.http.HttpClient owns a
    // selector thread + executor, and Phase 4+ construct a fresh
    // GiteaApiClient on every refresh/action — a per-instance client would
    // leak one of each per call site.
    private val httpClient: HttpClient get() = sharedHttpClient

    private val json = Json { ignoreUnknownKeys = true }

    // --- Auth -----------------------------------------------------------

    suspend fun getCurrentUser(): GiteaUser = get("/api/v1/user")

    // --- Pull requests ----------------------------------------------------

    suspend fun listPullRequests(owner: String, repo: String, state: String = "open"): List<PullRequest> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls?state=${enc(state)}")

    suspend fun getPullRequest(owner: String, repo: String, index: Long): PullRequest =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index")

    suspend fun getPullRequestFiles(owner: String, repo: String, index: Long): List<ChangedFile> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/files")

    // --- Top-level (issue-stream) comments ---------------------------------

    suspend fun listIssueComments(owner: String, repo: String, index: Long): List<IssueComment> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/issues/$index/comments")

    suspend fun createIssueComment(owner: String, repo: String, index: Long, body: String): IssueComment =
        post(
            "/api/v1/repos/${enc(owner)}/${enc(repo)}/issues/$index/comments",
            CreateIssueCommentOption(body),
        )

    // --- Reviews & inline comments -----------------------------------------

    suspend fun listReviews(owner: String, repo: String, index: Long): List<PullReview> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/reviews")

    suspend fun listReviewComments(owner: String, repo: String, index: Long, reviewId: Long): List<PullReviewComment> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/reviews/$reviewId/comments")

    /** Creates a review; when `options.event` isn't `PENDING`, this also
     * submits it immediately — Gitea does both in one call. */
    suspend fun createReview(owner: String, repo: String, index: Long, options: CreatePullReviewOptions): PullReview =
        post("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/reviews", options)

    suspend fun replyToComment(owner: String, repo: String, index: Long, commentId: Long, body: String): PullReviewComment =
        post(
            "/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/comments/$commentId/replies",
            CreatePullReviewCommentReplyOptions(body),
        )

    suspend fun resolveComment(owner: String, repo: String, commentId: Long) =
        postNoBody("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/comments/$commentId/resolve")

    suspend fun unresolveComment(owner: String, repo: String, commentId: Long) =
        postNoBody("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/comments/$commentId/unresolve")

    // --- HTTP plumbing ----------------------------------------------------

    private fun enc(segment: String): String = URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    private fun requestBuilder(path: String): HttpRequest.Builder = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl$path"))
        .header("Authorization", "token $token")
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(15))

    private suspend inline fun <reified T> get(path: String): T = withContext(Dispatchers.IO) {
        val response = httpClient.send(
            requestBuilder(path).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        decode(response)
    }

    private suspend inline fun <reified B, reified T> post(path: String, body: B): T = withContext(Dispatchers.IO) {
        val response = httpClient.send(
            requestBuilder(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.encodeToString<B>(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        decode(response)
    }

    /** For endpoints with no request or response body (resolve/unresolve). */
    private suspend fun postNoBody(path: String): Unit = withContext(Dispatchers.IO) {
        val response = httpClient.send(
            requestBuilder(path)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        checkStatus(response)
    }

    private fun checkStatus(response: HttpResponse<String>) {
        if (response.statusCode() !in 200..299) {
            throw GiteaApiException(
                response.statusCode(),
                "Gitea API request failed: ${response.statusCode()} ${response.body()}",
            )
        }
    }

    private inline fun <reified T> decode(response: HttpResponse<String>): T {
        checkStatus(response)
        return json.decodeFromString(response.body())
    }

    companion object {
        private val sharedHttpClient: HttpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }
}
