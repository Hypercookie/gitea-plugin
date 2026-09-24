package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.Icon

/**
 * Renders existing Gitea review comments as gutter icons in the diff viewer,
 * on both the base and head editors. Registered as `<diff.DiffExtension>` —
 * a real, public, `dynamic="true"` platform extension point (confirmed by
 * inspecting the bundled platform jar's own `PlatformExtensionPoints.xml`;
 * see docs/plan/pr-review-feature-plan.md Phase 5/6 hardening-pass notes).
 *
 * Deliberately built on stable primitives (`RangeHighlighter` +
 * `GutterIconRenderer` + a plain popup) rather than the internal
 * `com.intellij.collaboration.*` module — confirmed to exist and be what a
 * more mature third-party Gitea plugin
 * (github.com/JpMand/gitea-idea-integration) uses for a richer inlay-based
 * UI, at the cost of `@Suppress("UnstableApiUsage")` and IDE-version
 * fragility. That tradeoff isn't worth it for read-only comment display.
 *
 * Position mapping: Gitea's `position`/`original_position` are confirmed
 * (empirically, and independently corroborated by the same third-party
 * plugin's code) to be 1-indexed real file line numbers, not diff-hunk
 * offsets — so `line - 1` is the entire conversion to a 0-indexed editor line.
 */
class GiteaCommentDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val commentContext = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        if (viewer !is TwosideTextDiffViewer) return

        installCommentGutter(viewer.getEditor1(), commentContext, useOriginalPosition = true)
        installCommentGutter(viewer.getEditor2(), commentContext, useOriginalPosition = false)
    }

    private fun installCommentGutter(editor: EditorEx, context: GiteaDiffCommentContext, useOriginalPosition: Boolean) {
        for (comment in context.comments) {
            val position = if (useOriginalPosition) comment.originalPosition else comment.position
            if (position == null || position <= 0) continue

            val lineIndex = (position - 1).toInt()
            if (lineIndex < 0 || lineIndex >= editor.document.lineCount) continue

            val highlighter = editor.markupModel.addLineHighlighter(null, lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX)
            highlighter.gutterIconRenderer = CommentGutterIconRenderer(comment) { showCommentPopup(editor, comment) }
        }
    }

    private fun showCommentPopup(editor: EditorEx, comment: PullReviewComment) {
        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(340, 140)
            add(JBLabel(comment.user?.login ?: "unknown"), BorderLayout.NORTH)
            val body = JBTextArea(comment.body).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            add(JBScrollPane(body), BorderLayout.CENTER)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            .showInBestPositionFor(editor)
    }
}

private class CommentGutterIconRenderer(
    private val comment: PullReviewComment,
    private val onClick: () -> Unit,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Balloon

    override fun getTooltipText(): String =
        "${comment.user?.login ?: "unknown"}: ${comment.body.take(80)}"

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = onClick()
    }

    override fun equals(other: Any?): Boolean = other is CommentGutterIconRenderer && other.comment.id == comment.id
    override fun hashCode(): Int = comment.id.hashCode()
}
