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
import com.intellij.openapi.editor.markup.RangeHighlighter
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
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JSeparator

/**
 * Renders existing Gitea review comments as gutter icons in the diff viewer,
 * on both the base and head editors, grouped into threads by line (Gitea
 * itself threads review comments by matching path+position, not an explicit
 * parent-comment id — so grouping by line is the correct thread boundary,
 * not just a display convenience). Lets the user reply, and re-renders the
 * gutter with fresh data afterward so the new reply shows without reopening
 * the diff.
 *
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

        val controller = DiffCommentGutterController(project, viewer.getEditor1(), viewer.getEditor2(), commentContext)
        controller.render(commentContext.comments)
    }
}

private class DiffCommentGutterController(
    private val project: Project,
    private val baseEditor: EditorEx,
    private val headEditor: EditorEx,
    private val commentContext: GiteaDiffCommentContext,
) {
    private val baseHighlighters = mutableListOf<RangeHighlighter>()
    private val headHighlighters = mutableListOf<RangeHighlighter>()

    fun render(comments: List<PullReviewComment>) {
        baseHighlighters.forEach { baseEditor.markupModel.removeHighlighter(it) }
        baseHighlighters.clear()
        headHighlighters.forEach { headEditor.markupModel.removeHighlighter(it) }
        headHighlighters.clear()

        installThreads(baseEditor, baseHighlighters, comments, useOriginalPosition = true)
        installThreads(headEditor, headHighlighters, comments, useOriginalPosition = false)
    }

    private fun installThreads(
        editor: EditorEx,
        tracked: MutableList<RangeHighlighter>,
        comments: List<PullReviewComment>,
        useOriginalPosition: Boolean,
    ) {
        val threadsByLine = comments
            .mapNotNull { comment ->
                val position = if (useOriginalPosition) comment.originalPosition else comment.position
                if (position != null && position > 0) position to comment else null
            }
            .groupBy({ it.first }, { it.second })

        for ((position, thread) in threadsByLine) {
            val lineIndex = (position - 1).toInt()
            if (lineIndex < 0 || lineIndex >= editor.document.lineCount) continue

            val sortedThread = thread.sortedBy { it.createdAt ?: "" }
            val highlighter = editor.markupModel.addLineHighlighter(null, lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX)
            highlighter.gutterIconRenderer = CommentGutterIconRenderer(sortedThread) {
                showThreadPopup(editor, sortedThread)
            }
            tracked += highlighter
        }
    }

    private fun showThreadPopup(editor: EditorEx, thread: List<PullReviewComment>) {
        lateinit var popup: JBPopup

        val statusLabel = JBLabel(" ")
        val replyField = JBTextArea(3, 30).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val replyButton = JButton("Reply")

        val threadPanel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            thread.forEachIndexed { index, comment ->
                if (index > 0) add(JSeparator())
                add(JBLabel(comment.user?.login ?: "unknown"))
                add(
                    JBTextArea(comment.body).apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                    },
                )
            }
        }

        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(380, 260)
            add(JBScrollPane(threadPanel), BorderLayout.CENTER)

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
            val anchorComment = thread.first()
            replyButton.isEnabled = false
            statusLabel.text = "Sending…"
            GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
                val result = runCatching {
                    commentContext.client.replyToComment(
                        commentContext.owner, commentContext.repo, commentContext.prNumber, anchorComment.id, text,
                    )
                    commentContext.fetchFreshComments()
                }
                withContext(Dispatchers.Main) {
                    result.fold(
                        onSuccess = { freshComments ->
                            popup.cancel()
                            render(freshComments)
                            notify("Reply posted.", NotificationType.INFORMATION)
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

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Gitea")
            .createNotification(text, type)
            .notify(project)
    }
}

private class CommentGutterIconRenderer(
    private val thread: List<PullReviewComment>,
    private val onClick: () -> Unit,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Balloon

    override fun getTooltipText(): String {
        val first = thread.first()
        val suffix = if (thread.size > 1) " (+${thread.size - 1} more)" else ""
        return "${first.user?.login ?: "unknown"}: ${first.body.take(80)}$suffix"
    }

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = onClick()
    }

    override fun equals(other: Any?): Boolean =
        other is CommentGutterIconRenderer && other.thread.map { it.id } == thread.map { it.id }

    override fun hashCode(): Int = thread.map { it.id }.hashCode()
}
