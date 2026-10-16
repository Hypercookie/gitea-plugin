package com.github.hypercookie.giteaplugin.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Matches Gitea's `ReviewStateType` enum (swagger `CreatePullReviewOptions.event`
 * / `PullReview.state`), confirmed against a live 1.27.3 instance. */
@Serializable
enum class ReviewEvent {
    @SerialName("APPROVED") APPROVED,
    @SerialName("PENDING") PENDING,
    @SerialName("COMMENT") COMMENT,
    @SerialName("REQUEST_CHANGES") REQUEST_CHANGES,
    @SerialName("REQUEST_REVIEW") REQUEST_REVIEW,
}

@Serializable
data class PullReview(
    val id: Long,
    val body: String? = null,
    val state: ReviewEvent,
    val user: GiteaUser? = null,
    val official: Boolean = false,
    val dismissed: Boolean = false,
    val stale: Boolean = false,
    @SerialName("commit_id") val commitId: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** Inline comments in this review; null from servers that don't send it. */
    @SerialName("comments_count") val commentsCount: Int? = null,
)

/** One inline diff comment, as returned by `GET .../reviews/{id}/comments`. */
@Serializable
data class PullReviewComment(
    val id: Long,
    val body: String,
    val path: String,
    /** New-file line number, or 0/null if the comment is on the old side. */
    val position: Long? = null,
    @SerialName("original_position") val originalPosition: Long? = null,
    @SerialName("commit_id") val commitId: String? = null,
    val user: GiteaUser? = null,
    @SerialName("pull_request_review_id") val reviewId: Long? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /** Who resolved this comment's conversation; null while unresolved.
     * Gitea tracks resolution on the thread's first comment. */
    val resolver: GiteaUser? = null,
)

/** Body for `POST /pulls/{index}/comments/{id}/replies` — path/position are
 * inherited from the comment being replied to, only `body` is sent. */
@Serializable
data class CreatePullReviewCommentReplyOptions(val body: String)

/** One pending inline comment to attach when creating a review
 * (`CreatePullReviewOptions.comments[]`). Exactly one of `newPosition` /
 * `oldPosition` should be set to a non-zero line; the other left 0. */
@Serializable
data class CreatePullReviewComment(
    val body: String,
    val path: String,
    @SerialName("new_position") val newPosition: Long = 0,
    @SerialName("old_position") val oldPosition: Long = 0,
)

/** Body for `POST /pulls/{index}/reviews` — creates and, unless `event` is
 * `PENDING`, immediately submits a review with its inline comments.
 *
 * Pass [commitId] as the PR's current head SHA (`PullRequest.head.sha`) —
 * that's what `PullReview.stale` tracks against, so leaving it null risks
 * the review (and its comments) showing as stale as soon as the PR gets a
 * new commit, even though Gitea accepts a null `commit_id` without error. */
@Serializable
data class CreatePullReviewOptions(
    val event: ReviewEvent,
    val body: String = "",
    @SerialName("commit_id") val commitId: String? = null,
    val comments: List<CreatePullReviewComment> = emptyList(),
)
