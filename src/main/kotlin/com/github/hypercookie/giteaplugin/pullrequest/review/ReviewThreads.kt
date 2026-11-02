package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment

/** Which version of the file a review comment's line number refers to. */
enum class ReviewSide {
    /** `position`: a line of the file at the review's commit. */
    NEW,

    /** `original_position`: a line of the merge-base version. */
    OLD,
}

/**
 * One review conversation: comments on the same file, side, line and anchor
 * commit, oldest first. Gitea has no thread object in its REST API, so
 * threads are rebuilt client-side from flat `PullReviewComment`s.
 */
data class ReviewThread(
    val path: String,
    val side: ReviewSide,
    /** 0-based line in the file version the side refers to. */
    val line: Int,
    /** Commit the line number refers to: the commit of the review that holds
     * the thread's first comment, null for the pull request head. Gitea's
     * per-comment `commit_id` is only the commit that last changed the line
     * (a blame), so it says nothing about how lines are numbered. */
    val commitId: String?,
    val comments: List<PullReviewComment>,
) {
    val id: Long get() = comments.first().id

    /** Gitea tracks resolution on each comment; the first comment's
     * `resolver` stands for the conversation. */
    val resolver: GiteaUser? get() = comments.first().resolver
    val isResolved: Boolean get() = resolver != null
}

object ReviewThreads {

    /** [reviewCommits] maps a review id to its `commit_id`. */
    fun group(comments: List<PullReviewComment>, reviewCommits: Map<Long, String?> = emptyMap()): List<ReviewThread> = comments
        .mapNotNull { comment ->
            val newLine = comment.position?.takeIf { it > 0 }
            val oldLine = comment.originalPosition?.takeIf { it > 0 }
            when {
                newLine != null -> Key(comment.path, ReviewSide.NEW, (newLine - 1).toInt(), comment.commitId) to comment
                oldLine != null -> Key(comment.path, ReviewSide.OLD, (oldLine - 1).toInt(), comment.commitId) to comment
                else -> null
            }
        }
        .groupBy({ it.first }, { it.second })
        .map { (key, thread) ->
            val sorted = thread.sortedWith(compareBy({ it.createdAt ?: "" }, { it.id }))
            val anchor = sorted.first().reviewId?.let { reviewCommits[it] }?.takeIf { it.isNotBlank() }
            ReviewThread(key.path, key.side, key.line, anchor, sorted)
        }
        .sortedWith(compareBy({ it.path }, { it.line }, { it.id }))

    private data class Key(val path: String, val side: ReviewSide, val line: Int, val commitId: String?)
}
