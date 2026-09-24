package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaDiffCommentContext
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
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
 * Renders one editor's worth of Gitea PR review-comment gutter icons — one
 * icon per line, either an existing comment thread, a locally-queued
 * pending comment (not yet submitted), or an "add comment" affordance.
 * Shared between the diff-viewer path (`GiteaCommentDiffExtension`, one
 * instance per side) and the working-copy-editor path (Phase 14) — both
 * just need *an* `EditorEx` and which position field (`position` vs
 * `originalPosition`) that editor's document corresponds to.
 *
 * "+" is now offered on every line, not just ones inside a diff hunk (see
 * docs/plan/pr-review-feature-plan.md, "Architecture pivot" — Gitea itself
 * never validated this server-side, so it was always a client-side-only
 * restriction, and the user explicitly wants to comment on any line).
 */
class CommentGutterController(
    private val project: Project,
    private val editor: EditorEx,
    private val commentContext: GiteaDiffCommentContext,
    private val useOriginalPosition: Boolean,
) {
    private val highlighters = mutableListOf<RangeHighlighter>()
    private var currentComments: List<PullReviewComment> = emptyList()

    fun updateComments(comments: List<PullReviewComment>) {
        currentComments = comments
        render()
    }

    fun render() {
        highlighters.forEach { editor.markupModel.removeHighlighter(it) }
        highlighters.clear()

        val threadsByLine = currentComments
            .mapNotNull { comment ->
                val position = if (useOriginalPosition) comment.originalPosition else comment.position
                if (position != null && position > 0) (position - 1).toInt() to comment else null
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, thread) -> thread.sortedBy { it.createdAt ?: "" } }

        val pendingByLine = commentContext.pendingStore.pendingComments
            .filter { it.path == commentContext.filename }
            .mapNotNull { pending ->
                val position = if (useOriginalPosition) pending.oldPosition else pending.newPosition
                if (position > 0) (position - 1).toInt() to pending else null
            }
            .toMap()

        val allLines = threadsByLine.keys + pendingByLine.keys + (0 until editor.document.lineCount)
        for (lineIndex in allLines) {
            if (lineIndex < 0 || lineIndex >= editor.document.lineCount) continue

            val pending = pendingByLine[lineIndex]
            val thread = threadsByLine[lineIndex]
            val renderer = when {
                pending != null -> PendingCommentGutterIconRenderer(pending) { showPendingPopup(pending) }
                thread != null -> CommentGutterIconRenderer(thread) { showThreadPopup(thread) }
                else -> AddCommentGutterIconRenderer { showAddCommentPopup(lineIndex) }
            }

            val highlighter = editor.markupModel.addLineHighlighter(null, lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX)
            highlighter.gutterIconRenderer = renderer
            highlighters += highlighter
        }
    }

    private fun showAddCommentPopup(lineIndex: Int) {
        lateinit var popup: JBPopup
        val textField = JBTextArea(3, 30).apply {
            lineWrap = true
            wrapStyleWord = true
        }
        val addButton = JButton("Add")
        addButton.addActionListener {
            val text = textField.text.trim()
            if (text.isEmpty()) return@addActionListener
            val position = (lineIndex + 1).toLong()
            val comment = CreatePullReviewComment(
                body = text,
                path = commentContext.filename,
                newPosition = if (!useOriginalPosition) position else 0,
                oldPosition = if (useOriginalPosition) position else 0,
            )
            commentContext.pendingStore.addComment(comment)
            popup.cancel()
        }

        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(340, 140)
            add(JBLabel("New comment (queued until you submit the review)"), BorderLayout.NORTH)
            add(JBScrollPane(textField), BorderLayout.CENTER)
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply { add(addButton) }, BorderLayout.SOUTH)
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textField)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        popup.showInBestPositionFor(editor)
    }

    private fun showPendingPopup(pending: CreatePullReviewComment) {
        val discardButton = JButton("Discard")
        lateinit var popup: JBPopup
        discardButton.addActionListener {
            commentContext.pendingStore.removeComment(pending)
            popup.cancel()
        }

        val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            preferredSize = Dimension(340, 140)
            add(JBLabel("Pending — not yet submitted"), BorderLayout.NORTH)
            add(
                JBScrollPane(
                    JBTextArea(pending.body).apply {
                        isEditable = false
                        lineWrap = true
                        wrapStyleWord = true
                    },
                ),
                BorderLayout.CENTER,
            )
            add(JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply { add(discardButton) }, BorderLayout.SOUTH)
        }

        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, null)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
        popup.showInBestPositionFor(editor)
    }

    private fun showThreadPopup(thread: List<PullReviewComment>) {
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
                            updateComments(freshComments)
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

private class PendingCommentGutterIconRenderer(
    private val pending: CreatePullReviewComment,
    private val onClick: () -> Unit,
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Modified

    override fun getTooltipText(): String = "Pending: ${pending.body.take(80)}"

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = onClick()
    }

    override fun equals(other: Any?): Boolean = other is PendingCommentGutterIconRenderer && other.pending === pending
    override fun hashCode(): Int = System.identityHashCode(pending)
}

private class AddCommentGutterIconRenderer(private val onClick: () -> Unit) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Add

    override fun getTooltipText(): String = "Add a review comment on this line"

    override fun getClickAction(): AnAction = object : AnAction() {
        override fun actionPerformed(e: AnActionEvent) = onClick()
    }

    override fun equals(other: Any?): Boolean = other is AddCommentGutterIconRenderer
    override fun hashCode(): Int = AddCommentGutterIconRenderer::class.hashCode()
}
