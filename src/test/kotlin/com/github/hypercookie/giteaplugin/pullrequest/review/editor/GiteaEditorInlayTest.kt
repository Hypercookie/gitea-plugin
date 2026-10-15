package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaEditorInlay.Companion.collapsedAfter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaEditorInlayTest {

    @Test
    fun becomingOutdatedCollapses() {
        assertTrue(collapsedAfter(wasOutdated = false, outdated = true, collapsed = false, keepExpanded = false))
    }

    @Test
    fun becomingOutdatedWhileReplyingStaysExpanded() {
        assertFalse(collapsedAfter(wasOutdated = false, outdated = true, collapsed = false, keepExpanded = true))
    }

    @Test
    fun stayingOutdatedKeepsTheUsersChoice() {
        assertFalse(collapsedAfter(wasOutdated = true, outdated = true, collapsed = false, keepExpanded = false))
        assertTrue(collapsedAfter(wasOutdated = true, outdated = true, collapsed = true, keepExpanded = false))
    }

    @Test
    fun becomingCurrentExpands() {
        assertFalse(collapsedAfter(wasOutdated = true, outdated = false, collapsed = true, keepExpanded = false))
        assertFalse(collapsedAfter(wasOutdated = false, outdated = false, collapsed = false, keepExpanded = false))
    }
}
