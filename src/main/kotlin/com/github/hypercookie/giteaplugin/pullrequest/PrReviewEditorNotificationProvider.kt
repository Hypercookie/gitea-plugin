package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.icons.GiteaIcons
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.function.Function
import javax.swing.JComponent

/**
 * Top-of-editor banner (the same mechanism used for e.g. VCS/Maven "reload"
 * banners) covering two cases, either or both at once:
 *
 * - **Pending changes** — "in a top bar where I can push them": shown when
 *   the open file's branch resolves to an open PR with queued-but-not-
 *   submitted comments. Reuses [ReviewActionsPanel] so pushing from here
 *   goes through the same submit path as everywhere else.
 * - **Staleness** — the local file may have drifted from the PR's reviewed
 *   commit (local commits ahead of `pr.head.sha`, or an unsaved edit), which
 *   the "accept + warn" line-drift decision (see
 *   docs/plan/pr-review-feature-plan.md, "Architecture pivot") said should
 *   surface as a warning rather than a real head-blob↔working-copy line
 *   mapping.
 *
 * See docs/plan/pr-review-feature-plan.md, "UI pass," Phase 20 and Phase 14.
 *
 * `PendingReviewStoreService` calls
 * `EditorNotifications.updateAllNotifications()` on every store change, so
 * the pending-changes half of this banner appears/disappears live without
 * needing the file reopened.
 */
class PrReviewEditorNotificationProvider : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        // Must not touch PasswordSafe or the network here — this runs
        // inside a non-cancelable read action (confirmed via a SEVERE
        // SlowOperations log during manual testing when this used to call
        // getToken()/resolveForCurrentBranch() synchronously). Only reads
        // already-resolved, in-memory state; the token/client are resolved
        // lazily below, once, when the banner component is actually built.
        val repoContext = GiteaRepoLinkService.getInstance(project).resolveDefault() ?: return null
        val resolver = GiteaBranchPrResolverService.getInstance(project)
        val branch = resolver.currentBranchName() ?: return null
        val pr = resolver.peekCachedPr(branch) ?: return null

        val store = PendingReviewStoreService.getInstance(project).storeFor(repoContext.owner, repoContext.repo, pr.number)
        val hasPending = store.pendingComments.isNotEmpty()

        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        val isStale = (repo?.currentRevision != null && repo.currentRevision != pr.head.sha) ||
            FileDocumentManager.getInstance().isFileModified(file)

        if (!hasPending && !isStale) return null

        return Function {
            val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                background = JBColor.namedColor("EditorTooltip.background", JBColor(0xF5F5F5, 0x4B4D51))
                border = JBUI.Borders.customLineBottom(JBColor.border())
            }

            if (isStale) {
                panel.add(
                    JBLabel(
                        "Local file differs from PR #${pr.number}'s reviewed commit — comment positions may be inaccurate.",
                        AllIcons.General.Warning,
                        JBLabel.LEFT,
                    ).apply { border = JBUI.Borders.empty(2, 8) },
                    BorderLayout.NORTH,
                )
            }

            if (hasPending) {
                val token = GiteaAccountManager.getInstance().getToken(repoContext.account)
                if (!token.isNullOrBlank()) {
                    val client = GiteaApiClient(repoContext.account.serverUrl, token)
                    panel.add(
                        JBPanel<JBPanel<*>>(BorderLayout()).apply {
                            isOpaque = false
                            add(
                                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                                    isOpaque = false
                                    add(JBLabel("PR #${pr.number}: ${pr.title}", GiteaIcons.Gitea, JBLabel.LEFT))
                                },
                                BorderLayout.WEST,
                            )
                            add(
                                ReviewActionsPanel(project, client, repoContext.owner, repoContext.repo, pr.number, pr.head.sha, store).component,
                                BorderLayout.CENTER,
                            )
                        },
                        BorderLayout.CENTER,
                    )
                }
            }

            if (panel.componentCount == 0) null else panel
        }
    }
}
