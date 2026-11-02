package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewThreadsTest {

    private fun comment(id: Long, pos: Long?, orig: Long?, commit: String = "c1", created: String = "2026-01-0${id}T00:00:00Z", path: String = "a.txt", review: Long? = null) =
        PullReviewComment(id = id, reviewId = review, body = "b$id", path = path, position = pos, originalPosition = orig, commitId = commit, createdAt = created)

    @Test
    fun groupsRepliesOnSameLineSideAndCommit() {
        val threads = ReviewThreads.group(listOf(comment(2, 5, 0), comment(1, 5, 0), comment(3, 5, 0, commit = "c2")))
        assertEquals(2, threads.size)
        assertEquals(listOf(1L, 2L), threads.first { it.comments.size == 2 }.comments.map { it.id })
        assertEquals(4, threads.first().line)
    }

    @Test
    fun separatesNewAndOldSide() {
        // Gitea data: comment 15 on pr-test#2 has position 0, original_position 15.
        val threads = ReviewThreads.group(listOf(comment(1, 15, 0), comment(2, 0, 15)))
        assertEquals(setOf(ReviewSide.NEW, ReviewSide.OLD), threads.map { it.side }.toSet())
        threads.forEach { assertEquals(14, it.line) }
    }

    @Test
    fun anchorsThreadToItsFirstReviewsCommitNotTheBlameCommit() {
        // Gitea's comment commit_id is a blame of the line; the reply's review is newer.
        val comments = listOf(comment(1, 5, 0, commit = "blame", review = 10), comment(2, 5, 0, commit = "blame", review = 11))
        val thread = ReviewThreads.group(comments, mapOf(10L to "head1", 11L to "head2")).single()
        assertEquals("head1", thread.commitId)
        assertEquals(null, ReviewThreads.group(comments).single().commitId)
    }

    @Test
    fun dropsCommentsWithoutLine() {
        assertEquals(0, ReviewThreads.group(listOf(comment(1, 0, 0), comment(2, null, null))).size)
    }
}
