package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * "Pull Requests" tool window content: list open PRs for whatever Gitea repo
 * [GiteaRepoLinkService] resolves for this project, with a manual refresh.
 * Double-clicking a row checks out that PR's branch locally (per the
 * "Architecture pivot" — see docs/plan/pr-review-feature-plan.md Phase 16);
 * the per-PR detail tab (overview + diff viewer) is reachable via the
 * separate "View Details" button instead.
 *
 * Known v1 limitation: checkout targets `pr.head.ref` directly, which only
 * resolves cleanly for same-repo PRs (branch already exists locally or as
 * an `origin/<ref>` remote-tracking branch) — fork-based PRs aren't handled
 * yet.
 */
class PullRequestListPanel(private val project: Project, private val toolWindow: ToolWindow) {

    private val listModel = DefaultListModel<PullRequest>()
    private val prList = JBList(listModel).apply {
        cellRenderer = PullRequestCellRenderer()
    }
    private val statusLabel = JBLabel(" ")

    private var lastContext: GiteaRepoContext? = null
    private var lastClient: GiteaApiClient? = null

    private val currentBranchReviewPanel = CurrentBranchReviewPanel(project)

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(JButton("View Details").apply { addActionListener { prList.selectedValue?.let(::openDetailTab) } })
            add(statusLabel)
        }
        add(currentBranchReviewPanel.component, BorderLayout.NORTH)
        val listPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(prList), BorderLayout.CENTER)
        }
        add(listPanel, BorderLayout.CENTER)
    }

    init {
        prList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) prList.selectedValue?.let(::checkoutBranch)
            }
        })
        refresh()
    }

    fun refresh() {
        val context = GiteaRepoLinkService.getInstance(project).resolveDefault()
        if (context == null) {
            listModel.clear()
            statusLabel.text = "No linked Gitea repository — configure an account in " +
                "Settings → Version Control → Gitea, and make sure a git remote points at it."
            return
        }

        val token = GiteaAccountManager.getInstance().getToken(context.account)
        if (token.isNullOrBlank()) {
            statusLabel.text = "No token saved for account \"${context.account.name}\" — check Settings → Version Control → Gitea."
            return
        }

        statusLabel.text = "Loading…"
        val client = GiteaApiClient(context.account.serverUrl, token)
        lastContext = context
        lastClient = client
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching { client.listPullRequests(context.owner, context.repo) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { prs ->
                        listModel.clear()
                        prs.forEach(listModel::addElement)
                        statusLabel.text = "${prs.size} open pull request(s) in ${context.owner}/${context.repo}"
                    },
                    onFailure = { error ->
                        statusLabel.text = "Failed to load pull requests: ${error.message}"
                    },
                )
            }
        }
    }

    /** Checks out `pr.head.ref` locally via `GitBrancher` (handles VFS
     * refresh; confirms first if the working tree is dirty — this is a
     * hard-to-reverse action on the user's tree). Once checked out,
     * [WorkingCopyCommentEditorListener][com.github.hypercookie.giteaplugin.pullrequest.comments.WorkingCopyCommentEditorListener]
     * picks up this PR's comments automatically on any file opened from
     * that branch — no further action needed here. */
    private fun checkoutBranch(pr: PullRequest) {
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        if (repo == null) {
            statusLabel.text = "No git repository found for this project."
            return
        }

        val dirty = ChangeListManager.getInstance(project).allChanges.isNotEmpty()
        if (dirty) {
            val confirmed = Messages.showYesNoDialog(
                project,
                "You have uncommitted changes. Check out branch \"${pr.head.ref}\" anyway?",
                "Checkout Branch",
                "Checkout",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (confirmed != Messages.YES) return
        }

        statusLabel.text = "Checking out \"${pr.head.ref}\"…"
        GitBrancher.getInstance(project).checkout(pr.head.ref, false, listOf(repo)) {
            statusLabel.text = "Checked out \"${pr.head.ref}\" (PR #${pr.number})."
        }
    }

    private fun openDetailTab(pr: PullRequest) {
        val context = lastContext ?: return
        val client = lastClient ?: return

        val contentManager = toolWindow.contentManager
        val tabName = "#${pr.number}"
        contentManager.contents.find { it.tabName == tabName }?.let {
            contentManager.setSelectedContent(it)
            return
        }

        val detail = PullRequestDetailPanel(project, context, client, pr)
        val content = ContentFactory.getInstance().createContent(detail.component, tabName, false)
        content.isCloseable = true
        contentManager.addContent(content)
        contentManager.setSelectedContent(content)
    }
}

private class PullRequestCellRenderer : ListCellRenderer<PullRequest> {
    private val label = JBLabel()

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>,
        value: PullRequest?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        label.text = value?.let { "#${it.number}  ${it.title}  — ${it.user?.login ?: "unknown"} [${it.state}]" } ?: ""
        label.isOpaque = true
        label.background = if (isSelected) list.selectionBackground else list.background
        label.foreground = if (isSelected) list.selectionForeground else list.foreground
        return label
    }
}
