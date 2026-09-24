package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton

/**
 * Tool window panel bound to "the PR for whatever branch is currently
 * checked out" — lets the user submit a review (Approve/Comment/Request
 * Changes) without needing a detail tab open, reachable while just browsing
 * files on a PR branch. See docs/plan/pr-review-feature-plan.md,
 * "Architecture pivot," Phase 17.
 */
class CurrentBranchReviewPanel(private val project: Project) {

    private val statusLabel = JBLabel(" ")
    private val actionsContainer = JBPanel<JBPanel<*>>(BorderLayout())

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Refresh current-branch PR").apply { addActionListener { refresh() } })
                add(statusLabel)
            },
            BorderLayout.NORTH,
        )
        add(actionsContainer, BorderLayout.CENTER)
    }

    init {
        refresh()
    }

    fun refresh() {
        actionsContainer.removeAll()
        actionsContainer.revalidate()
        actionsContainer.repaint()

        val repoContext = GiteaRepoLinkService.getInstance(project).resolveDefault()
        if (repoContext == null) {
            statusLabel.text = "No linked Gitea repository."
            return
        }
        val token = GiteaAccountManager.getInstance().getToken(repoContext.account)
        if (token.isNullOrBlank()) {
            statusLabel.text = "No token saved for account \"${repoContext.account.name}\"."
            return
        }
        val resolver = GiteaBranchPrResolverService.getInstance(project)
        val branch = resolver.currentBranchName()
        if (branch == null) {
            statusLabel.text = "No git branch detected."
            return
        }

        statusLabel.text = "Resolving PR for branch \"$branch\"…"
        resolver.invalidate() // explicit refresh should re-fetch, not reuse a cached answer
        val client = GiteaApiClient(repoContext.account.serverUrl, token)
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val pr = runCatching { resolver.resolveForCurrentBranch(repoContext, client) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (pr == null) {
                    statusLabel.text = "No open PR found for branch \"$branch\"."
                    return@withContext
                }
                statusLabel.text = "Branch \"$branch\" → PR #${pr.number}: ${pr.title}"
                val store = PendingReviewStoreService.getInstance(project)
                    .storeFor(repoContext.owner, repoContext.repo, pr.number)
                val actions = ReviewActionsPanel(
                    project, client, repoContext.owner, repoContext.repo, pr.number, pr.head.sha, store,
                )
                actionsContainer.add(actions.component, BorderLayout.CENTER)
                actionsContainer.revalidate()
                actionsContainer.repaint()
            }
        }
    }
}
