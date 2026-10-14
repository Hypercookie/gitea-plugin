package com.github.hypercookie.giteaplugin.api

import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import com.github.hypercookie.giteaplugin.api.dto.CreateIssueCommentOption
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewCommentReplyOptions
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.CombinedCommitStatus
import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.GitCommitWithFiles
import com.github.hypercookie.giteaplugin.api.dto.GiteaIssue
import com.github.hypercookie.giteaplugin.api.dto.GiteaLabel
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.IssueComment
import com.github.hypercookie.giteaplugin.api.dto.PullReview
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.RepoContent
import com.github.hypercookie.giteaplugin.api.dto.TimelineEvent
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
import java.util.Base64

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

    /** `sort`/`poster` map directly to Gitea's `/pulls` query params (confirmed
     * against `/swagger.v1.json` for Phase 26 — `sort` enum: oldest,
     * recentupdate, recentclose, leastupdate, mostcomment, leastcomment,
     * priority; `poster` filters by PR author login). Both optional/null to
     * keep existing callers unchanged. */
    suspend fun listPullRequests(
        owner: String,
        repo: String,
        state: String = "open",
        sort: String? = null,
        poster: String? = null,
    ): List<PullRequest> {
        val query = buildString {
            append("state=${enc(state)}")
            if (!sort.isNullOrBlank()) append("&sort=${enc(sort)}")
            if (!poster.isNullOrBlank()) append("&poster=${enc(poster)}")
        }
        return get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls?$query")
    }

    /** One page of `/pulls`. Used by the list when a sort order is chosen
     * (the issues endpoints can't sort). */
    suspend fun listPullRequestsPage(
        owner: String,
        repo: String,
        state: String,
        sort: String?,
        poster: String?,
        label: Long?,
        page: Int,
        limit: Int,
    ): List<PullRequest> {
        val query = buildString {
            append("state=${enc(state)}&page=$page&limit=$limit")
            if (!sort.isNullOrBlank()) append("&sort=${enc(sort)}")
            if (!poster.isNullOrBlank()) append("&poster=${enc(poster)}")
            if (label != null) append("&labels=$label")
        }
        return get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls?$query")
    }

    /** One page of pull requests through the repository issues endpoint,
     * which supports text search and author/assignee/label filters. */
    suspend fun searchPullRequestIssues(
        owner: String,
        repo: String,
        state: String,
        query: String?,
        createdBy: String?,
        assignedBy: String?,
        label: String?,
        page: Int,
        limit: Int,
    ): List<GiteaIssue> {
        val q = buildString {
            append("type=pulls&state=${enc(state)}&page=$page&limit=$limit")
            if (!query.isNullOrBlank()) append("&q=${enc(query)}")
            if (!createdBy.isNullOrBlank()) append("&created_by=${enc(createdBy)}")
            if (!assignedBy.isNullOrBlank()) append("&assigned_by=${enc(assignedBy)}")
            if (!label.isNullOrBlank()) append("&labels=${enc(label)}")
        }
        return get("/api/v1/repos/${enc(owner)}/${enc(repo)}/issues?$q")
    }

    /** Pull requests where the current user's review is requested, across
     * all repositories of [owner]; callers filter to one repository. */
    suspend fun searchReviewRequested(owner: String, state: String, page: Int, limit: Int): List<GiteaIssue> =
        get("/api/v1/repos/issues/search?type=pulls&review_requested=true&owner=${enc(owner)}&state=${enc(state)}&page=$page&limit=$limit")

    suspend fun listLabels(owner: String, repo: String): List<GiteaLabel> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/labels?limit=100")

    /** Users who can be assigned (and so also review/author) in [repo]. */
    suspend fun listAssignees(owner: String, repo: String): List<GiteaUser> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/assignees")

    suspend fun getCommitWithFiles(owner: String, repo: String, sha: String): GitCommitWithFiles =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/git/commits/${enc(sha)}?files=true&stat=false&verification=false")

    suspend fun getCombinedStatus(owner: String, repo: String, ref: String): CombinedCommitStatus =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/commits/${enc(ref)}/status")

    suspend fun getPullRequest(owner: String, repo: String, index: Long): PullRequest =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index")

    suspend fun getPullRequestFiles(owner: String, repo: String, index: Long): List<ChangedFile> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/files")

    suspend fun getPullRequestCommits(owner: String, repo: String, index: Long): List<GitCommit> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/pulls/$index/commits")

    /** PRs are issues in Gitea's model, so the timeline lives under
     * `/issues/{index}/timeline` — confirmed against a live instance for
     * Phase 27 (see `TimelineEvent` doc comment). */
    suspend fun getTimeline(owner: String, repo: String, index: Long): List<TimelineEvent> =
        get("/api/v1/repos/${enc(owner)}/${enc(repo)}/issues/$index/timeline")

    /** File text at a given ref, or null if the file doesn't exist there
     * (added/deleted files have one empty side in a diff). */
    suspend fun getFileContentOrNull(owner: String, repo: String, path: String, ref: String): String? =
        try {
            val file: RepoContent = get(
                "/api/v1/repos/${enc(owner)}/${enc(repo)}/contents/${encPath(path)}?ref=${enc(ref)}",
            )
            when {
                file.content == null -> null
                file.encoding == "base64" -> String(Base64.getMimeDecoder().decode(file.content), Charsets.UTF_8)
                else -> file.content
            }
        } catch (e: GiteaApiException) {
            if (e.statusCode == 404) null else throw e
        }

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

    /** All inline comments across all reviews on this PR, flattened. */
    suspend fun getAllReviewComments(owner: String, repo: String, index: Long): List<PullReviewComment> =
        listReviews(owner, repo, index).flatMap { listReviewComments(owner, repo, index, it.id) }

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

    /** Encodes a repo-relative file path segment-by-segment, preserving `/`
     * as the directory separator Gitea's contents endpoint expects. */
    private fun encPath(path: String): String = path.split('/').joinToString("/") { enc(it) }

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
