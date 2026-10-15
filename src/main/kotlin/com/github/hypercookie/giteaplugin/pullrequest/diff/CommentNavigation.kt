package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewThread

/**
 * Order of review threads for "Previous / Next Comment" in a pull request
 * diff chain: files in chain order, then threads by line within a file.
 * Pure logic; [GiteaDiffCommentNavigation] applies it to the open diff.
 */
object CommentNavigation {

    /**
     * One file of the diff chain. A thread belongs to it when it is on the
     * new side of [path] or the old side of [basePath] (same rule as the
     * diff's editor models), and that side is shown ([hasNew] / [hasOld]:
     * an added or deleted file shows one side only).
     */
    data class File(val path: String, val basePath: String, val hasNew: Boolean, val hasOld: Boolean) {
        fun contains(thread: ReviewThread): Boolean = when (thread.side) {
            ReviewSide.NEW -> hasNew && thread.path == path
            ReviewSide.OLD -> hasOld && thread.path == basePath
        }
    }

    /** A thread at its place in the order. [line] is the thread's own line
     * (in the file version its side refers to), so lines of both sides of
     * a file are compared directly, which is close enough for ordering. */
    data class Entry(val fileIndex: Int, val threadId: Long, val side: ReviewSide, val line: Int)

    private val ORDER: Comparator<Entry> = compareBy({ it.fileIndex }, { it.line }, { it.side != ReviewSide.NEW }, { it.threadId })

    /** Threads of [files] in navigation order; resolved ones only with [includeResolved]. */
    fun entries(files: List<File>, threads: List<ReviewThread>, includeResolved: Boolean): List<Entry> =
        threads
            .filter { includeResolved || !it.isResolved }
            .mapNotNull { thread ->
                val index = files.indexOfFirst { it.contains(thread) }
                if (index < 0) null else Entry(index, thread.id, thread.side, thread.line)
            }
            .sortedWith(ORDER)

    /**
     * Thread after [current] (the last visited one, which may since have
     * been filtered out, e.g. resolved) if it is in [currentFile], the
     * file shown now; otherwise the first thread of [currentFile] or of a
     * later file. Null at the end.
     */
    fun next(entries: List<Entry>, current: Entry?, currentFile: Int): Entry? =
        if (current != null && current.fileIndex == currentFile) {
            entries.firstOrNull { ORDER.compare(it, current) > 0 }
        } else {
            entries.firstOrNull { it.fileIndex >= currentFile }
        }

    /** Mirror of [next]: the thread before [current], or else the last
     * thread of [currentFile] or of an earlier file. Null at the start. */
    fun previous(entries: List<Entry>, current: Entry?, currentFile: Int): Entry? =
        if (current != null && current.fileIndex == currentFile) {
            entries.lastOrNull { ORDER.compare(it, current) < 0 }
        } else {
            entries.lastOrNull { it.fileIndex <= currentFile }
        }

    /** Entry of thread [threadId], resolved or not, or null if it isn't in [files]. */
    fun locate(files: List<File>, threads: List<ReviewThread>, threadId: Long?): Entry? {
        if (threadId == null) return null
        return entries(files, threads.filter { it.id == threadId }, includeResolved = true).firstOrNull()
    }
}
