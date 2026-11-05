package com.github.hypercookie.giteaplugin.api

import org.junit.Assert.assertEquals
import org.junit.Test

/** Requests within the burst go at once; the rest wait their turn, in the
 * order they came, at the limiter's rate. */
class GiteaRequestLimiterTest {

    private var now = 0L
    private val limiter = GiteaRequestLimiter(burst = 3, perSecond = 2.0, nanoTime = { now })

    @Test
    fun `burst goes without waiting`() {
        repeat(3) { assertEquals(0L, limiter.reserve()) }
    }

    @Test
    fun `requests over the burst queue behind each other`() {
        repeat(3) { limiter.reserve() }
        assertEquals(500_000_000L, limiter.reserve())
        assertEquals(1_000_000_000L, limiter.reserve())
    }

    @Test
    fun `tokens come back over time`() {
        repeat(3) { limiter.reserve() }
        now += 1_000_000_000L
        assertEquals(0L, limiter.reserve())
        assertEquals(0L, limiter.reserve())
        assertEquals(500_000_000L, limiter.reserve())
    }

    @Test
    fun `an idle limiter refills only up to the burst`() {
        now += 60_000_000_000L
        repeat(3) { assertEquals(0L, limiter.reserve()) }
        assertEquals(500_000_000L, limiter.reserve())
    }
}
