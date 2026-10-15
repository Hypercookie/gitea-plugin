package com.github.hypercookie.giteaplugin.pullrequest.reactions

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress

/**
 * Reactions against a fake server answering like Gitea 1.27.3: comment
 * reactions under `/issues/comments/{id}/reactions`, add answering 201 (or
 * 200 with the existing reaction), remove as DELETE with a JSON body.
 */
class GiteaReactionsTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope
    private val requests = mutableListOf<String>()

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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

    private fun reaction(userId: Long, login: String, content: String) =
        """{"user":{"id":$userId,"login":"$login"},"content":"$content","created_at":"2026-10-15T12:00:00Z"}"""

    private fun respond(path: String, handler: (method: String, body: String) -> Pair<Int, String>) {
        server.createContext(path) { exchange ->
            val body = exchange.requestBody.readBytes().decodeToString()
            synchronized(requests) { requests += "${exchange.requestMethod} ${exchange.requestURI.path} $body".trim() }
            val (status, response) = handler(exchange.requestMethod, body)
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
        }
    }

    private fun service() = GiteaReactionsService(scope, GiteaApiClient(serverUrl, "token"), "o", "r", { 1L }, serverUrl)

    fun `test allowed reactions come from the server's UI settings`() = runBlocking {
        respond("/api/v1/settings/ui") { _, _ -> 200 to """{"allowed_reactions":["+1","gitea"],"custom_emojis":["gitea"]}""" }

        val service = service()

        withTimeout(5_000) { service.allowedReactions.first { it == listOf("+1", "gitea") } }
        Unit
    }

    fun `test comment reactions are grouped by name with the current user's marked`() = runBlocking {
        respond("/api/v1/repos/o/r/issues/comments/7/reactions") { _, _ ->
            200 to "[${reaction(1, "me", "+1")},${reaction(2, "other", "+1")},${reaction(2, "other", "heart")}]"
        }
        val me = MutableStateFlow<GiteaUser?>(GiteaUser(1, "me"))

        val vm = GiteaReactionsViewModelImpl(scope, GiteaReactable.Comment(7), me, service())
        val info = withTimeout(5_000) { vm.reactionsWithInfo.first { it.isNotEmpty() } }

        assertEquals(listOf("+1", "heart"), info.keys.toList())
        assertEquals(listOf("me", "other"), info.getValue("+1").users.map { it.login })
        assertTrue(info.getValue("+1").isReactedByCurrentUser)
        assertFalse(info.getValue("heart").isReactedByCurrentUser)
    }

    fun `test toggle adds and then removes the current user's reaction`() = runBlocking {
        respond("/api/v1/repos/o/r/issues/comments/7/reactions") { method, _ ->
            when (method) {
                "GET" -> 200 to "[]"
                "POST" -> 201 to reaction(1, "me", "rocket")
                else -> 200 to ""
            }
        }
        val service = service()
        val vm = GiteaReactionsViewModelImpl(scope, GiteaReactable.Comment(7), MutableStateFlow(GiteaUser(1, "me")), service)
        // Let the initial fetch land first, so it can't overwrite the toggles.
        withTimeout(5_000) { while (synchronized(requests) { requests.none { it.startsWith("GET") } }) delay(10) }
        delay(200)

        vm.toggle("rocket")
        withTimeout(5_000) { vm.reactionsWithInfo.first { it["rocket"]?.isReactedByCurrentUser == true } }
        vm.toggle("rocket")
        withTimeout(5_000) { vm.reactionsWithInfo.first { it.isEmpty() } }

        assertEquals(
            listOf(
                "GET /api/v1/repos/o/r/issues/comments/7/reactions",
                """POST /api/v1/repos/o/r/issues/comments/7/reactions {"content":"rocket"}""",
                """DELETE /api/v1/repos/o/r/issues/comments/7/reactions {"content":"rocket"}""",
            ),
            synchronized(requests) { requests.filter { "/comments/7/" in it } },
        )
    }

    fun `test an existing reaction returned again is not counted twice`() = runBlocking {
        // Paged like Gitea: page 1 holds the reaction, later pages are empty.
        server.createContext("/api/v1/repos/o/r/issues/1/reactions") { exchange ->
            val body = when {
                exchange.requestMethod != "GET" -> reaction(1, "me", "heart")
                exchange.requestURI.query.orEmpty().contains("page=1&") -> "[${reaction(1, "me", "heart")}]"
                else -> "[]"
            }.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val service = service()
        val flow = service.reactions(GiteaReactable.PullRequest)
        withTimeout(5_000) { flow.first { it.isNotEmpty() } }

        service.addReaction(GiteaReactable.PullRequest, "heart")

        assertEquals(1, flow.value.size)
    }
}
