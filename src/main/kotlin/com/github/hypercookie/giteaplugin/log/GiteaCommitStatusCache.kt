package com.github.hypercookie.giteaplugin.log

import com.github.hypercookie.giteaplugin.api.GiteaApiException
import com.github.hypercookie.giteaplugin.api.dto.CombinedCommitStatus
import com.github.hypercookie.giteaplugin.api.dto.CommitStatus
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.min

/** One commit on one Gitea repository. [serverUrl] has no trailing slash. */
data class GiteaCommitStatusKey(val serverUrl: String, val owner: String, val repo: String, val sha: String)

/** Overall check state of a commit, as shown in the log column. */
enum class GiteaCheckState { SUCCESS, WARNING, SKIPPED, FAILURE, PENDING, NONE, NOT_ON_SERVER }

/**
 * The checks of one commit. [running] is true while any single context is
 * still pending; Gitea reports the combined state `failure` as soon as one
 * context fails, even if others are still running (checked on 1.27.3).
 */
data class GiteaCommitChecks(val state: GiteaCheckState, val statuses: List<CommitStatus>, val running: Boolean) {

    /** No context can change any more. Treated as immutable for the session. */
    val isFinal: Boolean get() = statuses.isNotEmpty() && !running

    companion object {
        val NONE = GiteaCommitChecks(GiteaCheckState.NONE, emptyList(), false)
        val NOT_ON_SERVER = GiteaCommitChecks(GiteaCheckState.NOT_ON_SERVER, emptyList(), false)

        /**
         * Gitea answers a commit without statuses with `state: "pending"`,
         * `total_count: 0` and `statuses: null`, so "no checks" is read from
         * the status list, never from the combined state.
         */
        fun of(combined: CombinedCommitStatus): GiteaCommitChecks {
            val statuses = combined.statuses.orEmpty()
            if (statuses.isEmpty()) return NONE
            val running = statuses.any { it.status == "pending" }
            val state = when (combined.state) {
                "success" -> GiteaCheckState.SUCCESS
                "warning" -> GiteaCheckState.WARNING
                "skipped" -> GiteaCheckState.SKIPPED
                "failure", "error" -> GiteaCheckState.FAILURE
                else -> GiteaCheckState.PENDING
            }
            // An unknown combined state is kept on the pending TTL rather than
            // cached forever.
            return GiteaCommitChecks(state, statuses, running || state == GiteaCheckState.PENDING)
        }
    }
}

/**
 * Session cache and request gate for commit statuses shown in the Git log.
 * Gitea has no batch endpoint, so every commit costs one
 * `GET /repos/{o}/{r}/commits/{sha}/status`; this class keeps that count low:
 *
 * - Commits whose checks all finished are cached for the session and never
 *   fetched again (bounded by an LRU of [Policy.maxEntries]).
 * - Running checks are refetched after [Policy.pendingTtlMs].
 * - Commits with no checks, and commits the server doesn't know (404, i.e.
 *   not pushed yet), are refetched after a TTL that starts at
 *   [Policy.noneTtlMs] and doubles each time the answer stays the same, up to
 *   [Policy.noneMaxTtlMs] / [Policy.notFoundMaxTtlMs]. The commit's age isn't
 *   known here (reading it would need internal log API or a git call), so the
 *   doubling is what makes old commits without CI cheap.
 * - Requests for the same commit share one call; at most
 *   [Policy.maxConcurrent] run at a time, and a token bucket
 *   ([Policy.burstSize], [Policy.refillPerSecond]) caps how many start in a
 *   burst. A newer [load] from the same requester drops its queued requests
 *   for commits that are no longer asked for.
 * - 429, 5xx and network errors pause the whole server with an exponential
 *   backoff ([Policy.backoffInitialMs] up to [Policy.backoffMaxMs]); other
 *   errors (401, 403, ...) pause that repository for [Policy.repoPauseMs].
 *   Failures are never cached as "no checks".
 *
 * [requestCount] counts the requests made; each one is also logged at debug
 * level under `#com.github.hypercookie.giteaplugin.log`.
 */
class GiteaCommitStatusCache(
    private val scope: CoroutineScope,
    private val policy: Policy = Policy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Policy(
        val pendingTtlMs: Long = 60_000,
        val noneTtlMs: Long = 2 * 60_000,
        val noneMaxTtlMs: Long = 30 * 60_000,
        val notFoundMaxTtlMs: Long = 10 * 60_000,
        val maxEntries: Int = 5_000,
        val maxConcurrent: Int = 4,
        val burstSize: Int = 100,
        val refillPerSecond: Double = 1.0,
        val backoffInitialMs: Long = 30_000,
        val backoffMaxMs: Long = 10 * 60_000,
        val repoPauseMs: Long = 10 * 60_000,
    )

    private class Entry(val checks: GiteaCommitChecks, val expiresAt: Long, val streak: Int)

    private sealed interface Outcome {
        data class Loaded(val checks: GiteaCommitChecks) : Outcome
        /** Not fetched (paused) or failed; nothing was cached. */
        data object Skipped : Outcome
    }

    private class InFlight(val requesters: MutableSet<Any>) {
        lateinit var deferred: Deferred<Outcome>
        @Volatile var started = false
    }

    private class Backoff(val until: Long, val delayMs: Long)

    private val lock = Any()
    private val entries = object : LinkedHashMap<GiteaCommitStatusKey, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GiteaCommitStatusKey, Entry>?) = size > policy.maxEntries
    }
    private val inFlight = HashMap<GiteaCommitStatusKey, InFlight>()
    private val serverBackoff = HashMap<String, Backoff>()
    private val repoPause = HashMap<Triple<String, String, String>, Long>()
    private val semaphore = Semaphore(policy.maxConcurrent)
    private var tokens = policy.burstSize.toDouble()
    private var tokensAt = clock()
    private val requests = AtomicInteger()

    /** Requests sent to the server so far. */
    val requestCount: Int get() = requests.get()

    /** The last known checks of [key], even if they're due for a refetch. */
    fun cached(key: GiteaCommitStatusKey): GiteaCommitChecks? = synchronized(lock) { entries[key]?.checks }

    /**
     * Fetches the commits of [keys] that have no fresh cache entry, calling
     * [onLoaded] for each one as it arrives, and returns when all are done.
     * Keys nearest the middle of [keys] go first: the platform asks for the
     * visible rows plus a margin on both sides. Replaces [requester]'s
     * previous load; its queued requests for keys not in [keys] are dropped.
     *
     * Returns when [keys] should be loaded again (a running check expires or a
     * pause ends), or null if nothing in [keys] needs a refresh.
     */
    suspend fun load(
        requester: Any,
        keys: List<GiteaCommitStatusKey>,
        fetch: suspend (GiteaCommitStatusKey) -> CombinedCommitStatus,
        onLoaded: (GiteaCommitStatusKey, GiteaCommitChecks) -> Unit,
    ): Long? {
        val center = (keys.size - 1) / 2.0
        val ordered = keys.withIndex().sortedBy { abs(it.index - center) }.map { it.value }.distinct()
        val now = clock()
        val wanted = ordered.toSet()
        val requested = ArrayList<Pair<GiteaCommitStatusKey, Deferred<Outcome>>>()
        synchronized(lock) {
            dropRequests(requester) { it !in wanted }
            for (key in ordered) {
                val entry = entries[key]
                if (entry != null && entry.expiresAt > now) continue
                if (pausedUntil(key, now) != null) continue
                val request = inFlight.getOrPut(key) { newRequest(key, fetch) }
                request.requesters += requester
                requested += key to request.deferred
            }
        }
        requested.forEach { it.second.start() }
        coroutineScope {
            for ((key, deferred) in requested) {
                launch {
                    val outcome = try {
                        deferred.await()
                    } catch (e: CancellationException) {
                        ensureActive()
                        return@launch
                    }
                    if (outcome is Outcome.Loaded) onLoaded(key, outcome.checks)
                }
            }
        }
        return nextRefresh(ordered)
    }

    /** Drops [requester]'s interest in everything, e.g. when its log table closes. */
    fun release(requester: Any) {
        synchronized(lock) { dropRequests(requester) { true } }
    }

    private fun nextRefresh(keys: List<GiteaCommitStatusKey>): Long? = synchronized(lock) {
        val now = clock()
        keys.mapNotNull { key ->
            val paused = pausedUntil(key, now)
            val entry = entries[key]
            when {
                paused != null -> paused
                entry == null -> null
                entry.checks.running -> entry.expiresAt
                else -> null
            }
        }.minOrNull()
    }

    /** Under [lock]. */
    private fun dropRequests(requester: Any, drop: (GiteaCommitStatusKey) -> Boolean) {
        val iterator = inFlight.entries.iterator()
        while (iterator.hasNext()) {
            val (key, request) = iterator.next()
            if (!drop(key) || !request.requesters.remove(requester)) continue
            if (request.requesters.isEmpty() && !request.started) {
                iterator.remove()
                request.deferred.cancel()
            }
        }
    }

    /** Under [lock]. */
    private fun pausedUntil(key: GiteaCommitStatusKey, now: Long): Long? {
        val server = serverBackoff[key.serverUrl]?.until?.takeIf { it > now }
        val repo = repoPause[Triple(key.serverUrl, key.owner, key.repo)]?.takeIf { it > now }
        return listOfNotNull(server, repo).maxOrNull()
    }

    /** Under [lock]. */
    private fun newRequest(key: GiteaCommitStatusKey, fetch: suspend (GiteaCommitStatusKey) -> CombinedCommitStatus): InFlight {
        val request = InFlight(HashSet())
        request.deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                semaphore.withPermit {
                    awaitToken()
                    synchronized(lock) {
                        if (pausedUntil(key, clock()) != null) return@withPermit Outcome.Skipped
                        request.started = true
                    }
                    fetchAndStore(key, fetch)
                }
            } finally {
                synchronized(lock) { inFlight.remove(key, request) }
            }
        }
        return request
    }

    private suspend fun awaitToken() {
        while (true) {
            val waitMs = synchronized(lock) {
                val now = clock()
                tokens = min(policy.burstSize.toDouble(), tokens + (now - tokensAt) * policy.refillPerSecond / 1000.0)
                tokensAt = now
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return
                }
                ((1.0 - tokens) * 1000.0 / policy.refillPerSecond).toLong().coerceAtLeast(1)
            }
            delay(waitMs)
        }
    }

    private suspend fun fetchAndStore(
        key: GiteaCommitStatusKey,
        fetch: suspend (GiteaCommitStatusKey) -> CombinedCommitStatus,
    ): Outcome {
        val count = requests.incrementAndGet()
        val checks = try {
            GiteaCommitChecks.of(fetch(key))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GiteaApiException) {
            if (e.statusCode == 404) {
                GiteaCommitChecks.NOT_ON_SERVER
            } else {
                onError(key, e.statusCode)
                LOG.debug("Commit status request #$count ${key.owner}/${key.repo}@${key.sha}: ${e.message}")
                return Outcome.Skipped
            }
        } catch (e: IOException) {
            onError(key, null)
            LOG.debug("Commit status request #$count ${key.owner}/${key.repo}@${key.sha}: $e")
            return Outcome.Skipped
        } catch (e: Exception) {
            onError(key, 0)
            LOG.debug("Commit status request #$count ${key.owner}/${key.repo}@${key.sha}: $e")
            return Outcome.Skipped
        }
        LOG.debug("Commit status request #$count ${key.owner}/${key.repo}@${key.sha}: ${checks.state}")
        synchronized(lock) {
            serverBackoff.remove(key.serverUrl)
            val previous = entries[key]
            val streak = if (previous != null && previous.checks.state == checks.state) previous.streak + 1 else 1
            entries[key] = Entry(checks, clock() + ttl(checks, streak), streak)
        }
        return Outcome.Loaded(checks)
    }

    private fun ttl(checks: GiteaCommitChecks, streak: Int): Long = when {
        checks.isFinal -> Long.MAX_VALUE / 2
        checks.running -> policy.pendingTtlMs
        else -> {
            val max = if (checks.state == GiteaCheckState.NOT_ON_SERVER) policy.notFoundMaxTtlMs else policy.noneMaxTtlMs
            val doubled = policy.noneTtlMs shl (streak - 1).coerceIn(0, 20)
            min(doubled, max)
        }
    }

    /** [statusCode] null for network errors. */
    private fun onError(key: GiteaCommitStatusKey, statusCode: Int?) {
        val serverWide = statusCode == null || statusCode == 429 || statusCode >= 500
        synchronized(lock) {
            val now = clock()
            if (serverWide) {
                val previous = serverBackoff[key.serverUrl]
                val delayMs = if (previous == null) policy.backoffInitialMs else min(previous.delayMs * 2, policy.backoffMaxMs)
                // Requests already running when the first error arrives must
                // not double the delay again.
                if (previous == null || previous.until <= now) serverBackoff[key.serverUrl] = Backoff(now + delayMs, delayMs)
            } else {
                repoPause[Triple(key.serverUrl, key.owner, key.repo)] = now + policy.repoPauseMs
            }
        }
    }

    private companion object {
        val LOG = logger<GiteaCommitStatusCache>()
    }
}
