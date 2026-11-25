package com.github.hypercookie.giteaplugin.pullrequest

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaViewedFilesServiceTest {

    @Test
    fun marksAndUnmarksPerPullRequest() {
        val service = GiteaViewedFilesService()
        service.setViewed("a#1", listOf("x.kt", "y.kt"), true)
        assertTrue(service.isViewed("a#1", "x.kt"))
        assertFalse(service.isViewed("a#2", "x.kt"))
        service.setViewed("a#1", listOf("x.kt"), false)
        assertEquals(setOf("y.kt"), runBlocking { service.viewedPaths("a#1").first() })
    }

    @Test
    fun survivesSaveAndLoad() {
        val first = GiteaViewedFilesService()
        first.setViewed("a#1", listOf("x.kt"), true)
        first.setViewed("a#2", listOf("z.kt"), true)
        first.setViewed("a#2", listOf("z.kt"), false)
        val second = GiteaViewedFilesService()
        second.loadState(first.state)
        assertTrue(second.isViewed("a#1", "x.kt"))
        assertEquals(setOf("a#1"), second.state.viewed.keys)
    }
}
