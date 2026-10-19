package com.github.hypercookie.giteaplugin.api

import junit.framework.TestCase

class GiteaServerVersionTest : TestCase() {

    fun testParsesReleasesPreReleasesAndDevBuilds() {
        assertEquals(GiteaServerVersion.of(1, 27, 3), GiteaServerVersion.parse("1.27.3"))
        assertEquals(GiteaServerVersion.of(28, 1, 0), GiteaServerVersion.parse("28.1.0"))
        assertEquals(GiteaServerVersion.of(1, 27, 0), GiteaServerVersion.parse("1.27.0-rc0"))
        assertEquals(GiteaServerVersion.of(1, 28, 0), GiteaServerVersion.parse("1.28.0+dev-12-gabc123"))
        assertNull(GiteaServerVersion.parse("development"))
    }

    fun testComparesNumerically() {
        assertTrue(GiteaServerVersion.parse("28.0.0")!! > GiteaServerVersion.parse("1.27.3")!!)
        assertTrue(GiteaServerVersion.parse("1.27.10")!! > GiteaServerVersion.parse("1.27.9")!!)
        assertEquals(0, GiteaServerVersion.of(1, 26).compareTo(GiteaServerVersion.of(1, 26, 0)))
    }

    // Checked against 1.24.7, 1.25.5, 1.26.4, 1.27.3 and 28.1.0.
    fun testFeaturesFollowTheVersionsThatHaveThem() {
        fun features(version: String) = GiteaServerFeatures(GiteaServerVersion.parse(version))
        assertFalse(features("1.25.5").resolveComments)
        assertTrue(features("1.26.4").resolveComments)
        assertFalse(features("1.26.4").replyToComments)
        assertTrue(features("1.27.3").replyToComments)
        assertTrue(features("28.1.0").replyToComments)
        assertTrue(GiteaServerFeatures.UNKNOWN.resolveComments)
        assertTrue(GiteaServerFeatures.UNKNOWN.replyToComments)
    }
}
