package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.pullrequest.diff.CommentNavigation.Entry
import com.github.hypercookie.giteaplugin.pullrequest.diff.CommentNavigation.File
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewThread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommentNavigationTest {

    private fun thread(id: Long, path: String, line: Int, side: ReviewSide = ReviewSide.NEW, resolved: Boolean = false) =
        ReviewThread(
            path, side, line, "c1",
            listOf(PullReviewComment(id = id, body = "b$id", path = path, resolver = if (resolved) GiteaUser(1, "r") else null)),
        )

    // Chain order differs from path order on purpose; "c.txt" has no comments.
    private val files = listOf(
        File("src/b.txt", "src/b.txt", hasNew = true, hasOld = true),
        File("c.txt", "c.txt", hasNew = true, hasOld = true),
        File("a.txt", "a.txt", hasNew = true, hasOld = true),
    )
    private val threads = listOf(
        thread(1, "a.txt", 3),
        thread(2, "src/b.txt", 10),
        thread(3, "src/b.txt", 2),
        thread(4, "a.txt", 1, resolved = true),
        thread(5, "other.txt", 0),
    )

    private fun ids(entries: List<Entry>) = entries.map { it.threadId }

    @Test
    fun ordersByChainFileThenLine() {
        assertEquals(listOf(3L, 2L, 1L), ids(CommentNavigation.entries(files, threads, includeResolved = false)))
    }

    @Test
    fun includesResolvedOnRequest() {
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(CommentNavigation.entries(files, threads, includeResolved = true)))
    }

    @Test
    fun matchesOldSideByBasePathAndSkipsSidesNotShown() {
        val renamed = listOf(File("new.txt", "old.txt", hasNew = true, hasOld = true))
        val oldSide = thread(1, "old.txt", 4, side = ReviewSide.OLD)
        val newSide = thread(2, "new.txt", 4)
        assertEquals(listOf(2L, 1L), ids(CommentNavigation.entries(renamed, listOf(oldSide, newSide), false)))

        val added = listOf(File("new.txt", "old.txt", hasNew = true, hasOld = false))
        assertEquals(listOf(2L), ids(CommentNavigation.entries(added, listOf(oldSide, newSide), false)))
    }

    @Test
    fun nextStepsWithinFileThenCrossesToNextFileWithComments() {
        val entries = CommentNavigation.entries(files, threads, false)
        val first = CommentNavigation.next(entries, current = null, currentFile = 0)!!
        assertEquals(3L, first.threadId)
        val second = CommentNavigation.next(entries, first, currentFile = 0)!!
        assertEquals(2L, second.threadId)
        // Skips c.txt, which has no comments.
        val third = CommentNavigation.next(entries, second, currentFile = 0)!!
        assertEquals(1L, third.threadId)
        assertEquals(2, third.fileIndex)
    }

    @Test
    fun previousCrossesBackToLastCommentOfEarlierFile() {
        val entries = CommentNavigation.entries(files, threads, false)
        val inA = entries.single { it.threadId == 1L }
        assertEquals(2L, CommentNavigation.previous(entries, inA, currentFile = 2)!!.threadId)
    }

    @Test
    fun stopsAtBothEnds() {
        val entries = CommentNavigation.entries(files, threads, false)
        assertNull(CommentNavigation.next(entries, entries.last(), currentFile = 2))
        assertNull(CommentNavigation.previous(entries, entries.first(), currentFile = 0))
        assertNull(CommentNavigation.next(emptyList(), null, currentFile = 0))
    }

    @Test
    fun withoutCurrentCommentStartsFromTheShownFile() {
        val entries = CommentNavigation.entries(files, threads, false)
        // Showing c.txt (no comments): next is in a.txt, previous in b.txt.
        assertEquals(1L, CommentNavigation.next(entries, null, currentFile = 1)!!.threadId)
        assertEquals(2L, CommentNavigation.previous(entries, null, currentFile = 1)!!.threadId)
        // Showing b.txt: next and previous enter it at its first / last comment.
        assertEquals(3L, CommentNavigation.next(entries, null, currentFile = 0)!!.threadId)
        assertEquals(2L, CommentNavigation.previous(entries, null, currentFile = 0)!!.threadId)
    }

    @Test
    fun lastVisitedInAnotherFileIsIgnored() {
        val entries = CommentNavigation.entries(files, threads, false)
        // Visited thread 3 in b.txt, then switched to a.txt by hand.
        val visited = entries.single { it.threadId == 3L }
        assertEquals(1L, CommentNavigation.next(entries, visited, currentFile = 2)!!.threadId)
    }

    @Test
    fun continuesFromVisitedThreadThatIsNowFilteredOut() {
        val resolvedNow = threads.map { if (it.id == 3L) thread(3, "src/b.txt", 2, resolved = true) else it }
        val entries = CommentNavigation.entries(files, resolvedNow, includeResolved = false)
        val visited = CommentNavigation.locate(files, resolvedNow, 3L)!!
        assertEquals(2L, CommentNavigation.next(entries, visited, currentFile = 0)!!.threadId)
        assertNull(CommentNavigation.previous(entries, visited, currentFile = 0))
    }

    @Test
    fun ordersSameLineNewSideFirst() {
        val oneFile = listOf(File("a.txt", "a.txt", hasNew = true, hasOld = true))
        val sameLine = listOf(thread(1, "a.txt", 5, side = ReviewSide.OLD), thread(2, "a.txt", 5))
        assertEquals(listOf(2L, 1L), ids(CommentNavigation.entries(oneFile, sameLine, false)))
    }
}
