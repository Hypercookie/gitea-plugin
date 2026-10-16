package com.github.hypercookie.giteaplugin.pullrequest.toolwindow

import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStoreService
import com.github.hypercookie.giteaplugin.pullrequest.details.GiteaPullRequestDetailsComponent
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaPullRequestListComponent
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaReviewSessionService
import com.github.hypercookie.giteaplugin.settings.GiteaConfigurable
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.github.hypercookie.giteaplugin.util.errorPanel
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.github.hypercookie.giteaplugin.util.runCatchingCancellable
import com.intellij.collaboration.ui.toolwindow.ReviewTabsComponentFactory
import com.intellij.collaboration.ui.toolwindow.manageReviewToolwindowTabs
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * "Pull Requests" tool window: list tab plus one closable tab per opened
 * pull request, managed by the platform's `manageReviewToolwindowTabs`
 * (same as the GitHub plugin's `GHPRToolWindowFactory`).
 */
class GiteaToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun init(toolWindow: ToolWindow) {
        // Id stays "Gitea Pull Requests" so it can't clash with the GitHub
        // plugin's "Pull Requests" tool window; the stripe shows the same
        // short title GitHub uses.
        toolWindow.stripeTitle = "Pull Requests"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cs = GiteaProjectCoroutineScope.getInstance(project).coroutineScope.giteaChildScope("Gitea tool window")
        Disposer.register(toolWindow.contentManager) { cs.cancel() }

        val twVm = GiteaToolWindowViewModel(cs, project)
        toolWindow.setTitleActions(
            listOf(
                object : DumbAwareAction("Refresh", "Reload pull requests", AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) {
                        val projectVm = twVm.projectVm.value
                        if (projectVm == null) twVm.connect() else projectVm.listVm.reload()
                        GiteaReviewSessionService.getInstance(project).refreshAllData()
                    }
                },
            ),
        )
        cs.launch(Dispatchers.Main) {
            manageReviewToolwindowTabs(this, toolWindow, twVm, TabsComponentFactory(twVm), "Pull Requests")
            awaitCancellation()
        }
    }

    override fun shouldBeAvailable(project: Project) = true

    private class TabsComponentFactory(private val twVm: GiteaToolWindowViewModel) :
        ReviewTabsComponentFactory<GiteaPullRequestTabViewModel, GiteaProjectViewModel> {

        override fun createReviewListComponent(cs: CoroutineScope, projectVm: GiteaProjectViewModel): JComponent =
            GiteaPullRequestListComponent.create(cs, projectVm)

        override fun createTabComponent(
            cs: CoroutineScope,
            projectVm: GiteaProjectViewModel,
            tabVm: GiteaPullRequestTabViewModel,
        ): JComponent {
            val wrapper = JBPanelWithEmptyText(BorderLayout()).withEmptyText("Loading pull request…")
            cs.launch {
                val pr = runCatchingCancellable {
                    projectVm.client.getPullRequest(projectVm.repo.owner, projectVm.repo.repo, tabVm.number)
                }
                withContext(Dispatchers.Main) {
                    pr.onSuccess {
                        // Share comments and pending state with open editors
                        // when this is the current branch's pull request.
                        val session = GiteaReviewSessionService.getInstance(projectVm.project).session.value
                        val data = session?.takeIf { s -> s.repo == projectVm.repo && s.number == it.number }
                            ?: GiteaPullRequestReviewData(
                                projectVm.project, cs, projectVm.repo, projectVm.client, it,
                                PendingReviewStoreService.getInstance(projectVm.project).storeFor(projectVm.repo, it.number),
                            )
                        wrapper.add(GiteaPullRequestDetailsComponent.create(cs, data), BorderLayout.CENTER)
                        wrapper.revalidate()
                    }.onFailure {
                        // Empty text is a single line; the error panel wraps
                        // long server messages instead of cutting them off.
                        wrapper.emptyText.clear()
                        val error = errorPanel("Can't load pull request #${tabVm.number}", it).apply {
                            border = JBUI.Borders.empty(12)
                        }
                        wrapper.add(error, BorderLayout.NORTH)
                        wrapper.revalidate()
                    }
                }
            }
            return wrapper
        }

        override fun createEmptyTabContent(cs: CoroutineScope): JComponent =
            JBPanelWithEmptyText().also { panel ->
                cs.bindOnEdt(twVm.notConnectedReason) { reason ->
                    val hint = when (reason) {
                        GiteaNotConnectedReason.NO_MATCH -> "Add a Gitea account for this project's remote"
                        GiteaNotConnectedReason.AMBIGUOUS -> "Several Gitea accounts match this project; choose a default account in Settings"
                    }
                    panel.emptyText.clear()
                    panel.emptyText.appendLine("No Gitea repository connected")
                    panel.emptyText.appendLine(hint, SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
                    panel.emptyText.appendLine("Open Settings", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                        ShowSettingsUtil.getInstance().showSettingsDialog(twVm.project, GiteaConfigurable::class.java)
                        twVm.connect()
                    }
                    panel.emptyText.appendLine("Retry", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { twVm.connect() }
                    panel.repaint()
                }
            }
    }
}
