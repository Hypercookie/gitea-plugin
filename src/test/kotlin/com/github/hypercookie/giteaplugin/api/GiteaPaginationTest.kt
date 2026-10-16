package com.github.hypercookie.giteaplugin.api

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/**
 * List endpoints against a fake server that pages like Gitea 1.27.3 (checked
 * on the local dev instance): 50 items per page at most, `X-HasMore` only on
 * some endpoints, and timeline pages that come back `null` when every event
 * on them was a filtered-out code comment.
 */
class GiteaPaginationTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer
    private val requests = mutableListOf<String>()

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private val client get() = GiteaApiClient("http://127.0.0.1:${server.address.port}", "token")

    /** Serves [pages] (JSON bodies, 1-based) under [path]; past the end, `[]`. */
    private fun serve(path: String, pages: List<String>, hasMoreHeader: Boolean) {
        server.createContext(path) { exchange ->
            requests += exchange.requestURI.toString()
            val params = exchange.requestURI.query.orEmpty().split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val page = params["page"]?.toInt() ?: 1
            assertEquals("50", params["limit"])
            val body = pages.getOrNull(page - 1) ?: "[]"
            if (hasMoreHeader) exchange.responseHeaders.add("X-HasMore", (page < pages.size).toString())
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun files(range: IntRange) =
        range.joinToString(",", "[", "]") { """{"filename":"module$it/File.java","status":"modified"}""" }

    private fun events(range: IntRange) =
        range.joinToString(",", "[", "]") { """{"id":$it,"type":"comment"}""" }

    fun `test pull request files are read from every page`() = runBlocking {
        serve("/api/v1/repos/o/r/pulls/1/files", listOf(files(1..50), files(51..100), files(101..120)), hasMoreHeader = true)

        val result = client.getPullRequestFiles("o", "r", 1)

        assertEquals((1..120).map { "module$it/File.java" }, result.map { it.filename })
        assertEquals(3, requests.size)
    }

    fun `test pages without X-HasMore are read until an empty page`() = runBlocking {
        serve("/api/v1/repos/o/r/pulls/1/reviews", listOf(reviews(1..50), reviews(51..60)), hasMoreHeader = false)

        assertEquals((1L..60L).toList(), client.listReviews("o", "r", 1).map { it.id })
        assertEquals(3, requests.size)
    }

    fun `test timeline continues past a page of filtered code comments`() = runBlocking {
        serve(
            "/api/v1/repos/o/r/issues/1/timeline",
            listOf(events(1..40), "null", events(41..45)),
            hasMoreHeader = false,
        )

        assertEquals((1L..45L).toList(), client.getTimeline("o", "r", 1).map { it.id })
    }

    fun `test a short page that reaches X-Total-Count ends the list`() = runBlocking {
        server.createContext("/api/v1/repos/o/r/pulls/1/commits") { exchange ->
            requests += exchange.requestURI.toString()
            val page = exchange.requestURI.query.split('&').first { it.startsWith("page=") }.substringAfter('=').toInt()
            val body = when (page) {
                1 -> (1..50).joinToString(",", "[", "]") { commit(it) }
                2 -> (51..60).joinToString(",", "[", "]") { commit(it) }
                else -> "[]"
            }
            exchange.responseHeaders.add("X-Total-Count", "60")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertEquals(60, client.getPullRequestCommits("o", "r", 1).size)
        assertEquals(2, requests.size)
    }

    fun `test short pages below X-Total-Count keep paging`() = runBlocking {
        // A server whose MAX_RESPONSE_ITEMS is 30 answers limit=50 with 30.
        server.createContext("/api/v1/repos/o/r/labels") { exchange ->
            requests += exchange.requestURI.toString()
            val page = exchange.requestURI.query.split('&').first { it.startsWith("page=") }.substringAfter('=').toInt()
            val ids = ((page - 1) * 30 + 1..minOf(page * 30, 45))
            val body = if (ids.isEmpty()) "[]" else ids.joinToString(",", "[", "]") { """{"id":$it,"name":"l$it","color":"fff"}""" }
            exchange.responseHeaders.add("X-Total-Count", "45")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        assertEquals((1L..45L).toList(), client.listLabels("o", "r").map { it.id })
        assertEquals(2, requests.size)
    }

    private fun commit(n: Int) =
        """{"sha":"sha$n","commit":{"message":"m","author":{"name":"a","date":"2026-10-15T12:00:00Z"}}}"""

    private fun reviews(range: IntRange) =
        range.joinToString(",", "[", "]") { """{"id":$it,"state":"COMMENT"}""" }
}
