package com.github.hypercookie.giteaplugin.pullrequest.timeline

import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullPushBody
import com.github.hypercookie.giteaplugin.api.dto.TimelineEvent
import com.github.hypercookie.giteaplugin.pullrequest.details.toDate
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaAvatarIcons
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.util.GiteaMarkdown
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.github.hypercookie.giteaplugin.util.giteaAction
import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.codereview.CodeReviewTimelineUIUtil
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentTextFieldFactory
import com.intellij.collaboration.ui.codereview.comment.CodeReviewSubmittableTextViewModelBase
import com.intellij.collaboration.ui.codereview.comment.CommentInputActionsComponentFactory
import com.intellij.collaboration.ui.codereview.timeline.comment.CommentTextFieldFactory
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Timeline content: description, comments, reviews, pushed commits and
 * other events, then a box for a new top-level comment. Items use the
 * platform's chat-item layout (`CodeReviewChatItemUIUtil`, FULL type for
 * comments, FULL_SECONDARY for events) like the GitHub plugin's timeline.
 *
 * Review threads are shown as the review's summary only; GitHub's inline
 * thread-with-diff-hunk rendering relies on the internal
 * `TimelineDiffComponentFactory` (migration ledger I11, not ported yet).
 */
object GiteaTimelineComponent {

    private val FULL = CodeReviewChatItemUIUtil.ComponentType.FULL
    private val SECONDARY = CodeReviewChatItemUIUtil.ComponentType.FULL_SECONDARY
    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface Loaded {
        data class Items(val events: List<TimelineEvent>, val commits: List<GitCommit>) : Loaded
        data class Error(val error: Throwable) : Loaded
        data object Loading : Loaded
    }

    fun create(cs: CoroutineScope, data: GiteaPullRequestReviewData): JComponent {
        val project = data.project
        val avatars = GiteaAvatarIcons.getInstance(project)
        val state = MutableStateFlow<Loaded>(Loaded.Loading)

        fun reload() {
            cs.launch {
                state.value = runCatching {
                    coroutineScope {
                        val events = async { data.client.getTimeline(data.owner, data.repoName, data.number) }
                        val commits = async { data.client.getPullRequestCommits(data.owner, data.repoName, data.number) }
                        Loaded.Items(events.await(), commits.await())
                    }
                }.getOrElse { Loaded.Error(it) }
            }
        }

        val itemsPanel = JPanel(ListLayout.vertical(CodeReviewTimelineUIUtil.VERTICAL_GAP)).apply { isOpaque = false }
        cs.bindOnEdt(state) { loaded ->
            itemsPanel.removeAll()
            itemsPanel.add(description(project, avatars, data))
            when (loaded) {
                Loaded.Loading -> itemsPanel.add(JBLabel("Loading…").apply { border = JBUI.Borders.empty(8) })
                is Loaded.Error -> itemsPanel.add(
                    JPanel(ListLayout.horizontal(8)).apply {
                        isOpaque = false
                        add(JBLabel("Can't load timeline items", AllIcons.General.Error, JBLabel.LEFT))
                        add(ActionLink("Retry") { reload() })
                    },
                )
                is Loaded.Items -> {
                    val commitsBySha = loaded.commits.associateBy { it.sha }
                    loaded.events.mapNotNull { item(project, avatars, it, commitsBySha) }.forEach(itemsPanel::add)
                }
            }
            itemsPanel.revalidate()
            itemsPanel.repaint()
        }
        reload()

        val newComment = newCommentField(cs, data, avatars) { reload() }
        val content = JPanel(ListLayout.vertical(0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(CodeReviewTimelineUIUtil.VERT_PADDING, 0)
            add(itemsPanel)
            add(newComment)
        }
        return ScrollPaneFactory.createScrollPane(content, true).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            viewport.background = UIUtil.getListBackground()
            background = UIUtil.getListBackground()
        }
    }

    private fun description(project: Project, avatars: GiteaAvatarIcons, data: GiteaPullRequestReviewData): JComponent {
        val pr = data.pullRequest.value
        val body = pr.body?.takeIf { it.isNotBlank() }?.let(GiteaMarkdown::toHtml) ?: "<i>No description provided</i>"
        return chatItem(avatars, FULL, pr.user, SimpleHtmlPane(body), titlePane(pr.user, null, pr.createdAt))
    }

    private fun item(project: Project, avatars: GiteaAvatarIcons, event: TimelineEvent, commits: Map<String, GitCommit>): JComponent? =
        when (event.type) {
            "comment" -> chatItem(avatars, FULL, event.user, SimpleHtmlPane(GiteaMarkdown.toHtml(event.body.orEmpty())), titlePane(event.user, null, event.createdAt))
            "review" -> {
                val verb = "reviewed the changes"
                val body = event.body?.takeIf { it.isNotBlank() }?.let { SimpleHtmlPane(GiteaMarkdown.toHtml(it)) }
                if (body != null) {
                    chatItem(avatars, FULL, event.user, body, titlePane(event.user, verb, event.createdAt))
                } else {
                    eventItem(avatars, event.user, verb, event.createdAt)
                }
            }
            "pull_push" -> {
                val push = event.body?.let { runCatching { json.decodeFromString<PullPushBody>(it) }.getOrNull() }
                val ids = push?.commitIds.orEmpty()
                val text = when {
                    push?.isForcePush == true -> "force-pushed the branch"
                    ids.size == 1 -> "added a commit to this pull request"
                    else -> "added ${ids.size} commits to this pull request"
                }
                val list = JPanel(ListLayout.vertical(2)).apply {
                    isOpaque = false
                    ids.forEach { sha ->
                        val headline = commits[sha]?.commit?.message?.lineSequence()?.first().orEmpty()
                        add(JBLabel("<html><code>${sha.take(7)}</code> ${escape(headline)}</html>"))
                    }
                }
                chatItem(avatars, SECONDARY, event.user, list, titlePane(event.user, text, event.createdAt))
            }
            "close" -> eventItem(avatars, event.user, "closed this", event.createdAt)
            "reopen" -> eventItem(avatars, event.user, "reopened this", event.createdAt)
            "merge_pull" -> eventItem(avatars, event.user, "merged this", event.createdAt)
            "change_title" -> eventItem(avatars, event.user, "renamed this pull request from '${escape(event.oldTitle.orEmpty())}' to '${escape(event.newTitle.orEmpty())}'", event.createdAt)
            "label" -> eventItem(avatars, event.user, "changed labels ${escape(event.label?.name.orEmpty())}", event.createdAt)
            "assignees" -> eventItem(avatars, event.user, "assigned ${escape(event.assignee?.login.orEmpty())}", event.createdAt)
            "review_request" -> eventItem(avatars, event.user, "requested a review", event.createdAt)
            "delete_branch" -> eventItem(avatars, event.user, "deleted the branch", event.createdAt)
            else -> eventItem(avatars, event.user, escape(event.type.replace('_', ' ')), event.createdAt)
        }

    private fun eventItem(avatars: GiteaAvatarIcons, user: GiteaUser?, text: String, date: String?): JComponent =
        chatItem(avatars, SECONDARY, user, JPanel().apply { isOpaque = false }, titlePane(user, text, date))

    private fun titlePane(user: GiteaUser?, action: String?, date: String?): JComponent {
        val name = user?.login ?: "unknown"
        val text = if (action != null) "<b>$name</b> $action" else "<b>$name</b>"
        val when_ = date.toDate()?.let { " <span style='color:gray'>${DateFormatUtil.formatPrettyDateTime(it)}</span>" }.orEmpty()
        return SimpleHtmlPane("$text$when_")
    }

    private fun chatItem(
        avatars: GiteaAvatarIcons,
        type: CodeReviewChatItemUIUtil.ComponentType,
        user: GiteaUser?,
        content: JComponent,
        title: JComponent,
    ): JComponent = CodeReviewChatItemUIUtil.build(type, { size -> avatars.getIcon(user, size) }, content) {
        iconTooltip = user?.login
        withHeader(title, null)
    }

    private class NewCommentViewModel(project: Project, cs: CoroutineScope, private val data: GiteaPullRequestReviewData, private val onPosted: () -> Unit) :
        CodeReviewSubmittableTextViewModelBase(project, cs, "") {
        fun post() = submit { body ->
            data.client.createIssueComment(data.owner, data.repoName, data.number, body)
            text.value = ""
            onPosted()
        }
    }

    private fun newCommentField(cs: CoroutineScope, data: GiteaPullRequestReviewData, avatars: GiteaAvatarIcons, onPosted: () -> Unit): JComponent {
        val vm = NewCommentViewModel(data.project, cs, data, onPosted)
        val config = CommentInputActionsComponentFactory.Config(
            primaryAction = MutableStateFlow(giteaAction(CollaborationToolsBundle.message("review.comment.submit")) { vm.post() }),
            secondaryActions = MutableStateFlow(emptyList()),
            additionalActions = MutableStateFlow(emptyList()),
            cancelAction = MutableStateFlow(null),
            submitHint = MutableStateFlow(CollaborationToolsBundle.message("review.comment.hint", CommentInputActionsComponentFactory.submitShortcutText)),
        )
        val icon = CommentTextFieldFactory.IconConfig.of(FULL, avatars, data.currentUser.value)
        return CodeReviewCommentTextFieldFactory.createIn(cs, vm, config, icon).apply {
            border = JBUI.Borders.empty(FULL.inputPaddingInsets)
        }.let { field ->
            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(field, BorderLayout.CENTER)
            }
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
