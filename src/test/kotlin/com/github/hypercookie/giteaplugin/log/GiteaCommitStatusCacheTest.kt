package com.github.hypercookie.giteaplugin.log

import com.github.hypercookie.giteaplugin.api.GiteaApiException
import com.github.hypercookie.giteaplugin.api.dto.CombinedCommitStatus
import com.github.hypercookie.giteaplugin.api.dto.CommitStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The request gate behind the log's "Gitea Commit Checks" column, against a
 * fake server and a manual clock: what is cached for how long, and how many
 * requests reach the server.
 */
class GiteaCommitStatusCacheTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000L

    /** Answers per sha; a sha without an answer has no statuses. */
    private val answers = ConcurrentHashMap<String, () -> CombinedCommitStatus>()
    private val calls = ConcurrentHashMap<String, AtomicInteger>()
    private val running = AtomicInteger()
    private val maxRunning = AtomicInteger()
    private var gate: CompletableDeferred<Unit>? = null

    private val fetch: suspend (GiteaCommitStatusKey) -> CombinedCommitStatus = { key ->
        calls.getOrPut(key.sha) { AtomicInteger() }.incrementAndGet()
        maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
        try {
            gate?.await()
            (answers[key.sha] ?: { CombinedCommitStatus("pending", null) })()
        } finally {
            running.decrementAndGet()
        }
    }

    private fun cache(policy: GiteaCommitStatusCache.Policy = GiteaCommitStatusCache.Policy()) =
        GiteaCommitStatusCache(scope, policy) { now }

    private fun key(sha: String, repo: String = "repo") = GiteaCommitStatusKey("https://gitea.example.com", "owner", repo, sha)

    private fun combined(state: String, vararg statuses: Pair<String, String>) =
        CombinedCommitStatus(state, statuses.map { (context, status) -> CommitStatus(context, status) })

    private fun callCount(sha: String) = calls[sha]?.get() ?: 0
    private fun totalCalls() = calls.values.sumOf { it.get() }

    private fun GiteaCommitStatusCache.loadNow(vararg keys: GiteaCommitStatusKey, requester: Any = this@GiteaCommitStatusCacheTest): Long? =
        runBlocking { load(requester, keys.toList(), fetch) { _, _ -> } }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `no statuses reads as none, not pending`() {
        // What Gitea 1.27.3 answers for a commit without statuses.
        val checks = GiteaCommitChecks.of(CombinedCommitStatus("pending", null))
        assertEquals(GiteaCheckState.NONE, checks.state)
        assertFalse(checks.running)
        assertFalse(checks.isFinal)
    }

    @Test
    fun `responses of Gitea 1_27_3 are classified`() {
        val json = Json { ignoreUnknownKeys = true }
        val none = json.decodeFromString<CombinedCommitStatus>(
            """{"state":"pending","sha":"91870cc","total_count":0,"statuses":null,"repository":{"id":2},"commit_url":"x","url":"y"}""",
        )
        assertEquals(GiteaCheckState.NONE, GiteaCommitChecks.of(none).state)
        val failed = json.decodeFromString<CombinedCommitStatus>(
            """{"state":"failure","total_count":2,"statuses":[
                {"id":1,"status":"success","target_url":"http://localhost:3000/runs/1","description":"Build passed","context":"ci/build"},
                {"id":3,"status":"failure","target_url":"","description":"Lint failed","context":"ci/lint"}]}""",
        )
        val checks = GiteaCommitChecks.of(failed)
        assertEquals(GiteaCheckState.FAILURE, checks.state)
        assertTrue(checks.isFinal)
        assertEquals("http://localhost:3000/runs/1", checks.statuses[0].targetUrl)
    }

    @Test
    fun `failure with a running context is not final`() {
        val checks = GiteaCommitChecks.of(combined("failure", "ci/lint" to "failure", "ci/deploy" to "pending"))
        assertEquals(GiteaCheckState.FAILURE, checks.state)
        assertTrue(checks.running)
        assertFalse(checks.isFinal)
    }

    @Test
    fun `finished checks are never fetched again`() {
        answers["a"] = { combined("success", "ci/build" to "success") }
        answers["b"] = { combined("failure", "ci/build" to "failure") }
        val cache = cache()
        assertNull(cache.loadNow(key("a"), key("b")))
        now += 7 * 24 * 3_600_000L
        assertNull(cache.loadNow(key("a"), key("b")))
        assertEquals(1, callCount("a"))
        assertEquals(1, callCount("b"))
        assertEquals(GiteaCheckState.FAILURE, cache.cached(key("b"))?.state)
        assertEquals(2, cache.requestCount)
    }

    @Test
    fun `running checks are fetched again only after the pending TTL`() {
        answers["a"] = { combined("pending", "ci/build" to "pending") }
        val cache = cache()
        assertEquals(now + 60_000, cache.loadNow(key("a")))
        now += 59_000
        cache.loadNow(key("a"))
        assertEquals(1, callCount("a"))
        now += 1_000
        cache.loadNow(key("a"))
        assertEquals(2, callCount("a"))
    }

    @Test
    fun `no statuses are fetched again with a doubling TTL`() {
        val cache = cache()
        assertNull(cache.loadNow(key("a")))
        now += 2 * 60_000
        cache.loadNow(key("a"))
        assertEquals(2, callCount("a"))
        now += 2 * 60_000
        cache.loadNow(key("a"))
        assertEquals(2, callCount("a"))
        now += 2 * 60_000
        cache.loadNow(key("a"))
        assertEquals(3, callCount("a"))
        // Capped at 30 minutes.
        repeat(10) {
            now += 30 * 60_000
            cache.loadNow(key("a"))
        }
        assertEquals(13, callCount("a"))
    }

    @Test
    fun `unknown commits are cached as not on server without a pause`() {
        answers["unpushed"] = { throw GiteaApiException(404, "HTTP 404: object does not exist") }
        answers["b"] = { combined("success", "ci/build" to "success") }
        val cache = cache()
        assertNull(cache.loadNow(key("unpushed"), key("b")))
        assertEquals(GiteaCheckState.NOT_ON_SERVER, cache.cached(key("unpushed"))?.state)
        assertEquals(GiteaCheckState.SUCCESS, cache.cached(key("b"))?.state)
        cache.loadNow(key("unpushed"))
        assertEquals(1, callCount("unpushed"))
    }

    @Test
    fun `server errors pause the server with a growing backoff and are not cached`() {
        var fail = true
        answers["a"] = { if (fail) throw GiteaApiException(503, "HTTP 503") else combined("success", "ci" to "success") }
        val cache = cache()
        assertEquals(now + 30_000, cache.loadNow(key("a")))
        assertNull(cache.cached(key("a")))

        // Paused: nothing is sent, not even for other commits or repositories.
        now += 29_000
        assertEquals(now + 1_000, cache.loadNow(key("a"), key("b"), key("c", repo = "other")))
        assertEquals(1, totalCalls())

        now += 1_000
        assertEquals(now + 60_000, cache.loadNow(key("a")))
        assertEquals(2, callCount("a"))

        now += 60_000
        fail = false
        assertNull(cache.loadNow(key("a")))
        assertEquals(GiteaCheckState.SUCCESS, cache.cached(key("a"))?.state)
        assertEquals(3, callCount("a"))
    }

    @Test
    fun `network errors and 429 pause the server`() {
        answers["a"] = { throw IOException("Connection refused") }
        val cache = cache()
        assertNotNull(cache.loadNow(key("a")))
        cache.loadNow(key("b"))
        assertEquals(0, callCount("b"))

        val other = cache()
        answers["c"] = { throw GiteaApiException(429, "HTTP 429") }
        assertNotNull(other.loadNow(key("c")))
        other.loadNow(key("b"))
        assertEquals(0, callCount("b"))
    }

    @Test
    fun `auth errors pause only that repository`() {
        answers["a"] = { throw GiteaApiException(403, "HTTP 403") }
        val cache = cache()
        assertEquals(now + 10 * 60_000, cache.loadNow(key("a")))
        cache.loadNow(key("b"), key("c", repo = "other"))
        assertEquals(0, callCount("b"))
        assertEquals(1, callCount("c"))
    }

    @Test
    fun `concurrent loads of the same commit share one request`() = runBlocking {
        gate = CompletableDeferred()
        answers["a"] = { combined("success", "ci" to "success") }
        val cache = cache()
        val delivered = AtomicInteger()
        val first = async { cache.load("table 1", listOf(key("a")), fetch) { _, _ -> delivered.incrementAndGet() } }
        val second = async { cache.load("table 2", listOf(key("a")), fetch) { _, _ -> delivered.incrementAndGet() } }
        waitUntil { callCount("a") == 1 }
        gate!!.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, callCount("a"))
        assertEquals(2, delivered.get())
    }

    @Test
    fun `at most four requests run at a time`() = runBlocking {
        gate = CompletableDeferred()
        val cache = cache()
        val keys = (1..20).map { key("c$it") }
        val load = async { cache.load(this@GiteaCommitStatusCacheTest, keys, fetch) { _, _ -> } }
        waitUntil { running.get() == 4 }
        delay(50)
        assertEquals(4, totalCalls())
        gate!!.complete(Unit)
        load.await()
        assertEquals(20, totalCalls())
        assertEquals(4, maxRunning.get())
    }

    @Test
    fun `a newer load drops queued requests for rows that scrolled away`() = runBlocking {
        gate = CompletableDeferred()
        val cache = cache()
        val first = async { cache.load("table", (1..20).map { key("old$it") }, fetch) { _, _ -> } }
        waitUntil { running.get() == 4 }
        first.cancel()
        val second = async { cache.load("table", listOf(key("new1"), key("new2")), fetch) { _, _ -> } }
        delay(20)
        gate!!.complete(Unit)
        second.await()
        // The four already sent finish into the cache; the 16 queued ones are dropped.
        assertEquals(6, totalCalls())
        assertEquals(1, callCount("new1"))
        assertEquals(1, callCount("new2"))
    }

    @Test
    fun `the middle rows are fetched first`(): Unit = runBlocking {
        gate = CompletableDeferred()
        val cache = cache(GiteaCommitStatusCache.Policy(maxConcurrent = 1))
        val load = async { cache.load("table", (0..8).map { key("r$it") }, fetch) { _, _ -> } }
        waitUntil { running.get() == 1 }
        assertEquals(1, callCount("r4"))
        gate!!.complete(Unit)
        load.await()
    }

    @Test
    fun `a burst is capped by the token bucket`() = runBlocking {
        val cache = cache(GiteaCommitStatusCache.Policy(burstSize = 5, refillPerSecond = 0.001))
        val load = async { cache.load("table", (1..10).map { key("c$it") }, fetch) { _, _ -> } }
        waitUntil { totalCalls() == 5 }
        delay(100)
        assertEquals(5, totalCalls())
        load.cancel()
        cache.release("table")
    }

    @Test
    fun `the cache evicts the least recently used commits`() {
        answers["a"] = { combined("success", "ci" to "success") }
        answers["b"] = { combined("success", "ci" to "success") }
        answers["c"] = { combined("success", "ci" to "success") }
        val cache = cache(GiteaCommitStatusCache.Policy(maxEntries = 2))
        cache.loadNow(key("a"))
        cache.loadNow(key("b"))
        cache.cached(key("a"))
        cache.loadNow(key("c"))
        assertNotNull(cache.cached(key("a")))
        assertNull(cache.cached(key("b")))
        assertNotNull(cache.cached(key("c")))
    }
}
