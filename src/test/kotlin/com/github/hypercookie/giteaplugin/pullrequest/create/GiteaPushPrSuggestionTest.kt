package com.github.hypercookie.giteaplugin.pullrequest.create

import com.github.hypercookie.giteaplugin.api.dto.PrBranchInfo
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaPushPrSuggestionTest {

    private fun pr(number: Long, headRef: String, headRepo: Long? = 1) =
        PullRequest(
            id = number, number = number, title = "PR $number", state = "open",
            base = PrBranchInfo(ref = "main", sha = "base", repoId = 1),
            head = PrBranchInfo(ref = headRef, sha = "h$number", repoId = headRepo),
        )

    @Test
    fun pushedBranchStripsRefPrefixes() {
        assertEquals(
            GiteaPushedBranch("feature", "alice/feature"),
            GiteaPushPrSuggestion.pushedBranch("refs/heads/feature", "refs/remotes/origin/alice/feature", "origin"),
        )
    }

    @Test
    fun pushedBranchAcceptsShortRemoteNames() {
        assertEquals(
            GiteaPushedBranch("a/b", "a/b"),
            GiteaPushPrSuggestion.pushedBranch("refs/heads/a/b", "origin/a/b", "origin"),
        )
    }

    @Test
    fun tagsAndDetachedHeadsAreNotBranches() {
        assertNull(GiteaPushPrSuggestion.pushedBranch("refs/tags/v1", "refs/remotes/origin/v1", "origin"))
        assertNull(GiteaPushPrSuggestion.pushedBranch("0123abcd", "refs/remotes/origin/feature", "origin"))
    }

    @Test
    fun suggestsForBranchWithoutPullRequest() {
        assertTrue(GiteaPushPrSuggestion.shouldSuggest(GiteaPushedBranch("feature", "feature"), "main", listOf(pr(1, "other"))))
    }

    @Test
    fun noSuggestionWhenPullRequestIsOpen() {
        assertFalse(GiteaPushPrSuggestion.shouldSuggest(GiteaPushedBranch("local", "feature"), "main", listOf(pr(1, "feature"))))
    }

    @Test
    fun forkPullRequestWithSameHeadNameDoesNotCount() {
        assertTrue(GiteaPushPrSuggestion.shouldSuggest(GiteaPushedBranch("feature", "feature"), "main", listOf(pr(1, "feature", headRepo = 2))))
    }

    @Test
    fun noSuggestionForDefaultOrUnknownDefaultBranch() {
        assertFalse(GiteaPushPrSuggestion.shouldSuggest(GiteaPushedBranch("main", "main"), "main", emptyList()))
        assertFalse(GiteaPushPrSuggestion.shouldSuggest(GiteaPushedBranch("feature", "feature"), null, emptyList()))
    }

    @Test
    fun compareUrlKeepsBranchSlashesAndEncodesTheRest() {
        assertEquals(
            "https://gitea.example.com/sub/my%20org/repo/compare/main...alice/fix%23123",
            GiteaPushPrSuggestion.compareUrl("https://gitea.example.com/sub/", "my org", "repo", "main", "alice/fix#123"),
        )
    }
}
