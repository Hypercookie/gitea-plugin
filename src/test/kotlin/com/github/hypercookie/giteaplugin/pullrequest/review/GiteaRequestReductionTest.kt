package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.GiteaHttpClientService
import com.github.hypercookie.giteaplugin.api.dto.PrBranchInfo
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.pullrequest.reactions.GiteaReactable
import com.github.hypercookie.giteaplugin.pullrequest.reactions.GiteaReactionsService
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Requests the review data saves: reviews without comments and, in the
 * background poll, unchanged reviews aren't asked for their comments;
 * commits and commit files are fetched once; reaction lists aren't fetched
 * again while fresh.
 */
class GiteaRequestReductionTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope
    private val hits = ConcurrentHashMap<String, AtomicInteger>()

    @Volatile private var reviewsJson = """[{"id":7,"state":"COMMENT","comments_count":1,"updated_at":"t1"},{"id":8,"state":"APPROVED","comments_count":0}]"""
    @Volatile private var review7Comments = listOf("first")

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        respond("/api/v1/repos/o/r/pulls/1/reviews/7/comments") {
            review7Comments.mapIndexed { i, body -> """{"id":${i + 1},"body":"$body","path":"a.txt","position":1,"pull_request_review_id":7}""" }
                .joinToString(",", "[", "]")
        }
        respond("/api/v1/repos/o/r/pulls/1/reviews/8/comments") { "[]" }
        respond("/api/v1/repos/o/r/pulls/1/reviews") { reviewsJson }
        respond("/api/v1/repos/o/r/pulls/1/commits") { """[{"sha":"c1","commit":{"message":"m","author":{"name":"a","date":"2026-10-15T12:00:00Z"}}}]""" }
        respond("/api/v1/repos/o/r/git/commits/c1") { """{"sha":"c1","parents":[],"files":[{"filename":"a.txt","status":"modified"}]}""" }
        respond("/api/v1/repos/o/r/issues/comments/1/reactions") { "[]" }
        respond("/api/v1/user") { """{"id":1,"login":"me"}""" }
    }

    override fun tearDown() {
        try {
            scope.cancel()
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private val serverUrl get() = "http://127.0.0.1:${server.address.port}"

    private fun hits(path: String) = hits[path]?.get() ?: 0

    private fun respond(path: String, response: () -> String) {
        server.createContext(path) { exchange ->
            val bytes = if (exchange.requestURI.path == path) {
                hits.computeIfAbsent(path) { AtomicInteger() }.incrementAndGet()
                response().toByteArray()
            } else {
                "[]".toByteArray()
            }
            exchange.responseHeaders.add("X-HasMore", "false")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun data(): GiteaPullRequestReviewData {
        val repo = GiteaRepoContext(GiteaAccount(id = "test", name = "test", serverUrl = serverUrl), "o", "r", "$serverUrl/o/r.git")
        val pr = PullRequest(
            id = 1, number = 1, title = "Test", state = "open",
            base = PrBranchInfo(ref = "main", sha = "base"),
            head = PrBranchInfo(ref = "feature", sha = "head"),
        )
        return GiteaPullRequestReviewData(project, scope, repo, GiteaApiClient(serverUrl, "token"), pr, PendingReviewStore())
    }

    private suspend fun GiteaPullRequestReviewData.awaitComments(vararg bodies: String) =
        withTimeout(TIMEOUT_MS) { comments.first { it.map { c -> c.body } == bodies.toList() } }

    fun `test reviews without comments are not asked for them`() = runBlocking {
        val data = data()
        data.awaitComments("first")

        assertEquals(1, hits("/api/v1/repos/o/r/pulls/1/reviews/7/comments"))
        assertEquals(0, hits("/api/v1/repos/o/r/pulls/1/reviews/8/comments"))
        assertEquals(2, data.reviews.value?.size)
    }

    fun `test the poll only fetches the comments of changed reviews`() = runBlocking {
        val data = data()
        data.awaitComments("first")

        // Unchanged count and update time: the earlier comments stay.
        review7Comments = listOf("edited elsewhere")
        data.reloadComments(full = false)
        assertEquals(1, hits("/api/v1/repos/o/r/pulls/1/reviews/7/comments"))
        assertEquals(listOf("first"), data.comments.value.map { it.body })

        // A new comment changes the count, so that review is fetched again.
        review7Comments = listOf("edited elsewhere", "added")
        reviewsJson = reviewsJson.replace("\"comments_count\":1", "\"comments_count\":2")
        data.reloadComments(full = false)
        assertEquals(2, hits("/api/v1/repos/o/r/pulls/1/reviews/7/comments"))
        assertEquals(listOf("edited elsewhere", "added"), data.comments.value.map { it.body })

        // A full load fetches it whatever changed.
        data.reloadComments(full = true)
        assertEquals(3, hits("/api/v1/repos/o/r/pulls/1/reviews/7/comments"))
    }

    fun `test commits and commit files are fetched once per head`() = runBlocking {
        val data = data()
        repeat(3) {
            assertEquals(listOf("c1"), data.commits().map { it.sha })
            assertEquals(listOf("a.txt"), data.commitWithFiles("c1").files.orEmpty().map { it.filename })
        }
        assertEquals(1, hits("/api/v1/repos/o/r/pulls/1/commits"))
        assertEquals(1, hits("/api/v1/repos/o/r/git/commits/c1"))

        data.update(data.pullRequest.value.copy(head = PrBranchInfo(ref = "feature", sha = "newhead")))
        data.commits()
        assertEquals(2, hits("/api/v1/repos/o/r/pulls/1/commits"))
    }

    fun `test fresh reaction lists are not fetched again`() = runBlocking {
        val reactions = GiteaReactionsService(scope, GiteaApiClient(serverUrl, "token"), "o", "r", { 1L }, serverUrl)
        reactions.reactions(GiteaReactable.Comment(1))
        val path = "/api/v1/repos/o/r/issues/comments/1/reactions"
        withTimeout(TIMEOUT_MS) { while (hits(path) == 0) delay(10) }

        reactions.refresh(GiteaReactionsService.BACKGROUND_MAX_AGE_MS)
        reactions.refresh()
        delay(300)
        assertEquals(1, hits(path))
    }

    fun `test endpoints are counted without ids`() {
        assertEquals("/repos/{owner}/{repo}/pulls/{id}/reviews/{id}/comments", GiteaHttpClientService.endpointOf("/api/v1/repos/o/r/pulls/12/reviews/34/comments"))
        assertEquals("/repos/{owner}/{repo}/commits/{id}/status", GiteaHttpClientService.endpointOf("/api/v1/repos/o/r/commits/0123abcd/status"))
        assertEquals("/repos/{owner}/{repo}/contents/{path}", GiteaHttpClientService.endpointOf("/api/v1/repos/o/r/contents/src/Main.kt"))
        assertEquals("/repos/issues/search", GiteaHttpClientService.endpointOf("/api/v1/repos/issues/search"))
        assertEquals("/user", GiteaHttpClientService.endpointOf("/api/v1/user"))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
