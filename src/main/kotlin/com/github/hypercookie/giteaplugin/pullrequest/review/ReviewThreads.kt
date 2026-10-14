package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment

/** Which version of the file a review comment's line number refers to. */
enum class ReviewSide {
    /** `position`: a line of the file at the comment's `commit_id`. */
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
    /** Commit the line number refers to (Gitea's `commit_id`). */
    val commitId: String?,
    val comments: List<PullReviewComment>,
) {
    val id: Long get() = comments.first().id

    /** Gitea tracks resolution on each comment; the first comment's
     * `resolver` stands for the conversation (migration ledger G9). */
    val resolver: GiteaUser? get() = comments.first().resolver
    val isResolved: Boolean get() = resolver != null
}

object ReviewThreads {

    fun group(comments: List<PullReviewComment>): List<ReviewThread> = comments
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
            ReviewThread(key.path, key.side, key.line, key.commitId, thread.sortedWith(compareBy({ it.createdAt ?: "" }, { it.id })))
        }
        .sortedWith(compareBy({ it.path }, { it.line }, { it.id }))

    private data class Key(val path: String, val side: ReviewSide, val line: Int, val commitId: String?)
}
