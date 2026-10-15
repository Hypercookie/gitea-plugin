package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.dto.PrBranchInfo
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaBranchPrMatchingTest {

    private fun pr(number: Long, headRef: String, headRepo: Long? = 1, headSha: String = "h$number", updated: String = "2026-01-01") =
        PullRequest(
            id = number, number = number, title = "PR $number", state = "open",
            base = PrBranchInfo(ref = "main", sha = "base", repoId = 1),
            head = PrBranchInfo(ref = headRef, sha = headSha, repoId = headRepo),
            updatedAt = updated,
        )

    private val origin = { name: String -> GiteaTrackedBranch(name, isBaseRepoRemote = true) }

    @Test
    fun trackedBranchMatchesItsUpstreamName() {
        val prs = listOf(pr(7, "alice/greeter-feature"), pr(8, "other"))
        assertEquals(7L, GiteaBranchPrMatcher.match("alice/greeter-feature", origin("alice/greeter-feature"), prs)?.number)
    }

    @Test
    fun trackedBranchWithAnotherLocalNameMatchesThroughUpstream() {
        val prs = listOf(pr(7, "alice/greeter-feature"))
        assertEquals(7L, GiteaBranchPrMatcher.match("greeter", origin("alice/greeter-feature"), prs)?.number)
    }

    @Test
    fun sameNameButTrackingAnotherRemoteBranchDoesNotMatch() {
        val prs = listOf(pr(7, "feature"))
        assertNull(GiteaBranchPrMatcher.match("feature", origin("someone-else/feature"), prs))
    }

    @Test
    fun branchTrackingANonGiteaRemoteDoesNotMatch() {
        val prs = listOf(pr(7, "feature"))
        assertNull(GiteaBranchPrMatcher.match("feature", GiteaTrackedBranch("feature", isBaseRepoRemote = false), prs))
    }

    @Test
    fun forkPullRequestIsNeverMatched() {
        val prs = listOf(pr(9, "main", headRepo = 42))
        assertNull(GiteaBranchPrMatcher.match("main", origin("main"), prs))
        assertNull(GiteaBranchPrMatcher.match("main", null, prs))
    }

    @Test
    fun unknownRepoIdsCountAsSameRepository() {
        assertEquals(7L, GiteaBranchPrMatcher.match("feature", null, listOf(pr(7, "feature", headRepo = null)))?.number)
    }

    @Test
    fun untrackedBranchFallsBackToItsName() {
        val prs = listOf(pr(7, "feature"), pr(8, "other"))
        assertEquals(7L, GiteaBranchPrMatcher.match("feature", null, prs)?.number)
        assertNull(GiteaBranchPrMatcher.match("unrelated", null, prs))
    }

    @Test
    fun mostRecentlyUpdatedWinsForSharedHead() {
        val prs = listOf(pr(7, "feature", updated = "2026-01-01"), pr(8, "feature", updated = "2026-02-01"))
        assertEquals(8L, GiteaBranchPrMatcher.match("feature", origin("feature"), prs)?.number)
    }

    // Banner

    private fun sync(
        localHead: String? = "local",
        prHead: String = "h7",
        ahead: Int? = 0,
        behind: Int? = 0,
        branch: String = "alice/greeter-feature",
    ) = GiteaBranchSync("/repo", branch, 7, localHead, prHead, ahead, behind)

    private val pr7 = pr(7, "alice/greeter-feature")

    @Test
    fun sessionIsCurrentOnlyOnItsOwnBranch() {
        assertTrue(GiteaOutOfSyncBanner.isSessionCurrent("feature", "feature"))
        assertFalse(GiteaOutOfSyncBanner.isSessionCurrent("feature", "main"))
        assertFalse(GiteaOutOfSyncBanner.isSessionCurrent("feature", null)) // detached HEAD
    }

    @Test
    fun noBannerWhenAtPullRequestHead() {
        assertNull(GiteaOutOfSyncBanner.message(sync(localHead = "h7"), "alice/greeter-feature", "h7", pr7))
    }

    @Test
    fun noBannerWhenAnotherBranchIsCheckedOut() {
        // The reported bug: the session still names PR 7, but main is checked out.
        assertNull(GiteaOutOfSyncBanner.message(sync(ahead = 1), "main", "local", pr7))
    }

    @Test
    fun noBannerForAnotherPullRequestsSync() {
        assertNull(GiteaOutOfSyncBanner.message(sync(ahead = 1), "alice/greeter-feature", "local", pr(8, "alice/greeter-feature")))
    }

    @Test
    fun aheadNamesLocalBranchAndCount() {
        assertEquals(
            "Local branch alice/greeter-feature is 2 commits ahead of pull request #7: push to update it",
            GiteaOutOfSyncBanner.message(sync(ahead = 2), "alice/greeter-feature", "local", pr7),
        )
    }

    @Test
    fun behindNamesLocalBranchAndCount() {
        assertEquals(
            "Local branch alice/greeter-feature is 1 commit behind pull request #7: pull to update it",
            GiteaOutOfSyncBanner.message(sync(behind = 1), "alice/greeter-feature", "local", pr7),
        )
    }

    @Test
    fun diverged() {
        assertEquals(
            "Local branch alice/greeter-feature has diverged from pull request #7 (1 commit local, 3 commits remote)",
            GiteaOutOfSyncBanner.message(sync(ahead = 1, behind = 3), "alice/greeter-feature", "local", pr7),
        )
    }

    @Test
    fun headNotFetchedYet() {
        assertEquals(
            "Pull request #7 has commits not yet fetched into local branch alice/greeter-feature",
            GiteaOutOfSyncBanner.message(sync(ahead = null, behind = null), "alice/greeter-feature", "local", pr7),
        )
    }

    @Test
    fun differentLocalNameShowsPullRequestHead() {
        assertEquals(
            "Local branch greeter is 1 commit behind pull request #7 (alice/greeter-feature): pull to update it",
            GiteaOutOfSyncBanner.message(sync(behind = 1, branch = "greeter"), "greeter", "local", pr7),
        )
    }

    @Test
    fun countsForOtherCommitsFallBackToGenericText() {
        // A commit landed after the counts were computed; the next refresh updates them.
        assertEquals(
            "Local branch alice/greeter-feature is out of sync with pull request #7",
            GiteaOutOfSyncBanner.message(sync(ahead = 1), "alice/greeter-feature", "newer", pr7),
        )
    }
}
