package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaReviewSessionService
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.EditorLineTransfer
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaReviewEditorModel
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaReviewInEditorController
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaUnifiedReviewEditorModel
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.contents.EmptyContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Review comments in the pull request diff viewer, with the same model and
 * components as the regular editor ([GiteaReviewInEditorController]):
 * - side by side ([SimpleDiffViewer]): new-side threads on the head editor,
 *   old-side threads on the base editor;
 * - one-sided ([SimpleOnesideDiffViewer], which the platform picks for an
 *   added or deleted file): the side that exists;
 * - unified ([UnifiedDiffViewer]): both sides in its one editor, see
 *   [GiteaUnifiedReviewEditorModel] (migration ledger I16).
 *
 * Switching the viewer in the diff toolbar creates a new viewer, and the
 * platform calls [onViewerCreated] again for it.
 */
class GiteaCommentDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, diffContext: DiffContext, request: DiffRequest) {
        val ctx = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        if (viewer !is SimpleDiffViewer && viewer !is SimpleOnesideDiffViewer && viewer !is UnifiedDiffViewer) return
        val project = diffContext.project ?: return

        val cs = GiteaProjectCoroutineScope.getInstance(project).coroutineScope.giteaChildScope("Gitea diff review")
        Disposer.register(viewer) { cs.cancel() }

        // Reuse the current-branch session when it is the same PR, so
        // comments and pending state are shared with open editors.
        val session = GiteaReviewSessionService.getInstance(project).session.value
        val data = session?.takeIf { it.repo == ctx.repo && it.number == ctx.pullRequest.number }
            ?: GiteaPullRequestReviewData(project, cs, ctx.repo, ctx.client, ctx.pullRequest, ctx.pendingStore)

        when (viewer) {
            is SimpleDiffViewer -> {
                showSide(cs, data, ctx, viewer.getEditor2() as? EditorEx, ReviewSide.NEW)
                showSide(cs, data, ctx, viewer.getEditor1() as? EditorEx, ReviewSide.OLD)
            }
            is SimpleOnesideDiffViewer -> showSide(cs, data, ctx, viewer.editor, viewer.side.reviewSide)
            is UnifiedDiffViewer -> showUnified(cs, data, ctx, viewer)
        }
    }

    private fun showSide(cs: CoroutineScope, data: GiteaPullRequestReviewData, ctx: GiteaDiffCommentContext, editor: EditorEx?, side: ReviewSide) {
        if (editor == null) return
        val path = if (side == ReviewSide.NEW) ctx.path else ctx.basePath
        cs.launch(Dispatchers.Main) {
            GiteaReviewInEditorController.showReview(this, data, editor, path, ctx.basePath, side, showChanges = false)
        }
    }

    /**
     * One model per non-empty side, reading that side's document, with its
     * lines moved into the unified editor by the viewer's own line transfer.
     * The viewer rebuilds its document on every rediff (the first one runs
     * after this extension), so the transfer is refreshed then and gutter
     * controls and inlays are rendered again; models, threads and comment
     * drafts stay.
     */
    private fun showUnified(cs: CoroutineScope, data: GiteaPullRequestReviewData, ctx: GiteaDiffCommentContext, viewer: UnifiedDiffViewer) {
        val contents = viewer.request.contents
        val sides = Side.entries.filter { side -> side.select(contents) !is EmptyContent }
        val lines = sides.associateWith { MutableStateFlow<EditorLineTransfer>(EditorLineTransfer.None) }
        val layout = MutableStateFlow(0)
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() {
                val lineCount = viewer.editor.document.lineCount
                for ((side, transfer) in lines) {
                    transfer.value = EditorLineTransfer.of(IntArray(lineCount) { viewer.transferLineFromOnesideStrict(side, it) })
                }
                layout.value++
            }
        })

        cs.launch(Dispatchers.Main) {
            fun model(side: Side): GiteaReviewEditorModel? {
                val transfer = lines[side] ?: return null
                val reviewSide = side.reviewSide
                val path = if (reviewSide == ReviewSide.NEW) ctx.path else ctx.basePath
                return GiteaReviewEditorModel(this, data, path, ctx.basePath, reviewSide, viewer.getDocument(side), showChanges = false, transfer)
            }
            val model = GiteaUnifiedReviewEditorModel(this, model(Side.RIGHT), model(Side.LEFT))
            layout.collectLatest {
                coroutineScope { GiteaReviewInEditorController.renderReview(this, data, viewer.editor, model) }
            }
        }
    }

    /** Diff sides: the request's right content is the PR head (see [GiteaDiffCommentContext]). */
    private val Side.reviewSide: ReviewSide get() = if (this == Side.RIGHT) ReviewSide.NEW else ReviewSide.OLD
}
