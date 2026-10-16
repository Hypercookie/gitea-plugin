package com.github.hypercookie.giteaplugin.pullrequest.list

import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrMatcher
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.util.GiteaBrowser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import git4idea.branch.GitBrancher
import git4idea.fetch.GitFetchSupport
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.datatransfer.StringSelection

/** Pull request operations shared by the list's context menu and the
 * detail tab. */
object GiteaPullRequestActions {

    /**
     * Fetches [pr]'s head branch from the remote [repo] was resolved from,
     * then checks it out via `GitBrancher` (handles VFS refresh): the local
     * branch of that name if there is one, otherwise a new one tracking the
     * fetched branch. Asks first when the working tree has uncommitted
     * changes, since this is hard to reverse.
     *
     * Pull requests from a fork are refused: their head branch lives in
     * another repository, and a same-named branch here (often `main`) is
     * not the pull request's code. Failures are reported in a notification.
     */
    suspend fun fetchAndCheckout(
        project: Project,
        repository: GitRepository,
        repo: GiteaRepoContext,
        pr: PullRequest,
        onDone: () -> Unit = {},
    ) {
        val branch = pr.head.ref
        if (GiteaBranchPrMatcher.isFork(pr)) {
            notifyError(project, "Can't check out pull request #${pr.number}", "Its branch $branch is in a fork; only branches of ${repo.owner}/${repo.repo} can be checked out.")
            return
        }
        val remote = remoteFor(repository, repo)
        if (remote == null) {
            notifyError(project, "Can't check out pull request #${pr.number}", "No git remote of this project points at ${repo.owner}/${repo.repo}.")
            return
        }
        try {
            withBackgroundProgress(project, "Fetching $branch from ${remote.name}") {
                withContext(Dispatchers.IO) {
                    GitFetchSupport.fetchSupport(project)
                        .fetch(repository, remote, "+refs/heads/$branch:refs/remotes/${remote.name}/$branch")
                        .throwExceptionIfFailed()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notifyError(project, "Can't fetch $branch", e.message ?: e.javaClass.simpleName)
            return
        }
        withContext(Dispatchers.EDT) { checkout(project, repository, branch, "${remote.name}/$branch", onDone) }
    }

    private fun checkout(project: Project, repository: GitRepository, branch: String, remoteBranch: String, onDone: () -> Unit) {
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
        val brancher = GitBrancher.getInstance(project)
        if (repository.branches.findLocalBranch(branch) != null) {
            brancher.checkout(branch, false, listOf(repository), onDone)
        } else {
            brancher.checkoutNewBranchStartingFrom(branch, remoteBranch, listOf(repository), onDone)
        }
    }

    /** The remote of [repository] that [repo] was resolved from. */
    private fun remoteFor(repository: GitRepository, repo: GiteaRepoContext): GitRemote? =
        repository.remotes.firstOrNull { repo.remoteUrl in it.urls }

    private fun notifyError(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("Gitea")
            .createNotification(HtmlChunk.text(title).toString(), HtmlChunk.text(content).toString(), NotificationType.ERROR)
            .notify(project)
    }

    fun openInBrowser(url: String?) {
        GiteaBrowser.open(url)
    }

    fun copyLink(url: String?) {
        if (!url.isNullOrBlank()) CopyPasteManager.getInstance().setContents(StringSelection(url))
    }
}
