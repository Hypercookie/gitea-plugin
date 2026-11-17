package com.github.hypercookie.giteaplugin.api

import kotlinx.coroutines.delay

/**
 * Token bucket for the requests sent to one Gitea server: up to [burst]
 * requests go at once, after that [perSecond] a second. A request over the
 * limit waits for its turn instead of failing, in the order they came.
 *
 * It is the safety net under the plugin's own care (cached reads, conflated
 * reloads, Refresh's cooldown): whatever asks too often, the server sees at
 * most this rate.
 */
internal class GiteaRequestLimiter(
    private val burst: Int = DEFAULT_BURST,
    private val perSecond: Double = DEFAULT_PER_SECOND,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens = burst.toDouble()
    private var updatedAt = nanoTime()

    /** Suspends until the next request may be sent. */
    suspend fun acquire() {
        val waitNanos = reserve()
        if (waitNanos > 0) delay((waitNanos + 999_999) / 1_000_000)
    }

    /** Takes a token and returns how long to wait for it, in nanoseconds.
     * Tokens below zero are requests already waiting, so each new one
     * waits behind them. */
    @Synchronized
    internal fun reserve(): Long {
        val now = nanoTime()
        tokens = minOf(burst.toDouble(), tokens + (now - updatedAt) * perSecond / 1e9)
        updatedAt = now
        tokens -= 1
        return if (tokens >= 0) 0 else (-tokens / perSecond * 1e9).toLong()
    }

    companion object {
        /** Opening a pull request sends a few dozen requests at once
         * (reviews, their comments, reactions, statuses). */
        const val DEFAULT_BURST = 30
        const val DEFAULT_PER_SECOND = 5.0
    }
}
