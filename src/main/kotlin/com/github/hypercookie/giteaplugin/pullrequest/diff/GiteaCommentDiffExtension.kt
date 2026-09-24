package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
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
 * Renders Gitea PR review comments as gutter icons in the diff viewer, on
 * both the base and head editors — one icon per line, representing either
 * an existing comment thread, a locally-queued pending comment (Phase 8,
 * not yet submitted), or an "add comment" affordance on lines that are part
 * of the diff but have neither yet. Also handles replying to an existing
 * thread (Phase 7).
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
 *
 * "Addable" lines (where the "+" affordance shows) are restricted to lines
 * actually part of a `SimpleDiffChange` on that side — Gitea's own API does
 * NOT validate this server-side (confirmed during the Phase 2 hardening
 * pass: it accepted comments on context lines and even completely
 * out-of-diff lines without error), so this plugin enforces it client-side
 * instead, matching GitHub/GitLab's UX.
 */
class GiteaCommentDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, diffContext: DiffContext, request: DiffRequest) {
        val commentContext = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        if (viewer !is SimpleDiffViewer) return
        val project = diffContext.project ?: return

        val controller = DiffCommentGutterController(project, viewer, commentContext)
        controller.updateComments(commentContext.comments)

        // Any pending-store change re-fetches from the server rather than
        // just re-rendering stale data. Needed for the case where the change
        // was "a review got submitted elsewhere" (Phase 9's submit clears
        // the store) — the just-submitted comments are now real server-side
        // comments, and only a fresh fetch turns their pending markers into
        // real thread markers instead of them just vanishing back to "+".
        commentContext.pendingStore.addListener {
            GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
                val fresh = runCatching { commentContext.fetchFreshComments() }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (fresh != null) controller.updateComments(fresh) else controller.render()
                }
            }
        }

        // onViewerCreated fires before the diff is actually computed —
        // viewer.getDiffChanges() is empty at this point, so the "+" add
        // affordance (which depends on it) wouldn't appear until some other
        // trigger caused a re-render. Existing comment/pending icons are
        // unaffected (they don't depend on diff computation), which is why
        // this only broke the "+" icons specifically. Re-render once the
        // real diff is in.
        viewer.addListener(object : DiffViewerListener() {
            override fun onAfterRediff() = controller.render()
        })
    }
}

private class DiffCommentGutterController(
    private val project: Project,
    private val viewer: SimpleDiffViewer,
    private val commentContext: GiteaDiffCommentContext,
) {
    private val baseEditor get() = viewer.getEditor1()
    private val headEditor get() = viewer.getEditor2()
    private val baseHighlighters = mutableListOf<RangeHighlighter>()
    private val headHighlighters = mutableListOf<RangeHighlighter>()

    private var currentComments: List<PullReviewComment> = emptyList()

    fun updateComments(comments: List<PullReviewComment>) {
        currentComments = comments
        render()
    }

    fun render() {
        clear(baseEditor, baseHighlighters)
        clear(headEditor, headHighlighters)
        installLine(baseEditor, baseHighlighters, Side.LEFT, useOriginalPosition = true)
        installLine(headEditor, headHighlighters, Side.RIGHT, useOriginalPosition = false)
    }

    private fun clear(editor: EditorEx, tracked: MutableList<RangeHighlighter>) {
        tracked.forEach { editor.markupModel.removeHighlighter(it) }
        tracked.clear()
    }

    private fun installLine(editor: EditorEx, tracked: MutableList<RangeHighlighter>, side: Side, useOriginalPosition: Boolean) {
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

        val addableLines = mutableSetOf<Int>()
        for (change in viewer.getDiffChanges()) {
            for (line in change.getStartLine(side) until change.getEndLine(side)) addableLines += line
        }

        val allLines = threadsByLine.keys + pendingByLine.keys + addableLines
        for (lineIndex in allLines) {
            if (lineIndex < 0 || lineIndex >= editor.document.lineCount) continue

            val pending = pendingByLine[lineIndex]
            val thread = threadsByLine[lineIndex]
            val renderer = when {
                pending != null -> PendingCommentGutterIconRenderer(pending) { showPendingPopup(editor, pending) }
                thread != null -> CommentGutterIconRenderer(thread) { showThreadPopup(editor, thread) }
                else -> AddCommentGutterIconRenderer { showAddCommentPopup(editor, side, lineIndex) }
            }

            val highlighter = editor.markupModel.addLineHighlighter(null, lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX)
            highlighter.gutterIconRenderer = renderer
            tracked += highlighter
        }
    }

    private fun showAddCommentPopup(editor: EditorEx, side: Side, lineIndex: Int) {
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
                newPosition = if (side == Side.RIGHT) position else 0,
                oldPosition = if (side == Side.LEFT) position else 0,
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

    private fun showPendingPopup(editor: EditorEx, pending: CreatePullReviewComment) {
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
