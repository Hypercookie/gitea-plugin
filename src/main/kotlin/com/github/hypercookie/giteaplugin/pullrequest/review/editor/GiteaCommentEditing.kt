package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaCommentCodeBlockFilter
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.mention.GiteaMentionSource
import com.github.hypercookie.giteaplugin.util.bindVisibility
import com.github.hypercookie.giteaplugin.util.giteaAction
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.github.hypercookie.giteaplugin.util.hideNewLineHint
import com.github.hypercookie.giteaplugin.util.submitAction
import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentTextFieldFactory
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewSubmittableTextViewModelBase
import com.intellij.collaboration.ui.codereview.comment.CommentInputActionsComponentFactory
import com.intellij.collaboration.ui.codereview.timeline.comment.CommentTextFieldFactory
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import javax.swing.JComponent
import javax.swing.JPanel
import com.intellij.ui.components.panels.ListLayout

/** Text field of one comment being edited, saving to Gitea. */
internal class GiteaEditCommentViewModel(
    cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
    private val commentId: Long,
    private val original: String,
    initialText: String,
    private val onDone: () -> Unit,
) : CodeReviewSubmittableTextViewModelBase(data.project, cs, initialText) {

    fun save() = submit { body ->
        if (body != original) {
            // Before the request: the comment is drawn again once it is
            // saved, and must not reopen its editor then.
            data.commentEdits.finish(commentId)
            data.editComment(commentId, body)
        }
        onDone()
    }

    fun cancel() = onDone()
}

/**
 * Lets a comment's author edit it in place: an edit button for the comment's
 * header, and the comment's body swapped for a text field while it is being
 * edited. Only the signed-in user's own comments can be edited here.
 */
internal object GiteaCommentEditing {

    /**
     * [content] shows [view] (the comment's rendered body) or, while
     * editing, the text field instead; [editButton] starts editing and is
     * visible on the signed-in user's own comments only.
     */
    class Editable(val content: JComponent, val editButton: JComponent)

    fun editable(
        cs: CoroutineScope,
        data: GiteaPullRequestReviewData,
        commentId: Long,
        author: GiteaUser?,
        body: String,
        view: JComponent,
    ): Editable {
        val canEdit: StateFlow<Boolean> = data.currentUser.map { user -> isOwn(user, author) }
            .stateIn(cs, SharingStarted.Eagerly, isOwn(data.currentUser.value, author))
        val content = JPanel(ListLayout.vertical(0)).apply {
            isOpaque = false
            add(view)
        }
        var editor: JComponent? = null
        var editorCs: CoroutineScope? = null

        fun stop() {
            editorCs?.cancel()
            editorCs = null
            editor?.let(content::remove)
            editor = null
            data.commentEdits.finish(commentId)
            view.isVisible = true
            content.revalidate()
            content.repaint()
        }

        fun start(text: String) {
            if (editor != null) return
            val ecs = cs.giteaChildScope("Gitea comment editor").also { editorCs = it }
            data.commentEdits.update(commentId, text)
            val vm = GiteaEditCommentViewModel(ecs, data, commentId, body, text) { UIUtil.invokeLaterIfNeeded { stop() } }
            ecs.launch { vm.text.collect { data.commentEdits.update(commentId, it) } }
            val field = createField(ecs, data, vm)
            editor = field
            view.isVisible = false
            content.add(field)
            content.revalidate()
            content.repaint()
            vm.requestFocus()
        }

        val editButton = CodeReviewCommentUIUtil.createEditButton { start(body) }.apply { bindVisibility(cs, canEdit) }
        // Drawn again while being edited: carry on where the old editor was.
        data.commentEdits.draft(commentId)?.takeIf { canEdit.value }?.let(::start)
        return Editable(content, editButton)
    }

    private fun isOwn(user: GiteaUser?, author: GiteaUser?) = user != null && author != null && user.id == author.id

    private fun createField(cs: CoroutineScope, data: GiteaPullRequestReviewData, vm: GiteaEditCommentViewModel): JComponent {
        val config = CommentInputActionsComponentFactory.Config(
            primaryAction = MutableStateFlow(vm.submitAction(cs, CollaborationToolsBundle.message("review.comment.save")) { vm.save() }),
            secondaryActions = MutableStateFlow(emptyList()),
            additionalActions = MutableStateFlow(emptyList()),
            cancelAction = MutableStateFlow(giteaAction("") { vm.cancel() }),
            submitHint = MutableStateFlow(
                CollaborationToolsBundle.message("review.comment.save.hint", CommentInputActionsComponentFactory.submitShortcutText),
            ),
        )
        // The comment shows its author's avatar already.
        val icon = CommentTextFieldFactory.IconConfig(EmptyIcon.ICON_0, 0)
        return CodeReviewCommentTextFieldFactory.createIn(cs, vm, config, icon) {
            GiteaCommentCodeBlockFilter.mark(it)
            GiteaMentionSource.install(it, data.mentions)
        }.hideNewLineHint()
    }
}
