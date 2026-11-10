package com.github.hypercookie.giteaplugin.debug

import junit.framework.TestCase
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class GiteaErrorReportSubmitterTest : TestCase() {

    private fun decodedBody(url: String): String =
        URLDecoder.decode(url.substringAfter("&body="), StandardCharsets.UTF_8)

    fun testUrlCarriesVersionIdeAndTrace() {
        val url = GiteaErrorReportSubmitter.buildIssueUrl("1.2.0", "IDEA 2026.3", "clicked refresh", "java.lang.Boom\n\tat A.b", "Boom")
        assertTrue(url.startsWith("https://github.com/Hypercookie/gitea-plugin/issues/new?title="))
        val body = decodedBody(url)
        assertTrue(body.contains("Plugin: 1.2.0"))
        assertTrue(body.contains("IDE: IDEA 2026.3"))
        assertTrue(body.contains("clicked refresh"))
        assertTrue(body.contains("java.lang.Boom"))
    }

    fun testLongTraceIsTruncated() {
        val url = GiteaErrorReportSubmitter.buildIssueUrl("1", "IDE", null, "x".repeat(10_000), null)
        assertTrue(decodedBody(url).contains("(truncated)"))
        assertTrue(url.length < 8_000)
    }

    fun testMissingInfoStillBuildsUrl() {
        val url = GiteaErrorReportSubmitter.buildIssueUrl("1", "IDE", null, null, null)
        assertTrue(decodedBody(url).contains("Please describe."))
    }
}
