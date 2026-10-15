package com.github.hypercookie.giteaplugin.pullrequest.details

import com.github.hypercookie.giteaplugin.pullrequest.diff.CommentNavigation
import com.github.hypercookie.giteaplugin.pullrequest.diff.GITEA_DIFF_CHAIN_KEY
import com.github.hypercookie.giteaplugin.pullrequest.diff.GITEA_DIFF_COMMENT_CONTEXT_KEY
import com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaDiffCommentContext
import com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaDiffCommentNavigation
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileTypes.FileTypeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opens pull request file diffs in the platform diff viewer, with file
 * contents from the Gitea API (no local fetch of the PR branch needed).
 * The GitHub plugin builds these requests from local git objects through
 * its internal diff-processor view models instead.
 */
object GiteaPullRequestDiff {

    /** Shows [changes] as one diff chain, starting at [selectedIndex]. */
    fun show(data: GiteaPullRequestReviewData, changes: List<RefComparisonChange>, selectedIndex: Int) {
        if (changes.isEmpty()) return
        data.cs.launch {
            val requests = coroutineScope {
                changes.map { change -> async { createRequest(data, change) } }.map { it.await() }
            }
            withContext(Dispatchers.Main) {
                val chain = SimpleDiffRequestChain(requests, selectedIndex.coerceIn(0, requests.lastIndex)).apply {
                    putUserData(GITEA_DIFF_CHAIN_KEY, true)
                    putUserData(GiteaDiffCommentNavigation.KEY, GiteaDiffCommentNavigation(requests, requests.map(::navigationFile)))
                    // Shown in the toolbar after the platform's navigation arrows.
                    putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, COMMENT_NAVIGATION_ACTIONS.mapNotNull(ActionManager.getInstance()::getAction))
                }
                DiffManager.getInstance().showDiff(data.project, chain, com.intellij.diff.DiffDialogHints.DEFAULT)
            }
        }
    }

    private val COMMENT_NAVIGATION_ACTIONS = listOf("Gitea.Diff.Review.PreviousComment", "Gitea.Diff.Review.NextComment")

    private fun navigationFile(request: SimpleDiffRequest): CommentNavigation.File {
        val ctx = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY)!!
        val (before, after) = request.contents
        return CommentNavigation.File(ctx.path, ctx.basePath, hasNew = after !is EmptyContent, hasOld = before !is EmptyContent)
    }

    private suspend fun createRequest(data: GiteaPullRequestReviewData, change: RefComparisonChange): SimpleDiffRequest {
        val beforePath = change.filePathBefore?.let { GiteaChangePaths.relative(data, it) }
        val afterPath = change.filePathAfter?.let { GiteaChangePaths.relative(data, it) }
        val beforeText = beforePath?.let { loadText(data, it, change.revisionNumberBefore.asString()) }
        val afterText = afterPath?.let { loadText(data, it, change.revisionNumberAfter.asString()) }

        val name = afterPath ?: beforePath.orEmpty()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(name)
        val factory = DiffContentFactory.getInstance()
        val before = beforeText?.let { factory.create(data.project, it, fileType) } ?: factory.createEmpty()
        val after = afterText?.let { factory.create(data.project, it, fileType) } ?: factory.createEmpty()
        val pr = data.pullRequest.value
        return SimpleDiffRequest("#${pr.number}: $name", before, after, change.revisionNumberBefore.toShortString(), change.revisionNumberAfter.toShortString()).apply {
            putUserData(
                GITEA_DIFF_COMMENT_CONTEXT_KEY,
                GiteaDiffCommentContext(data.repo, data.client, pr, afterPath ?: name, beforePath ?: name, data.pendingStore),
            )
        }
    }

    /** File text, or null (shown as an empty side) when it is missing or
     * failed to load. A failure is logged rather than failing the whole
     * chain, and isn't cached, so reopening the diff retries. */
    private suspend fun loadText(data: GiteaPullRequestReviewData, path: String, ref: String): String? =
        try {
            data.fileContent(path, ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Failed to load $path at $ref for the diff of #${data.number}", e)
            null
        }
}

/** Repository-relative paths <-> [com.intellij.openapi.vcs.FilePath]s under
 * the local repository root, so the changes tree groups by directory like
 * the GitHub plugin's. */
object GiteaChangePaths {
    private fun rootPath(data: GiteaPullRequestReviewData): String = data.repo.gitRoot?.path ?: ""

    fun filePath(data: GiteaPullRequestReviewData, relative: String): com.intellij.openapi.vcs.FilePath =
        com.intellij.vcsUtil.VcsUtil.getFilePath("${rootPath(data)}/$relative", false)

    fun relative(data: GiteaPullRequestReviewData, path: com.intellij.openapi.vcs.FilePath): String =
        path.path.removePrefix(rootPath(data)).removePrefix("/")
}
