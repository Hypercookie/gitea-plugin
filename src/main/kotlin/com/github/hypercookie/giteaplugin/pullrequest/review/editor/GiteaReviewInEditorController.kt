package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaReviewSessionService
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.collaboration.ui.codereview.editor.CodeReviewComponentInlayRenderer
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterChangesRenderer
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsRenderer
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import com.intellij.collaboration.ui.codereview.editor.renderInlays
import com.intellij.collaboration.util.HashingUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VfsUtilCore
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.HierarchyEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Shows the current branch's pull request review in regular editors.
 * Same shape as the GitHub plugin's `GHPRReviewInEditorController`: an
 * `EditorFactoryListener` that, per main editor, follows the "PR on current
 * branch" flow and installs the review model, gutter renderers and inlays
 * while one exists.
 */
class GiteaReviewInEditorController : EditorFactoryListener {

    private val jobs = ConcurrentHashMap<Editor, Job>()

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorEx ?: return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return

        val sessions = GiteaReviewSessionService.getInstance(project).session
        jobs[editor] = GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch(Dispatchers.Main) {
            sessions.collectLatest { data ->
                if (data == null) return@collectLatest
                // getRepositoryForFile may update repository state
                // synchronously, which the platform forbids on the EDT.
                val path = withContext(Dispatchers.Default) {
                    val repository = GitRepositoryManager.getInstance(project).getRepositoryForFile(file) ?: return@withContext null
                    if (data.repo.gitRoot != null && data.repo.gitRoot != repository.root) return@withContext null
                    VfsUtilCore.getRelativePath(file, repository.root)
                } ?: return@collectLatest
                coroutineScope { showReview(this, data, editor, path) }
            }
        }
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        jobs.remove(event.editor)?.cancel()
    }

    companion object {
        /**
         * Installs review UI for [path] into [editor] until [cs] is cancelled.
         * Shared with the diff viewer extension.
         */
        suspend fun showReview(
            cs: CoroutineScope,
            data: GiteaPullRequestReviewData,
            editor: EditorEx,
            path: String,
            basePath: String = path,
            side: ReviewSide = ReviewSide.NEW,
            showChanges: Boolean = true,
        ): Nothing {
            val model = GiteaReviewEditorModel(cs, data, path, basePath, side, editor.document, showChanges)
            if (showChanges) cs.launch(Dispatchers.Main) { CodeReviewEditorGutterChangesRenderer.render(model, editor) }
            renderReview(cs, data, editor, model)
            awaitCancellation()
        }

        /**
         * Gutter comment controls and inlays of [model] in [editor] until
         * [cs] is cancelled. Shared with the unified diff viewer, which
         * renders a [GiteaUnifiedReviewEditorModel].
         */
        fun renderReview(
            cs: CoroutineScope,
            data: GiteaPullRequestReviewData,
            editor: EditorEx,
            model: CodeReviewEditorModel<GiteaEditorInlay>,
        ) {
            cs.launch(Dispatchers.Main) { CodeReviewEditorGutterControlsRenderer.render(model, editor) }
            cs.launch(Dispatchers.Main) {
                editor.renderInlays(model.inlays, HashingUtil.mappingStrategy(GiteaEditorInlay::key)) { inlay ->
                    val component = GiteaReviewEditorComponents.create(this, data.project, inlay)
                    component.addHierarchyListener { e ->
                        if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) inlay.shownState.value = component.isShowing
                    }
                    CodeReviewComponentInlayRenderer(component)
                }
            }
        }
    }
}
