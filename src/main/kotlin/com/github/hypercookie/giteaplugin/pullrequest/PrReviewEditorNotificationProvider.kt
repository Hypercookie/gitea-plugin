package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.util.function.Function
import javax.swing.JComponent

/**
 * "Pending changes in a top bar where I can push them" — a banner above the
 * editor (the same mechanism used for e.g. VCS/Maven "reload" banners) shown
 * whenever the open file's branch resolves to an open PR **and** that PR has
 * queued-but-not-submitted comments. Reuses [ReviewActionsPanel] so pushing
 * from here goes through the same submit path as everywhere else. See
 * docs/plan/pr-review-feature-plan.md, "UI pass," Phase 20.
 *
 * `PendingReviewStoreService` calls
 * `EditorNotifications.updateAllNotifications()` on every store change, so
 * this banner appears/disappears live without needing the file reopened.
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
        if (store.pendingComments.isEmpty()) return null

        return Function {
            val token = GiteaAccountManager.getInstance().getToken(repoContext.account)
            if (token.isNullOrBlank()) return@Function null
            val client = GiteaApiClient(repoContext.account.serverUrl, token)

            JBPanel<JBPanel<*>>(BorderLayout()).apply {
                background = JBColor.namedColor("EditorTooltip.background", JBColor(0xF5F5F5, 0x4B4D51))
                border = JBUI.Borders.customLineBottom(JBColor.border())
                add(
                    JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                        isOpaque = false
                        add(JBLabel("PR #${pr.number}: ${pr.title}"))
                    },
                    BorderLayout.WEST,
                )
                add(ReviewActionsPanel(project, client, repoContext.owner, repoContext.repo, pr.number, pr.head.sha, store).component, BorderLayout.CENTER)
            }
        }
    }
}
