package com.github.hypercookie.giteaplugin.api

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress

/** Every request the API client sends is counted once, whatever its method
 * and outcome; paged lists count one request per page. */
class GiteaRequestCounterTest : BasePlatformTestCase() {

    private lateinit var server: HttpServer

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/user") { exchange -> respond(exchange, 200, """{"id":1,"login":"me"}""") }
        server.createContext("/api/v1/repos/o/r/pulls/1/reviews") { exchange ->
            val page = exchange.requestURI.query.orEmpty().split('&').firstOrNull { it.startsWith("page=") }?.substringAfter('=')
            respond(exchange, 200, if (page == "1") """[{"id":1,"state":"COMMENT"}]""" else "[]")
        }
        server.createContext("/api/v1/repos/o/r/pulls/comments/5/resolve") { exchange -> respond(exchange, 404, """{"message":"not found"}""") }
        server.start()
    }

    override fun tearDown() {
        try {
            server.stop(0)
        } finally {
            super.tearDown()
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private val client get() = GiteaApiClient("http://127.0.0.1:${server.address.port}", "token")

    private val count get() = GiteaHttpClientService.getInstance().requestCount.value

    fun `test single, paged and failed requests are counted`() = runBlocking {
        val start = count

        client.getCurrentUser()
        assertEquals(start + 1, count)

        // Page 1 has an item, page 2 is empty and ends the list.
        client.listReviews("o", "r", 1)
        assertEquals(start + 3, count)

        assertThrows(GiteaApiException::class.java) { runBlocking { client.resolveComment("o", "r", 5) } }
        assertEquals(start + 4, count)
    }
}
