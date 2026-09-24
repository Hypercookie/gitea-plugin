package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.intellij.openapi.util.Key

/**
 * Attached to a [com.intellij.diff.requests.DiffRequest] via `putUserData`
 * before calling `DiffManager.showDiff`, so [GiteaCommentDiffExtension] can
 * read it back in `onViewerCreated` — that's the only hand-off point
 * `DiffExtension` gives us between "we opened this diff" and "the viewer
 * exists," so context has to travel on the request itself.
 */
data class GiteaDiffCommentContext(
    val filename: String,
    val comments: List<PullReviewComment>,
)

val GITEA_DIFF_COMMENT_CONTEXT_KEY: Key<GiteaDiffCommentContext> = Key.create("Gitea.DiffCommentContext")
