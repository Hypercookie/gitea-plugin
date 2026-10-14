package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaReviewSessionService
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaReviewInEditorController
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Review comments in the pull request diff viewer: new-side threads on the
 * head editor, old-side threads on the base editor, with the same model and
 * components as the regular editor
 * ([GiteaReviewInEditorController.showReview]).
 *
 * Only the side-by-side [SimpleDiffViewer] is supported; the unified viewer
 * shows no comments (migration ledger I16).
 */
class GiteaCommentDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, diffContext: DiffContext, request: DiffRequest) {
        val ctx = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        if (viewer !is SimpleDiffViewer) return
        val project = diffContext.project ?: return

        val cs = GiteaProjectCoroutineScope.getInstance(project).coroutineScope.giteaChildScope("Gitea diff review")
        Disposer.register(viewer) { cs.cancel() }

        // Reuse the current-branch session when it is the same PR, so
        // comments and pending state are shared with open editors.
        val session = GiteaReviewSessionService.getInstance(project).session.value
        val data = session?.takeIf { it.repo == ctx.repo && it.number == ctx.pullRequest.number }
            ?: GiteaPullRequestReviewData(project, cs, ctx.repo, ctx.client, ctx.pullRequest, ctx.pendingStore)

        val head = viewer.getEditor2() as? EditorEx
        val base = viewer.getEditor1() as? EditorEx
        if (head != null) {
            cs.launch(Dispatchers.Main) {
                GiteaReviewInEditorController.showReview(this, data, head, ctx.path, ctx.basePath, ReviewSide.NEW, showChanges = false)
            }
        }
        if (base != null) {
            cs.launch(Dispatchers.Main) {
                GiteaReviewInEditorController.showReview(this, data, base, ctx.basePath, ctx.basePath, ReviewSide.OLD, showChanges = false)
            }
        }
    }
}
