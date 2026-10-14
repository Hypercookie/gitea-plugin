package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.comments.avatarLabel
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
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.ListCellRenderer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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
        cellRenderer = PullRequestCellRenderer(project)
        emptyText.text = "No pull requests"
    }
    private val statusLabel = JBLabel(" ").apply { border = JBUI.Borders.empty(2, 8) }

    // Filter/sort bar (Phase 26) — mirrors the reference UI's search box +
    // State/Author/Sort dropdowns. State and Sort are server-side (Gitea's
    // `/pulls` query params, confirmed via /swagger.v1.json); search text and
    // Author filter the already-loaded page client-side — simpler than a
    // round trip per keystroke, and Gitea's `poster` param only takes an
    // exact login anyway, which the Author dropdown already gives us for
    // free from the loaded data. Label/Assignee dropdowns deferred to
    // Phase 33 (labels aren't decoded yet).
    private val searchField = JBTextField()
    private val stateCombo = JComboBox(arrayOf("Open", "Closed", "All"))
    private val authorCombo = JComboBox(DefaultComboBoxModel(arrayOf("All")))
    private val sortCombo = JComboBox(
        arrayOf(
            "Newest" to null,
            "Oldest" to "oldest",
            "Recently updated" to "recentupdate",
            "Least updated" to "leastupdate",
            "Most commented" to "mostcomment",
            "Least commented" to "leastcomment",
        ).map { it.first }.toTypedArray(),
    )
    private val sortValues = mapOf(
        "Newest" to null,
        "Oldest" to "oldest",
        "Recently updated" to "recentupdate",
        "Least updated" to "leastupdate",
        "Most commented" to "mostcomment",
        "Least commented" to "leastcomment",
    )

    private var allLoadedPrs: List<PullRequest> = emptyList()
    private var lastContext: GiteaRepoContext? = null
    private var lastClient: GiteaApiClient? = null

    private val currentBranchReviewPanel = CurrentBranchReviewPanel(project)

    val component = SimpleToolWindowPanel(true, true).apply {
        // Only one Refresh control in the whole tool window (it refreshes
        // the list AND the current-branch panel, see RefreshAction) — View
        // Details and Checkout live in the row context menu instead, not a
        // second/third toolbar button.
        val actionToolbar = ActionManager.getInstance()
            .createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, DefaultActionGroup(RefreshAction()), true)
        actionToolbar.targetComponent = prList
        toolbar = actionToolbar.component

        PopupHandler.installPopupMenu(prList, DefaultActionGroup(ViewDetailsAction(), CheckoutAction()), "GiteaPullRequestListPopup")

        setContent(
            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(
                    JBPanel<JBPanel<*>>(BorderLayout()).apply {
                        add(currentBranchReviewPanel.component, BorderLayout.NORTH)
                        add(buildFilterBar(), BorderLayout.CENTER)
                        add(statusLabel, BorderLayout.SOUTH)
                    },
                    BorderLayout.NORTH,
                )
                add(JBScrollPane(prList), BorderLayout.CENTER)
            },
        )
    }

    private fun buildFilterBar(): JBPanel<*> =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8)
            add(searchField.apply { emptyText.text = "Search title…" }, BorderLayout.NORTH)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
                    add(labeled("State", stateCombo))
                    add(labeled("Author", authorCombo))
                    add(labeled("Sort", sortCombo))
                },
                BorderLayout.CENTER,
            )
        }

    private fun labeled(text: String, combo: JComboBox<String>): JBPanel<*> =
        JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel(text))
            add(combo)
        }

    init {
        prList.addMouseListener(
            object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.clickCount == 2) prList.selectedValue?.let(::openDetailTab)
                }
            },
        )
        searchField.document.addDocumentListener(
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = applyFilters()
                override fun removeUpdate(e: DocumentEvent) = applyFilters()
                override fun changedUpdate(e: DocumentEvent) = applyFilters()
            },
        )
        authorCombo.addActionListener { applyFilters() }
        stateCombo.addActionListener { refresh() }
        sortCombo.addActionListener { refresh() }
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
        val state = (stateCombo.selectedItem as? String ?: "Open").lowercase()
        val sort = sortValues[sortCombo.selectedItem as? String]
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching { client.listPullRequests(context.owner, context.repo, state = state, sort = sort) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { prs ->
                        allLoadedPrs = prs
                        refreshAuthorOptions(prs)
                        applyFilters()
                        statusLabel.text = "${prs.size} pull request(s) in ${context.owner}/${context.repo}"
                    },
                    onFailure = { error ->
                        statusLabel.text = "Failed to load pull requests: ${error.message}"
                    },
                )
            }
        }
    }

    private fun refreshAuthorOptions(prs: List<PullRequest>) {
        val previouslySelected = authorCombo.selectedItem as? String ?: "All"
        val authors = prs.mapNotNull { it.user?.login }.distinct().sorted()
        val model = DefaultComboBoxModel((listOf("All") + authors).toTypedArray())
        authorCombo.model = model
        authorCombo.selectedItem = if (authors.contains(previouslySelected)) previouslySelected else "All"
    }

    private fun applyFilters() {
        val query = searchField.text.trim()
        val author = authorCombo.selectedItem as? String ?: "All"
        val filtered = allLoadedPrs.filter { pr ->
            (query.isEmpty() || pr.title.contains(query, ignoreCase = true)) &&
                (author == "All" || pr.user?.login == author)
        }
        listModel.clear()
        filtered.forEach(listModel::addElement)
    }

    /** Checks out `pr.head.ref` locally via `GitBrancher` (handles VFS
     * refresh; confirms first if the working tree is dirty — this is a
     * hard-to-reverse action on the user's tree). Once checked out,
     * [GiteaReviewSessionService][com.github.hypercookie.giteaplugin.pullrequest.review.GiteaReviewSessionService]
     * picks up the branch change and shows this PR's review in every open
     * editor — no further action needed here. */
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
        override fun actionPerformed(e: AnActionEvent) {
            refresh()
            currentBranchReviewPanel.refresh()
        }
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

    private inner class CheckoutAction : AnAction("Checkout", "Check out this pull request's branch locally", AllIcons.Vcs.Branch) {
        override fun actionPerformed(e: AnActionEvent) {
            prList.selectedValue?.let(::checkoutBranch)
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = prList.selectedValue != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.EDT
    }
}

/**
 * Two-line row (title bold, "#N · created DATE, by AUTHOR" subtitle below)
 * with a trailing state badge + author avatar — mirrors the reference
 * GitHub-plugin list row (`incoming_pull_requests.png` /
 * `pull_request_options.png`, see docs/plan/pr-review-feature-plan.md).
 * A plain `ListCellRenderer` building a real composed `JPanel`, not
 * `ColoredListCellRenderer` (single-line `SimpleColoredComponent` text) —
 * needed room for a second text line and a right-aligned avatar, neither of
 * which `ColoredListCellRenderer`'s single leading-icon-plus-text-run model
 * supports.
 */
private class PullRequestCellRenderer(private val project: Project) : ListCellRenderer<PullRequest> {
    override fun getListCellRendererComponent(
        list: JList<out PullRequest>,
        value: PullRequest,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 10)
            isOpaque = true
            background = if (isSelected) list.selectionBackground else list.background
        }
        val fg = if (isSelected) list.selectionForeground else list.foreground

        val title = JBLabel(value.title).apply { foreground = fg }
        val subtitle = JBLabel("#${value.number} · created ${formatShortDate(value.createdAt)}, by ${value.user?.login ?: "unknown"}").apply {
            foreground = if (isSelected) fg else JBColor.GRAY
            font = title.font.deriveFont(title.font.size2D - 1f)
        }
        val textColumn = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(title)
            add(subtitle)
        }
        row.add(textColumn, BorderLayout.CENTER)

        val (stateText, stateColor) = when {
            value.merged -> "merged" to MERGED_COLOR
            value.state == "closed" -> "closed" to CLOSED_COLOR
            value.draft -> "draft" to DRAFT_COLOR
            else -> "open" to OPEN_COLOR
        }
        val stateLabel = JBLabel(stateText).apply {
            foreground = if (isSelected) fg else stateColor
            font = title.font.deriveFont(java.awt.Font.BOLD)
        }
        val commentCount = (value.comments ?: 0) + (value.reviewComments ?: 0)
        val trailing = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(stateLabel)
            if (commentCount > 0) {
                add(JBLabel("$commentCount comments").apply { foreground = if (isSelected) fg else JBColor.GRAY })
            }
            add(avatarLabel(project, value.user))
        }
        row.add(trailing, BorderLayout.EAST)

        return row
    }

    companion object {
        private val OPEN_COLOR = JBColor(0x1A7F37, 0x3FB950)
        private val MERGED_COLOR = JBColor(0x8250DF, 0xA371F7)
        private val CLOSED_COLOR = JBColor(0xCF222E, 0xF85149)
        private val DRAFT_COLOR = JBColor.GRAY
    }
}

/** `2026-09-24T08:51:40+02:00` -> `2026-09-24`. */
private fun formatShortDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "?"
    return iso.substringBefore('T')
}
