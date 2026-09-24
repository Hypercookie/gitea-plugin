package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * "Pull Requests" tool window content: list open PRs for whatever Gitea repo
 * [GiteaRepoLinkService] resolves for this project, with a manual refresh.
 * Phase 4 — list only; opening a PR into a detail tab is Phase 5.
 */
class PullRequestListPanel(private val project: Project) {

    private val listModel = DefaultListModel<PullRequest>()
    private val prList = JBList(listModel).apply {
        cellRenderer = PullRequestCellRenderer()
    }
    private val statusLabel = JBLabel(" ")

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val toolbar = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(JButton("Refresh").apply { addActionListener { refresh() } })
            add(statusLabel)
        }
        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(prList), BorderLayout.CENTER)
    }

    init {
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
