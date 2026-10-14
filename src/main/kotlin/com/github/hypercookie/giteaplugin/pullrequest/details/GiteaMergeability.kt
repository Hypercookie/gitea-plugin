package com.github.hypercookie.giteaplugin.pullrequest.details

/**
 * What Gitea's `mergeable` flag means for one pull request.
 *
 * Gitea 1.27 reports `mergeable: false` when the pull request is still being
 * checked, has conflicts, failed its conflict check, or is a work in
 * progress (`models/issues/pull.go`, `PullRequest.Mergeable`). The API does
 * not say which. Work in progress is reported separately as `draft`, and a
 * pending check clears after a moment, so after excluding those the
 * remaining cases are a conflict or a failed check.
 */
enum class GiteaMergeability {
    /** Can be merged, or the question doesn't apply (closed, merged, draft). */
    OK,

    /** `mergeable` is false; re-fetching may still turn it true. */
    CHECKING,

    /** Still false after re-checking: conflicts, or Gitea could not check. */
    BLOCKED,
    ;

    companion object {
        /** First assessment from a single API response. */
        fun of(state: String, merged: Boolean, draft: Boolean, mergeable: Boolean?): GiteaMergeability = when {
            state != "open" || merged || draft -> OK
            mergeable == false -> CHECKING
            else -> OK
        }
    }
}
