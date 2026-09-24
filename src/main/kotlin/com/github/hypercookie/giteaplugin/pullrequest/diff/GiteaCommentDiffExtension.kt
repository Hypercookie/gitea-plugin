package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JButton

/**
 * Renders existing Gitea review comments as gutter icons in the diff viewer,
 * on both the base and head editors, and lets the user reply to one.
 * Registered as `<diff.DiffExtension>` — a real, public, `dynamic="true"`
 * platform extension point (confirmed by inspecting the bundled platform
 * jar's own `PlatformExtensionPoints.xml`; see
 * docs/plan/pr-review-feature-plan.md Phase 5/6 hardening-pass notes).
 *
 * Deliberately built on stable primitives (`RangeHighlighter` +
 * `GutterIconRenderer` + a plain popup) rather than the internal
 * `com.intellij.collaboration.*` module — confirmed to exist and be what a
 * more mature third-party Gitea plugin
 * (github.com/JpMand/gitea-idea-integration) uses for a richer inlay-based
 * UI, at the cost of `@Suppress("UnstableApiUsage")` and IDE-version
 * fragility. That tradeoff isn't worth it here.
 *
 * Position mapping: Gitea's `position`/`original_position` are confirmed
 * (empirically, and independently corroborated by the same third-party
 * plugin's code) to be 1-indexed real file line numbers, not diff-hunk
 * offsets — so `line - 1` is the entire conversion to a 0-indexed editor line.
 */
class GiteaCommentDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, diffContext: DiffContext, request: DiffRequest) {
        val commentContext = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        if (viewer !is TwosideTextDiffViewer) return
        val project = diffContext.project ?: return

        installCommentGutter(project, viewer.getEditor1(), commentContext, useOriginalPosition = true)
        installCommentGutter(project, viewer.getEditor2(), commentContext, useOriginalPosition = false)
    }

    private fun installCommentGutter(
        project: Project,
        editor: EditorEx,
        commentContext: GiteaDiffCommentContext,
        useOriginalPosition: Boolean,
    ) {
        for (comment in commentContext.comments) {
            val position = if (useOriginalPosition) comment.originalPosition else comment.position
            if (position == null || position <= 0) continue

            val lineIndex = (position - 1).toInt()
            if (lineIndex < 0 || lineIndex >= editor.document.lineCount) continue

            val highlighter = editor.markupModel.addLineHighlighter(null, lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX)
            highlighter.gutterIconRenderer = CommentGutterIconRenderer(comment) {
                showCommentPopup(project, editor, commentContext, comment)
            }
        }
    }

    private fun showCommentPopup(project: Project, editor: EditorEx, commentContext: GiteaDiffCommentContext, comment: PullReviewComment) {
        lateinit var popup: JBPopup

        val statusLabel = JBLabel(" ")
        val replyField = JBTextArea(3, 30).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val replyButton = JButton("Reply")

        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(360, 220)
            add(JBLabel(comment.user?.login ?: "unknown"), BorderLayout.NORTH)

            val body = JBTextArea(comment.body).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            add(JBScrollPane(body), BorderLayout.CENTER)

            val replyPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JBScrollPane(replyField), BorderLayout.CENTER)
                val actionsRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(statusLabel)
                    add(replyButton)
                }
                add(actionsRow, BorderLayout.SOUTH)
            }
            add(replyPanel, BorderLayout.SOUTH)
        }

        replyButton.addActionListener {
            val text = replyField.text.trim()
            if (text.isEmpty()) return@addActionListener
            replyButton.isEnabled = false
            statusLabel.text = "Sending…"
            GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
                val result = runCatching {
                    commentContext.client.replyToComment(commentContext.owner, commentContext.repo, commentContext.prNumber, comment.id, text)
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = {
                            popup.cancel()
                            notify(project, "Reply posted.", NotificationType.INFORMATION)
                        },
                        onFailure = { error ->
                            replyButton.isEnabled = true
                            statusLabel.text = "Failed: ${error.message}"
                        },
                    )
                }
            }
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, replyField)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        popup.showInBestPositionFor(editor)
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Gitea")
            .createNotification(text, type)
            .notify(project)
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
