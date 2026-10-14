package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.util.Key

/**
 * Attached to a [com.intellij.diff.requests.DiffRequest] via `putUserData`
 * before calling `DiffManager.showDiff`, so [GiteaCommentDiffExtension] can
 * read it back in `onViewerCreated` — that's the only hand-off point
 * `DiffExtension` gives us between "we opened this diff" and "the viewer
 * exists," so context has to travel on the request itself.
 *
 * The request's left side must be the file at the merge base and the right
 * side the file at `pr.head.sha`: review comment lines refer to those
 * versions.
 */
data class GiteaDiffCommentContext(
    val repo: GiteaRepoContext,
    val client: GiteaApiClient,
    val pullRequest: PullRequest,
    val path: String,
    val basePath: String,
    val pendingStore: PendingReviewStore,
)

val GITEA_DIFF_COMMENT_CONTEXT_KEY: Key<GiteaDiffCommentContext> = Key.create("Gitea.DiffCommentContext")

/**
 * Marks diff chains opened by this plugin, so their editor tabs, whose
 * requests carry [GiteaDiffCommentContext], can be closed before the plugin
 * is unloaded (see [com.github.hypercookie.giteaplugin.GiteaPluginUnloadListener]).
 */
val GITEA_DIFF_CHAIN_KEY: Key<Boolean> = Key.create("Gitea.DiffChain")
