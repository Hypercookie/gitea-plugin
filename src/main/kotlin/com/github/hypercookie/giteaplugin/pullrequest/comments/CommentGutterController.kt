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
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Color
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Renders one editor's worth of Gitea PR review comments as real embedded
 * boxes (via [InlineComponentHost]) rather than gutter-icon-triggered
 * popups — per the user's explicit UI direction (see
 * docs/plan/pr-review-feature-plan.md, "UI pass"). The gutter "+" affordance
 * stays (cheap, discoverable launcher for a new comment on any line — every
 * line is addable, see the "Architecture pivot" section); clicking it, an
 * existing thread, or a pending comment now opens an inline box instead of
 * a [com.intellij.openapi.ui.popup.JBPopup]. The line a thread/pending
 * comment belongs to gets a background highlight.
 *
 * Shared between the diff-viewer path (`GiteaCommentDiffExtension`, one
 * instance per side) and the working-copy-editor path
 * (`WorkingCopyCommentEditorListener`).
 */
class CommentGutterController(
    private val project: Project,
    private val editor: EditorEx,
    private val commentContext: GiteaDiffCommentContext,
    private val useOriginalPosition: Boolean,
) {
    private val addGutterHighlighters = mutableListOf<RangeHighlighter>()
    private val lineBackgroundHighlighters = mutableListOf<RangeHighlighter>()
    private val threadInlays = mutableMapOf<Int, Inlay<ComponentInlayRenderer<JComponent>>>()
    private val pendingInlays = mutableMapOf<Int, Inlay<ComponentInlayRenderer<JComponent>>>()
    private var newCommentBox: Pair<Int, Inlay<ComponentInlayRenderer<JComponent>>>? = null

    private var currentComments: List<PullReviewComment> = emptyList()

    /** Bumped on every [render] call; a deferred render only actually runs
     * if it's still the most recent one scheduled — see [render]. */
    private var renderGeneration = 0

    fun updateComments(comments: List<PullReviewComment>) {
        currentComments = comments
        render()
    }

    private fun threadsByLine(): Map<Int, List<PullReviewComment>> = currentComments
        .mapNotNull { comment ->
            val position = if (useOriginalPosition) comment.originalPosition else comment.position
            if (position != null && position > 0) (position - 1).toInt() to comment else null
        }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, thread) -> thread.sortedBy { it.createdAt ?: "" } }

    private fun pendingByLine(): Map<Int, CreatePullReviewComment> = commentContext.pendingStore.pendingComments
        .filter { it.path == commentContext.filename }
        .mapNotNull { pending ->
            val position = if (useOriginalPosition) pending.oldPosition else pending.newPosition
            if (position > 0) (position - 1).toInt() to pending else null
        }
        .toMap()

    /** Deferred to the next EDT tick, not run synchronously: a dispose
     * followed immediately by installing a new inlay at that same editor
     * line, within the same call stack, left the old component visually
     * lingering during manual testing even though the dispose itself and
     * the data model were both already correct (confirmed via temporary
     * logging) — same class of inlay-mounting timing issue as Phase 18's
     * "boxes render but stay empty" root cause, on the removal side
     * instead of the mounting side.
     *
     * Guarded by [renderGeneration] rather than just deferring blindly:
     * [render] can legitimately be called again before a previously
     * deferred one has run (e.g. the pending-store listener calls it
     * synchronously, then again moments later once a background refetch
     * resolves) — without this guard, both deferred passes used to run in
     * turn, each adding inlays on top of whatever the previous pass had
     * already installed, without disposing them first, since the dispose
     * step had already run and cleared its own bookkeeping by the time the
     * second pass's install ran. Only the most recently scheduled pass
     * still matching [renderGeneration] when it fires actually disposes
     * and rebuilds; a superseded one is a no-op. */
    fun render() {
        renderGeneration++
        val thisGeneration = renderGeneration
        SwingUtilities.invokeLater {
            if (thisGeneration != renderGeneration) return@invokeLater
            if (editor.isDisposed) return@invokeLater
            doRender()
        }
    }

    private fun doRender() {
        addGutterHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        addGutterHighlighters.clear()
        lineBackgroundHighlighters.forEach { editor.markupModel.removeHighlighter(it) }
        lineBackgroundHighlighters.clear()
        threadInlays.values.forEach { if (it.isValid) it.dispose() }
        threadInlays.clear()
        pendingInlays.values.forEach { if (it.isValid) it.dispose() }
        pendingInlays.clear()

        val threadsByLine = threadsByLine()
        val pendingByLine = pendingByLine()

        // A pending comment now landing on this line (e.g. just submitted
        // elsewhere and re-fetched as a real thread) means the compose box
        // that created it is done — close it.
        newCommentBox?.let { (line, inlay) ->
            if (line in threadsByLine.keys || line in pendingByLine.keys) {
                if (inlay.isValid) inlay.dispose()
                newCommentBox = null
            }
        }

        for (lineIndex in 0 until editor.document.lineCount) {
            val pending = pendingByLine[lineIndex]
            val thread = threadsByLine[lineIndex]

            when {
                pending != null -> {
                    installLineHighlight(lineIndex)
                    pendingInlays[lineIndex] = InlineComponentHost.install(
                        editor, lineIndex,
                        PendingCommentBoxPanel(
                            pending = pending,
                            onDiscard = { commentContext.pendingStore.removeComment(pending) },
                            onResized = { pendingInlays[lineIndex]?.let(InlineComponentHost::relayout) },
                        ),
                    ) ?: continue
                }
                thread != null -> {
                    installLineHighlight(lineIndex)
                    threadInlays[lineIndex] = InlineComponentHost.install(
                        editor, lineIndex,
                        CommentThreadBoxPanel(
                            project, thread,
                            onReply = { text, onResult -> reply(thread, text, onResult) },
                            onResized = { threadInlays[lineIndex]?.let(InlineComponentHost::relayout) },
                        ),
                    ) ?: continue
                }
                newCommentBox?.first == lineIndex -> {
                    // Compose box already open here — leave it, just don't
                    // add a "+" for this line.
                }
                else -> {
                    val highlighter = editor.markupModel.addLineHighlighter(null, lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX)
                    highlighter.gutterIconRenderer = AddCommentGutterIconRenderer { toggleNewCommentBox(lineIndex) }
                    addGutterHighlighters += highlighter
                }
            }
        }
    }

    private fun installLineHighlight(lineIndex: Int) {
        val attributes = TextAttributes().apply { backgroundColor = COMMENTED_LINE_BACKGROUND }
        lineBackgroundHighlighters += editor.markupModel.addLineHighlighter(lineIndex, HighlighterLayer.ADDITIONAL_SYNTAX, attributes)
    }

    private fun toggleNewCommentBox(lineIndex: Int) {
        newCommentBox?.let { (line, inlay) ->
            if (inlay.isValid) inlay.dispose()
            newCommentBox = null
            if (line == lineIndex) {
                render()
                return
            }
        }

        val panel = NewCommentBoxPanel(
            // "Start Review" only when this is the first queued comment —
            // once a review is already in progress, saying "Start Review"
            // again on the second/third comment was misleading.
            startLabel = if (commentContext.pendingStore.pendingComments.isEmpty()) "Start Review" else "Add to Review",
            onAdd = { text ->
                val position = (lineIndex + 1).toLong()
                val comment = CreatePullReviewComment(
                    body = text,
                    path = commentContext.filename,
                    newPosition = if (!useOriginalPosition) position else 0,
                    oldPosition = if (useOriginalPosition) position else 0,
                )
                commentContext.pendingStore.addComment(comment)
            },
            onCancel = {
                newCommentBox?.let { (_, inlay) -> if (inlay.isValid) inlay.dispose() }
                newCommentBox = null
                render()
            },
            onResized = { newCommentBox?.second?.let(InlineComponentHost::relayout) },
        )
        val inlay = InlineComponentHost.install(editor, lineIndex, panel) ?: return
        newCommentBox = lineIndex to inlay
        render()
        panel.focusInput()
    }

    private fun reply(thread: List<PullReviewComment>, text: String, onResult: (Result<Unit>) -> Unit) {
        val anchorComment = thread.first()
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
                        updateComments(freshComments)
                        onResult(Result.success(Unit))
                        notify("Reply posted.", NotificationType.INFORMATION)
                    },
                    onFailure = { error -> onResult(Result.failure(error)) },
                )
            }
        }
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Gitea")
            .createNotification(text, type)
            .notify(project)
    }

    companion object {
        // A pink/magenta tint, not yellow — matching the reference
        // GitHub-plugin review-mode gutter marker
        // (gh_review_mark_gutter.png, docs/plan/pr-review-feature-plan.md
        // UI pass 3) rather than our original arbitrary color choice.
        private val COMMENTED_LINE_BACKGROUND = JBColor(Color(0xFC, 0xE8, 0xF3), Color(0x4A, 0x2A, 0x3A))
    }
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
