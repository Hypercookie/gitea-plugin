package com.github.hypercookie.giteaplugin.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaErrorMessageTest {

    @Test
    fun `uses message from Gitea error json`() {
        // Verbatim response of Gitea 1.27.3 to REQUEST_CHANGES without a body.
        val body = """{"message":"review event REQUEST_CHANGES requires a body","url":"http://localhost:3000/api/swagger"}"""
        assertEquals("HTTP 422: review event REQUEST_CHANGES requires a body", giteaErrorMessage(422, body))
    }

    @Test
    fun `keeps long Gitea messages in full`() {
        val long = "x".repeat(1000)
        assertEquals("HTTP 500: $long", giteaErrorMessage(500, """{"message":"$long"}"""))
    }

    @Test
    fun `falls back to raw body when json has no message`() {
        assertEquals("""HTTP 404: {"errors":["not found"]}""", giteaErrorMessage(404, """{"errors":["not found"]}"""))
    }

    @Test
    fun `falls back to raw body when message is not a string`() {
        assertEquals("""HTTP 400: {"message":null}""", giteaErrorMessage(400, """{"message":null}"""))
    }

    @Test
    fun `falls back to raw body for malformed json`() {
        assertEquals("HTTP 422: {\"message\": \"unterminated", giteaErrorMessage(422, "{\"message\": \"unterminated"))
    }

    @Test
    fun `shortens long non-json bodies`() {
        val html = "<html><body>" + "Bad Gateway ".repeat(100) + "</body></html>"
        val message = giteaErrorMessage(502, html)
        assertTrue(message, message.startsWith("HTTP 502: <html><body>Bad Gateway"))
        assertTrue(message, message.endsWith("…"))
        assertTrue(message, message.length < html.length)
    }

    @Test
    fun `reports status only for empty body`() {
        assertEquals("HTTP 401", giteaErrorMessage(401, ""))
        assertEquals("HTTP 401", giteaErrorMessage(401, "  \n"))
        assertEquals("HTTP 401", giteaErrorMessage(401, null))
    }
}
