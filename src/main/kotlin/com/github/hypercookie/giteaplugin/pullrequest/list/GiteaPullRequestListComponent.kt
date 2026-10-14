package com.github.hypercookie.giteaplugin.pullrequest.list

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaAvatarIcons
import com.github.hypercookie.giteaplugin.pullrequest.toolwindow.GiteaProjectViewModel
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.intellij.collaboration.ui.codereview.avatar.Avatar
import com.intellij.collaboration.ui.codereview.list.NamedCollection
import com.intellij.collaboration.ui.codereview.list.ReviewListComponentFactory
import com.intellij.collaboration.ui.codereview.list.ReviewListItemPresentation
import com.intellij.collaboration.ui.codereview.list.ReviewListUtil
import com.intellij.collaboration.ui.codereview.list.TagPresentation
import com.intellij.collaboration.ui.codereview.list.UserPresentation
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CompositeShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColorUtil
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBList
import git4idea.repo.GitRepositoryManager
import icons.CollaborationToolsIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.util.Date
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * Pull request list tab: search bar, lazily paged list, context menu.
 * Mirrors the GitHub plugin's `GHPRListPanelFactory` / `GHPRListComponentFactory`
 * (row rendering is the platform's `ReviewListComponentFactory`).
 */
object GiteaPullRequestListComponent {

    fun create(cs: CoroutineScope, projectVm: GiteaProjectViewModel): JComponent {
        val vm = projectVm.listVm
        val avatars = GiteaAvatarIcons.getInstance(projectVm.project)

        val listModel = CollectionListModel<GiteaPullRequestListItem>()
        cs.bindOnEdt(vm.items) { items ->
            if (listModel.items != items) listModel.replaceAll(items)
        }
        val list = ReviewListComponentFactory(listModel).create { present(avatars, it) }

        cs.bindOnEdt(combine(vm.isLoading, vm.error, vm.items) { loading, error, items -> Triple(loading, error, items) }) { (loading, error, items) ->
            val empty = list.emptyText
            empty.clear()
            when {
                loading -> empty.appendText("Loading…")
                error != null -> {
                    empty.appendText("Can't load pull requests")
                    empty.appendSecondaryText("Retry", com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { vm.reload() }
                }
                items.isEmpty() && projectVm.searchVm.searchState.value != projectVm.searchVm.emptySearch -> {
                    empty.appendText("No pull requests matching filters.")
                    empty.appendSecondaryText("Clear filters", com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                        projectVm.searchVm.searchState.value = projectVm.searchVm.emptySearch
                    }
                }
                items.isEmpty() -> empty.appendText("No pull requests in ${projectVm.projectName}")
            }
        }

        installActions(cs, projectVm, list)

        val searchPanel = GiteaSearchPanelFactory(projectVm.searchVm, avatars).create(cs)
        val scroll = ReviewListUtil.wrapWithLazyVerticalScroll(cs, list) { vm.requestMore() }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(searchPanel, BorderLayout.NORTH)
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun installActions(cs: CoroutineScope, projectVm: GiteaProjectViewModel, list: JBList<GiteaPullRequestListItem>) {
        val open = action("View Pull Request", AllIcons.Actions.Preview, list) { item ->
            projectVm.openPullRequest(item.number, item.title)
        }
        val checkout = action("Checkout", AllIcons.Vcs.Branch, list) { item ->
            val manager = GitRepositoryManager.getInstance(projectVm.project)
            val repository = projectVm.repo.gitRoot?.let { root -> manager.repositories.find { it.root == root } }
                ?: manager.repositories.firstOrNull() ?: return@action
            // The issues endpoints don't return the head branch, so fetch the
            // pull request first.
            cs.launch {
                val pr = runCatching { projectVm.client.getPullRequest(projectVm.repo.owner, projectVm.repo.repo, item.number) }.getOrNull()
                    ?: return@launch
                withContext(Dispatchers.Main) { GiteaPullRequestActions.checkout(projectVm.project, repository, pr.head.ref) }
            }
        }
        val openInBrowser = action("Open in Browser", AllIcons.General.Web, list) { GiteaPullRequestActions.openInBrowser(it.htmlUrl) }
        val copyLink = action("Copy Link", AllIcons.Actions.Copy, list) { GiteaPullRequestActions.copyLink(it.htmlUrl) }
        val refresh = object : DumbAwareAction("Refresh List", null, AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) = projectVm.listVm.reload()
        }
        PopupHandler.installPopupMenu(
            list,
            DefaultActionGroup(open, checkout, openInBrowser, copyLink, Separator.getInstance(), refresh),
            "GiteaPullRequestListPopup",
        )
        open.registerCustomShortcutSet(CompositeShortcutSet(CommonShortcuts.ENTER, CommonShortcuts.DOUBLE_CLICK_1), list)
    }

    private fun action(
        text: String,
        icon: javax.swing.Icon,
        list: JBList<GiteaPullRequestListItem>,
        perform: (GiteaPullRequestListItem) -> Unit,
    ) = object : DumbAwareAction(text, null, icon) {
        override fun actionPerformed(e: AnActionEvent) {
            list.selectedValue?.let(perform)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = list.selectedValue != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private fun present(avatars: GiteaAvatarIcons, item: GiteaPullRequestListItem): ReviewListItemPresentation =
        ReviewListItemPresentation.Simple(
            title = item.title,
            id = "#${item.number}",
            createdDate = item.createdAt.toDate() ?: Date(0),
            author = item.author?.let { user(avatars, it) },
            tagGroup = NamedCollection.create(
                if (item.labels.size == 1) "Label" else "Labels",
                item.labels.map { TagPresentation.Simple(it.name, it.color?.let { hex -> runCatching { ColorUtil.fromHex(hex) }.getOrNull() }) },
            ),
            mergeableStatus = if (item.mergeable == false) {
                ReviewListItemPresentation.Status(CollaborationToolsIcons.Review.NonMergeable, "Pull request cannot be merged")
            } else {
                null
            },
            buildStatus = null,
            state = when {
                item.merged -> "Merged"
                item.state == "closed" -> "Closed"
                item.draft -> "Draft"
                else -> null
            },
            userGroup1 = NamedCollection.create(if (item.assignees.size == 1) "Assignee" else "Assignees", item.assignees.map { user(avatars, it) }),
            userGroup2 = NamedCollection.create(if (item.reviewers.size == 1) "Reviewer" else "Reviewers", item.reviewers.map { user(avatars, it) }),
            commentsCounter = ReviewListItemPresentation.CommentsCounter(
                item.comments,
                if (item.comments == 1) "1 comment" else "${item.comments} comments",
            ),
            seen = null,
        )

    private fun user(avatars: GiteaAvatarIcons, user: GiteaUser): UserPresentation =
        UserPresentation.Simple(user.login, user.fullName?.takeIf { it.isNotBlank() }, avatars.getIcon(user, Avatar.Sizes.BASE))

    private fun String?.toDate(): Date? = this?.let { runCatching { Date.from(OffsetDateTime.parse(it).toInstant()) }.getOrNull() }
}
