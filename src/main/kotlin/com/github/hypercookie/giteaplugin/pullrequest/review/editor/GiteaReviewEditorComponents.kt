package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.pullrequest.reactions.GiteaReactable
import com.github.hypercookie.giteaplugin.pullrequest.reactions.GiteaReactionsComponentFactory
import com.github.hypercookie.giteaplugin.pullrequest.reactions.GiteaReactionsViewModelImpl
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaAvatarIcons
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaNewCommentViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaThreadViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.mention.GiteaMentionSource
import com.github.hypercookie.giteaplugin.util.GiteaMarkdown
import com.github.hypercookie.giteaplugin.util.bindEnabled
import com.github.hypercookie.giteaplugin.util.bindName
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.github.hypercookie.giteaplugin.util.bindVisibility
import com.github.hypercookie.giteaplugin.util.giteaAction
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.CollaborationToolsUIUtil
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.CodeReviewTimelineUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentTextFieldFactory
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.comment.CommentInputActionsComponentFactory
import com.intellij.collaboration.ui.codereview.timeline.comment.CommentTextFieldFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.OffsetDateTime
import java.util.Date
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Swing components for review inlays. Mirrors the GitHub plugin's
 * `GHPRReviewEditorComponentsFactory`, `GHPRReviewThreadCommentComponentFactory`
 * and `GHPRReviewThreadComponentFactory` (intellij-community, Apache 2.0,
 * build 252.28539.54), built only from public collaboration-tools API.
 */
object GiteaReviewEditorComponents {

    private val COMPACT = CodeReviewChatItemUIUtil.ComponentType.COMPACT

    fun create(cs: CoroutineScope, project: Project, inlay: GiteaEditorInlay): JComponent = when (inlay) {
        is GiteaEditorInlay.Thread -> createThread(cs, project, inlay)
        is GiteaEditorInlay.Pending -> createPending(cs, project, inlay)
        is GiteaEditorInlay.NewComment -> createNewComment(cs, project, inlay.vm)
    }

    // --- Thread ------------------------------------------------------------

    private fun createThread(cs: CoroutineScope, project: Project, inlay: GiteaEditorInlay.Thread): JComponent {
        val vm = inlay.vm
        val avatars = GiteaAvatarIcons.getInstance(project)
        val commentsPanel = verticalPanel(0)
        val comments = vm.thread.map { it.comments }.distinctUntilChanged()
        // Comments are rebuilt on every change; their bindings (reaction
        // pills) live in a scope that is replaced with them.
        var commentsCs: CoroutineScope? = null
        cs.bindOnEdt(combine(comments, inlay.outdatedState) { c, outdated -> c to outdated }) { (comments, outdated) ->
            commentsCs?.cancel()
            val ccs = cs.giteaChildScope("Gitea thread comments").also { commentsCs = it }
            commentsPanel.removeAll()
            comments.forEachIndexed { index, comment ->
                val first = index == 0
                commentsPanel.add(
                    createComment(
                        ccs,
                        project,
                        vm.data,
                        comment,
                        firstInResolvedThread = first && vm.thread.value.isResolved,
                        firstInOutdatedThread = first && outdated,
                    ),
                )
            }
            commentsPanel.revalidate()
            commentsPanel.repaint()
        }

        val collapsedRow = createCollapsedRow(vm.thread.value.comments.first().user, inlay)
        val replyActions = createReplyActionsPanel(cs, inlay).apply {
            border = JBUI.Borders.empty(8, COMPACT.fullLeftShift)
        }
        val replyField = createReplyField(cs, vm, avatars)

        val content = verticalPanel(0).apply {
            name = "Gitea Thread in Editor Panel ${vm.id}"
            border = JBUI.Borders.empty(CodeReviewCommentUIUtil.getInlayPadding(COMPACT))
            add(collapsedRow)
            add(commentsPanel)
            add(replyActions)
            add(replyField)
        }
        cs.bindOnEdt(combine(vm.isWritingReply, inlay.collapsedState) { writing, collapsed -> writing to collapsed }) { (writing, collapsed) ->
            collapsedRow.isVisible = collapsed
            commentsPanel.isVisible = !collapsed
            replyActions.isVisible = !collapsed && !writing
            replyField.isVisible = !collapsed && writing
            content.revalidate()
        }
        return CodeReviewCommentUIUtil.createEditorInlayPanel(content)
    }

    /** Stand-in for a collapsed outdated comment: author, OUTDATED tag and
     * a link that expands it. Same tag as the GitHub plugin's outdated
     * threads (`GHPRTimelineThreadComponentFactory`). */
    private fun createCollapsedRow(author: GiteaUser?, inlay: GiteaEditorInlay): JComponent =
        horizontalPanel(CodeReviewCommentUIUtil.Title.HORIZONTAL_GAP).apply {
            if (author != null) add(CodeReviewTimelineUIUtil.createTitleTextPane(author.presentableName(), null, null))
            add(outdatedTag())
            add(ActionLink("Show outdated comment") { inlay.collapsedState.value = false }.apply { isFocusPainted = false })
        }

    private fun outdatedTag(): JComponent =
        CollaborationToolsUIUtil.createTagLabel(CollaborationToolsBundle.message("review.thread.outdated.tag"))

    /** One comment of a thread: body with reaction pills below and the add
     * reaction button in the header, as the GitHub plugin's
     * `GHPRReviewThreadCommentComponentFactory` builds it. */
    private fun createComment(
        cs: CoroutineScope,
        project: Project,
        data: GiteaPullRequestReviewData,
        comment: PullReviewComment,
        firstInResolvedThread: Boolean,
        firstInOutdatedThread: Boolean,
    ): JComponent {
        val avatars = GiteaAvatarIcons.getInstance(project)
        val reactionsVm = GiteaReactionsViewModelImpl(cs, GiteaReactable.Comment(comment.id), data.currentUser, data.reactions)
        val body = verticalPanel(CodeReviewTimelineUIUtil.VERTICAL_GAP).apply {
            add(GiteaMarkdown.createPane(GiteaMarkdown.toHtml(comment.body)))
            add(GiteaReactionsComponentFactory.create(cs, reactionsVm))
        }
        val author = comment.user
        return CodeReviewChatItemUIUtil.build(COMPACT, { size -> avatars.getIcon(author, size) }, body) {
            iconTooltip = author?.presentableName()
            maxContentWidth = null
            val title = horizontalPanel(CodeReviewCommentUIUtil.Title.HORIZONTAL_GAP).apply {
                add(CodeReviewTimelineUIUtil.createTitleTextPane(author?.presentableName() ?: "unknown", author?.htmlUrl(), comment.createdAt.toDate()))
                if (firstInOutdatedThread) add(outdatedTag())
                if (firstInResolvedThread) {
                    add(CollaborationToolsUIUtil.createTagLabel(CollaborationToolsBundle.message("review.thread.resolved.tag")))
                }
            }
            val actions = horizontalPanel(CodeReviewCommentUIUtil.Actions.HORIZONTAL_GAP).apply {
                add(GiteaReactionsComponentFactory.createAddReactionButton(cs, reactionsVm))
            }
            withHeader(title, actions)
        }
    }

    private fun createReplyActionsPanel(cs: CoroutineScope, inlay: GiteaEditorInlay.Thread): JComponent {
        val vm = inlay.vm
        val collapseLink = createCollapseLink(cs, inlay)
        val replyLink = ActionLink(CollaborationToolsBundle.message("review.comments.reply.action")) { vm.startWritingReply() }.apply {
            isFocusPainted = false
        }
        val resolveLink = ActionLink(createResolveAction(cs, vm)).apply {
            autoHideOnDisable = false
            bindVisibility(cs, vm.canChangeResolvedState)
        }
        return horizontalPanel(CodeReviewTimelineUIUtil.Thread.Replies.ActionsFolded.HORIZONTAL_GROUP_GAP).apply {
            add(replyLink)
            add(resolveLink)
            add(collapseLink)
        }
    }

    /** Collapses an expanded outdated comment again; hidden while current. */
    private fun createCollapseLink(cs: CoroutineScope, inlay: GiteaEditorInlay): JComponent =
        ActionLink("Hide outdated comment") { inlay.collapsedState.value = true }.apply {
            isFocusPainted = false
            bindVisibility(cs, inlay.outdatedState)
        }

    private fun createResolveAction(cs: CoroutineScope, vm: GiteaThreadViewModel): Action =
        giteaAction(CodeReviewCommentUIUtil.getResolveToggleActionText(vm.isResolved.value)) { vm.changeResolvedState() }.apply {
            bindEnabled(cs, vm.isBusy.map { !it })
            bindName(cs, vm.isResolved.map(CodeReviewCommentUIUtil::getResolveToggleActionText))
        }

    private fun createReplyField(cs: CoroutineScope, vm: GiteaThreadViewModel, avatars: GiteaAvatarIcons): JComponent {
        val replyVm = vm.replyVm
        val resolveAction = createResolveAction(cs, vm)
        val config = CommentInputActionsComponentFactory.Config(
            primaryAction = MutableStateFlow(giteaAction(CollaborationToolsBundle.message("review.comments.reply.action")) { replyVm.submitReply() }),
            secondaryActions = MutableStateFlow(emptyList()),
            additionalActions = MutableStateFlow(listOf(resolveAction)),
            cancelAction = MutableStateFlow(giteaAction("") { vm.stopWritingReply() }),
            submitHint = MutableStateFlow(
                CollaborationToolsBundle.message("review.comments.reply.hint", CommentInputActionsComponentFactory.submitShortcutText),
            ),
        )
        val icon = CommentTextFieldFactory.IconConfig.of(COMPACT, avatars, replyVm.currentUser.value)
        return CodeReviewCommentTextFieldFactory.createIn(cs, replyVm, config, icon) { GiteaMentionSource.install(it, vm.data.mentions) }.let {
            CollaborationToolsUIUtil.wrapWithLimitedSize(it, maxWidth = CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH + COMPACT.contentLeftShift)
        }.apply {
            border = JBUI.Borders.empty(COMPACT.inputPaddingInsets)
            isVisible = false
        }
    }

    // --- Pending comment ---------------------------------------------------

    private fun createPending(cs: CoroutineScope, project: Project, inlay: GiteaEditorInlay.Pending): JComponent {
        val avatars = GiteaAvatarIcons.getInstance(project)
        val body = GiteaMarkdown.createPane(GiteaMarkdown.toHtml(inlay.comment.body))
        val author = inlay.author.value
        val item = CodeReviewChatItemUIUtil.build(COMPACT, { size -> avatars.getIcon(author, size) }, body) {
            iconTooltip = author?.presentableName()
            maxContentWidth = null
            val title = horizontalPanel(CodeReviewCommentUIUtil.Title.HORIZONTAL_GAP).apply {
                // Author like a posted comment, plus the PENDING tag (GitHub shows the same).
                if (author != null) add(CodeReviewTimelineUIUtil.createTitleTextPane(author.presentableName(), null, null))
                add(CollaborationToolsUIUtil.createTagLabel(CollaborationToolsBundle.message("review.thread.pending.tag")))
                add(outdatedTag().apply { bindVisibility(cs, inlay.outdatedState) })
            }
            val actions = horizontalPanel(CodeReviewCommentUIUtil.Actions.HORIZONTAL_GAP).apply {
                add(CodeReviewCommentUIUtil.createDeleteCommentIconButton { inlay.onDiscard() })
            }
            withHeader(title, actions)
        }
        val collapsedRow = createCollapsedRow(author, inlay)
        val collapseActions = horizontalPanel(0).apply {
            border = JBUI.Borders.empty(8, COMPACT.fullLeftShift, 0, 0)
            add(createCollapseLink(cs, inlay))
        }
        val content = verticalPanel(0).apply {
            border = JBUI.Borders.empty(CodeReviewCommentUIUtil.getInlayPadding(COMPACT))
            add(collapsedRow)
            add(item)
            add(collapseActions)
        }
        cs.bindOnEdt(combine(inlay.outdatedState, inlay.collapsedState) { outdated, collapsed -> outdated to collapsed }) { (outdated, collapsed) ->
            collapsedRow.isVisible = collapsed
            item.isVisible = !collapsed
            collapseActions.isVisible = outdated && !collapsed
            content.revalidate()
        }
        return CodeReviewCommentUIUtil.createEditorInlayPanel(content)
    }

    // --- New comment -------------------------------------------------------

    private fun createNewComment(cs: CoroutineScope, project: Project, vm: GiteaNewCommentViewModel): JComponent {
        val avatars = GiteaAvatarIcons.getInstance(project)
        val primary = vm.hasPendingReview.map { pending ->
            // Strings from the GitHub plugin's bundle (pull.request.review.editor.*).
            giteaAction(if (pending) "Add Review Comment" else "Start Review") { vm.addToReview() }
        }.stateIn(cs, SharingStarted.Eagerly, null)
        val secondary = MutableStateFlow(listOf(giteaAction("Add Single Comment") { vm.postSingle() }))
        val cancel = giteaAction("") {
            if (vm.text.value.isBlank() ||
                MessageDialogBuilder.yesNo(
                    CollaborationToolsBundle.message("review.comments.discard.new.confirmation.title"),
                    CollaborationToolsBundle.message("review.comments.discard.new.confirmation"),
                ).ask(project)
            ) {
                vm.cancel()
            }
        }
        val config = CommentInputActionsComponentFactory.Config(
            primaryAction = primary,
            secondaryActions = secondary,
            additionalActions = MutableStateFlow(emptyList()),
            cancelAction = MutableStateFlow(cancel),
            submitHint = MutableStateFlow(
                CollaborationToolsBundle.message("review.comment.hint", CommentInputActionsComponentFactory.submitShortcutText),
            ),
        )
        val icon = CommentTextFieldFactory.IconConfig.of(COMPACT, avatars, vm.currentUser.value)
        val editor = CodeReviewCommentTextFieldFactory.createIn(cs, vm, config, icon) { GiteaMentionSource.install(it, vm.data.mentions) }.apply {
            border = JBUI.Borders.empty(COMPACT.inputPaddingInsets)
        }
        return CodeReviewCommentUIUtil.createEditorInlayPanel(editor)
    }

    // --- helpers -----------------------------------------------------------

    private fun verticalPanel(gap: Int) = JPanel(ListLayout.vertical(gap)).apply { isOpaque = false }

    private fun horizontalPanel(gap: Int) = JPanel(ListLayout.horizontal(gap)).apply { isOpaque = false }

    private fun GiteaUser.presentableName(): String = fullName?.takeIf { it.isNotBlank() } ?: login

    private fun GiteaUser.htmlUrl(): String? = null

    private fun String?.toDate(): Date? = this?.let { runCatching { Date.from(OffsetDateTime.parse(it).toInstant()) }.getOrNull() }
}
