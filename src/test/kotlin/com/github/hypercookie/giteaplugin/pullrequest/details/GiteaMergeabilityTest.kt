package com.github.hypercookie.giteaplugin.pullrequest.details

import org.junit.Assert.assertEquals
import org.junit.Test

class GiteaMergeabilityTest {

    @Test
    fun openAndMergeableIsOk() {
        assertEquals(GiteaMergeability.OK, GiteaMergeability.of("open", merged = false, draft = false, mergeable = true))
    }

    @Test
    fun openAndNotMergeableNeedsRecheck() {
        assertEquals(GiteaMergeability.CHECKING, GiteaMergeability.of("open", merged = false, draft = false, mergeable = false))
    }

    @Test
    fun draftIsNeverFlagged() {
        // Gitea reports work-in-progress pull requests as not mergeable.
        assertEquals(GiteaMergeability.OK, GiteaMergeability.of("open", merged = false, draft = true, mergeable = false))
    }

    @Test
    fun closedOrMergedIsNeverFlagged() {
        assertEquals(GiteaMergeability.OK, GiteaMergeability.of("closed", merged = false, draft = false, mergeable = false))
        assertEquals(GiteaMergeability.OK, GiteaMergeability.of("closed", merged = true, draft = false, mergeable = false))
    }

    @Test
    fun unknownIsOk() {
        assertEquals(GiteaMergeability.OK, GiteaMergeability.of("open", merged = false, draft = false, mergeable = null))
    }
}
