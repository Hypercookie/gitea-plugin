package com.github.hypercookie.giteaplugin.api

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [GiteaApiClient] against the real local dev Gitea instance
 * (see docs/local-gitea-dev-instance.md) rather than mocks — the point of
 * Phase 2 is confirming our DTOs actually decode Gitea's real responses.
 * Requires `docker compose up` in ~/dev-infra/gitea-plugin-dev to be running,
 * and the `plugindev/pr-test` repo + PR #1 set up as documented in
 * docs/plan/pr-review-feature-plan.md Phase 2 notes. Not wired into CI (no
 * Gitea instance there) — run manually during plugin development.
 */
class GiteaApiClientIntegrationTest {

    private val client = GiteaApiClient("http://localhost:3000", TOKEN)

    @Test
    fun `lists open pull requests`() = runBlocking {
        val prs = client.listPullRequests("plugindev", "pr-test")
        assertTrue(prs.isNotEmpty())
        val pr = prs.first { it.number == 1L }
        assertEquals("Test PR for plugin dev", pr.title)
        assertEquals("open", pr.state)
        assertEquals("feature", pr.head.ref)
        assertEquals("main", pr.base.ref)
    }

    @Test
    fun `gets a single pull request`() = runBlocking {
        val pr = client.getPullRequest("plugindev", "pr-test", 1)
        assertEquals(1L, pr.number)
        assertEquals("plugindev", pr.user?.login)
    }

    @Test
    fun `lists changed files`() = runBlocking {
        val files = client.getPullRequestFiles("plugindev", "pr-test", 1)
        assertTrue(files.any { it.filename == "README.md" })
    }

    // Fixed comment left by the repo setup (docs/local-gitea-dev-instance.md
    // "Test fixture" section), not by any test in this class — used as a
    // stable anchor since PR #1 accumulates more reviews/comments across
    // repeated test runs and `.first()`/index-based lookups would be
    // order-dependent and flaky.
    private val fixtureCommentBody = "nit: consider rewording this line"

    @Test
    fun `lists reviews and their inline comments`() = runBlocking {
        val reviews = client.listReviews("plugindev", "pr-test", 1)
        assertTrue(reviews.any { it.state == ReviewEvent.COMMENT })

        val comment = reviews
            .flatMap { client.listReviewComments("plugindev", "pr-test", 1, it.id) }
            .first { it.body == fixtureCommentBody }
        assertEquals("README.md", comment.path)
    }

    @Test
    fun `replies to an inline review comment`() = runBlocking {
        val reviews = client.listReviews("plugindev", "pr-test", 1)
        val comment = reviews
            .flatMap { client.listReviewComments("plugindev", "pr-test", 1, it.id) }
            .first { it.body == fixtureCommentBody }

        val reply = client.replyToComment("plugindev", "pr-test", 1, comment.id, "Sounds good, will fix.")
        assertEquals("Sounds good, will fix.", reply.body)
        assertEquals("README.md", reply.path)
    }

    @Test
    fun `posts a top-level PR comment`() = runBlocking {
        val comment = client.createIssueComment("plugindev", "pr-test", 1, "Top-level comment from integration test.")
        assertEquals("Top-level comment from integration test.", comment.body)
    }

    @Test
    fun `submits a review with a comment`() = runBlocking {
        // Not APPROVED/REQUEST_CHANGES: Gitea forbids self-review on your own
        // PR for those states ("approve your own pull is not allowed"),
        // which this account is, since it authored pr-test#1. COMMENT is
        // unaffected and already exercised via curl during the Phase 2 spike.
        //
        // commitId is set to the PR's current head SHA — the pattern Phase 9
        // production code should follow, so a review doesn't show as `stale`
        // the moment the PR gets a new commit (see CreatePullReviewOptions doc).
        val pr = client.getPullRequest("plugindev", "pr-test", 1)
        val review = client.createReview(
            "plugindev", "pr-test", 1,
            CreatePullReviewOptions(
                event = ReviewEvent.COMMENT,
                body = "Another comment from integration test",
                commitId = pr.head.sha,
            ),
        )
        assertEquals(ReviewEvent.COMMENT, review.state)
        assertEquals(pr.head.sha, review.commitId)
    }

    companion object {
        // Read from env rather than hardcoded, even though this is a
        // throwaway 127.0.0.1-only dev instance — keeps no token literal in
        // git history. Generate one per docs/local-gitea-dev-instance.md and
        // run with GITEA_DEV_TOKEN=<token> ./gradlew test --tests '*GiteaApiClientIntegrationTest'
        private val TOKEN = System.getenv("GITEA_DEV_TOKEN") ?: "unset"
    }
}
