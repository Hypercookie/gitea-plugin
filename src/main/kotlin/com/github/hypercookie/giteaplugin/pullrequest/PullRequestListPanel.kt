package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JList

/**
 * "Pull Requests" tool window content: list open PRs for whatever Gitea repo
 * [GiteaRepoLinkService] resolves for this project. Built on
 * `SimpleToolWindowPanel` + a real `ActionToolbar` (not raw `JButton`s) —
 * the same primitives JetBrains' own tool windows use — per the user's
 * "modern IntelliJ-native UI" direction (docs/plan/pr-review-feature-plan.md,
 * "UI pass 2").
 *
 * Double-clicking a row checks out that PR's branch locally (per the
 * "Architecture pivot" — see Phase 16); the per-PR detail tab (overview +
 * diff viewer) is reachable via the toolbar's "View Details" action instead.
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
        emptyText.text = "No pull requests"
    }
    private val statusLabel = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }

    private var lastContext: GiteaRepoContext? = null
    private var lastClient: GiteaApiClient? = null

    private val currentBranchReviewPanel = CurrentBranchReviewPanel(project)

    val component = SimpleToolWindowPanel(true, true).apply {
        val actionGroup = DefaultActionGroup(RefreshAction(), ViewDetailsAction())
        val actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
        actionToolbar.targetComponent = prList
        toolbar = actionToolbar.component

        setContent(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(currentBranchReviewPanel.component, BorderLayout.NORTH)
                add(
                    JBPanel<JBPanel<*>>(BorderLayout()).apply {
                        add(statusLabel, BorderLayout.NORTH)
                        add(JBScrollPane(prList), BorderLayout.CENTER)
                    },
                    BorderLayout.CENTER,
                )
            },
        )
    }

    init {
        prList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) prList.selectedValue?.let(::checkoutBranch)
                }
            },
        )
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

    private inner class RefreshAction : AnAction("Refresh", "Reload pull requests", AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) = refresh()
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }

    private inner class ViewDetailsAction : AnAction("View Details", "Open the selected pull request's detail tab", AllIcons.Actions.Preview) {
        override fun actionPerformed(e: AnActionEvent) {
            prList.selectedValue?.let(::openDetailTab)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = prList.selectedValue != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
}

private class PullRequestCellRenderer : ColoredListCellRenderer<PullRequest>() {
    override fun customizeCellRenderer(
        list: JList<out PullRequest>,
        value: PullRequest?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val pr = value ?: return

        icon = when {
            pr.merged -> AllIcons.Vcs.Merge
            pr.state == "closed" -> AllIcons.General.Error
            else -> AllIcons.Vcs.Branch
        }
        ipad = JBUI.insets(4, 8)

        append("#${pr.number}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(pr.title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("   ")

        val (stateText, stateColor) = when {
            pr.merged -> "merged" to MERGED_COLOR
            pr.state == "closed" -> "closed" to CLOSED_COLOR
            pr.draft -> "draft" to DRAFT_COLOR
            else -> "open" to OPEN_COLOR
        }
        append(stateText, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, stateColor))
        append("  ${pr.user?.login ?: "unknown"}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
    }

    companion object {
        private val OPEN_COLOR = JBColor(0x1A7F37, 0x3FB950)
        private val MERGED_COLOR = JBColor(0x8250DF, 0xA371F7)
        private val CLOSED_COLOR = JBColor(0xCF222E, 0xF85149)
        private val DRAFT_COLOR = JBColor.GRAY
    }
}
