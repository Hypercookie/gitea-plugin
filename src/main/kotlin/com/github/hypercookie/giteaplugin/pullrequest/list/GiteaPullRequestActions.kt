package com.github.hypercookie.giteaplugin.pullrequest.list

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager
import git4idea.branch.GitBrancher
import git4idea.repo.GitRepository
import java.awt.datatransfer.StringSelection

/** Pull request operations shared by the list's context menu and the
 * detail tab. */
object GiteaPullRequestActions {

    /**
     * Checks out [branch] locally via `GitBrancher` (handles VFS refresh).
     * Asks first when the working tree has uncommitted changes, since this
     * is hard to reverse. Only same-repository branches resolve; fork pull
     * requests are out of scope.
     */
    fun checkout(project: Project, repository: GitRepository, branch: String, onDone: () -> Unit = {}) {
        val dirty = ChangeListManager.getInstance(project).allChanges.isNotEmpty()
        if (dirty) {
            val confirmed = Messages.showYesNoDialog(
                project,
                "You have uncommitted changes. Check out branch \"$branch\" anyway?",
                "Checkout Branch",
                "Checkout",
                "Cancel",
                Messages.getWarningIcon(),
            )
            if (confirmed != Messages.YES) return
        }
        GitBrancher.getInstance(project).checkout(branch, false, listOf(repository), onDone)
    }

    fun openInBrowser(url: String?) {
        if (!url.isNullOrBlank()) BrowserUtil.browse(url)
    }

    fun copyLink(url: String?) {
        if (!url.isNullOrBlank()) CopyPasteManager.getInstance().setContents(StringSelection(url))
    }
}
