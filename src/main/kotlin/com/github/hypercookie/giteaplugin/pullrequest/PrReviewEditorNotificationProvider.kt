package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.icons.GiteaIcons
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaReviewSessionService
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaSubmitReviewButton
import com.intellij.icons.AllIcons
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
 *   submitted comments. Reuses [com.github.hypercookie.giteaplugin.pullrequest.review.GiteaSubmitReviewButton] so pushing from here
 *   goes through the same submit path as everywhere else.
 * - **Out of sync** — local HEAD is not the PR's head commit (local
 *   commits, or the PR got new commits). Comment positions are mapped onto
 *   local edits ([com.github.hypercookie.giteaplugin.pullrequest.review.LineMapping]),
 *   so unsaved edits no longer warrant a warning; this matches the GitHub
 *   plugin's "local branch is out of sync" state. The text names the local
 *   branch and says whether it is ahead, behind or diverged
 *   ([GiteaOutOfSyncBanner]), from counts the session service computes in
 *   the background.
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
        // the already-resolved review session, which carries its client.
        val sessions = GiteaReviewSessionService.getInstance(project)
        val data = sessions.session.value ?: return null
        val sync = sessions.branchSync.value ?: return null
        val pr = data.pullRequest.value
        val store = data.pendingStore
        val hasPending = store.pendingComments.isNotEmpty()

        // Only files of the repository the session was resolved for, and
        // only while that branch is still checked out (a checkout is seen
        // here before the debounced re-resolution catches up).
        val repo = GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file) ?: return null
        if (repo.root.path != sync.rootPath || sync.prNumber != pr.number) return null
        val liveBranch = repo.currentBranchName
        if (!GiteaOutOfSyncBanner.isSessionCurrent(sync.localBranch, liveBranch)) return null
        val outOfSync = GiteaOutOfSyncBanner.message(sync, liveBranch, repo.currentRevision, pr)

        if (!hasPending && outOfSync == null) return null

        return Function {
            val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                background = JBColor.namedColor("EditorTooltip.background", JBColor(0xF5F5F5, 0x4B4D51))
                border = JBUI.Borders.customLineBottom(JBColor.border())
            }

            if (outOfSync != null) {
                panel.add(
                    JBLabel(
                        outOfSync,
                        AllIcons.General.Warning,
                        JBLabel.LEFT,
                    ).apply { border = JBUI.Borders.empty(2, 8) },
                    BorderLayout.NORTH,
                )
            }

            if (hasPending) {
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
                            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                                isOpaque = false
                                add(GiteaSubmitReviewButton.createStatic(data))
                            },
                            BorderLayout.CENTER,
                        )
                    },
                    BorderLayout.CENTER,
                )
            }

            if (panel.componentCount == 0) null else panel
        }
    }
}
