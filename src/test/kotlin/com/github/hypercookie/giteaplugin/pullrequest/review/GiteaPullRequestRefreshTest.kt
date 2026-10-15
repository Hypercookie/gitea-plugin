package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PrBranchInfo
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.pullrequest.timeline.GiteaTimelineLoader
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the pull request's Refresh reloads, against a fake Gitea server:
 * review comments, the timeline's comments and events, and every other
 * holder of the same pull request.
 */
class GiteaPullRequestRefreshTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope

    @Volatile private var reviewCommentBodies = listOf("first")
    @Volatile private var timelineBodies = listOf("first")

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Handlers may block (see the ordering test); don't stall the others.
        server.executor = Executors.newCachedThreadPool()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        respond("/api/v1/repos/o/r/pulls/1/reviews/7/comments") {
            reviewCommentBodies.mapIndexed { i, body -> comment(i + 1L, body) }.joinToString(",", "[", "]")
        }
        respond("/api/v1/repos/o/r/pulls/1/reviews") { """[{"id":7,"state":"COMMENT"}]""" }
        respond("/api/v1/repos/o/r/issues/1/timeline") {
            timelineBodies.mapIndexed { i, body -> """{"id":${i + 1},"type":"comment","body":"$body"}""" }.joinToString(",", "[", "]")
        }
        respond("/api/v1/repos/o/r/pulls/1/commits") { "[]" }
        respond("/api/v1/repos/o/r/pulls/1") { PR_JSON }
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

    private fun comment(id: Long, body: String) =
        """{"id":$id,"body":"$body","path":"a.txt","position":1,"pull_request_review_id":7}"""

    private fun respond(path: String, response: () -> String) {
        server.createContext(path) { exchange ->
            // Contexts match by prefix; anything deeper has its own.
            val bytes = if (exchange.requestURI.path == path) response().toByteArray() else "[]".toByteArray()
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

    private suspend fun GiteaTimelineLoader.awaitBodies(vararg bodies: String) =
        withTimeout(TIMEOUT_MS) {
            state.first { (it as? GiteaTimelineLoader.State.Items)?.events?.map { e -> e.body } == bodies.toList() }
        }

    fun `test reload fetches review comments again`() = runBlocking {
        val data = data()
        data.awaitComments("first")

        reviewCommentBodies = listOf("first", "added in the browser")
        data.reload()

        data.awaitComments("first", "added in the browser")
        Unit
    }

    fun `test timeline comments reload with their pull request`() = runBlocking {
        val data = data()
        val loader = GiteaTimelineLoader(scope, data)
        loader.awaitBodies("first")

        timelineBodies = listOf("first", "added in the browser")
        data.reload()

        loader.awaitBodies("first", "added in the browser")
        Unit
    }

    fun `test refresh reloads every holder of the same pull request`() = runBlocking {
        val tab = data()
        val diffViewer = data()
        diffViewer.awaitComments("first")

        reviewCommentBodies = listOf("edited")
        GiteaReviewSessionService.getInstance(project).refreshPullRequest(tab)

        diffViewer.awaitComments("edited")
        tab.awaitComments("edited")
        Unit
    }

    fun `test an earlier slow comment fetch can't overwrite a later one`() = runBlocking {
        val calls = AtomicInteger()
        val release = CountDownLatch(1)
        server.removeContext("/api/v1/repos/o/r/pulls/1/reviews/7/comments")
        respond("/api/v1/repos/o/r/pulls/1/reviews/7/comments") {
            // The first fetch (on creation) answers late, with what was
            // there before; every later one answers right away.
            if (calls.incrementAndGet() == 1) {
                release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)
                "[${comment(1, "stale")}]"
            } else {
                "[${comment(1, "fresh")}]"
            }
        }
        val data = data()
        withTimeout(TIMEOUT_MS) { while (calls.get() == 0) delay(10) }

        data.reload()
        delay(300) // the reload's fetch, were it not queued behind the first
        release.countDown()

        data.awaitComments("fresh")
        delay(300)
        assertEquals(listOf("fresh"), data.comments.value.map { it.body })
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val PR_JSON = """{"id":1,"number":1,"title":"Test","state":"open","base":{"ref":"main","sha":"base"},"head":{"ref":"feature","sha":"head"}}"""
    }
}
