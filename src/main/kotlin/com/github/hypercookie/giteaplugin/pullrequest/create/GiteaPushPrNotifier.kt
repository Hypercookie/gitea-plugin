package com.github.hypercookie.giteaplugin.pullrequest.create

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.accounts.GiteaProjectDefaultAccountHolder
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.push.GitPushListener
import git4idea.push.GitPushRepoResult
import git4idea.repo.GitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After a push of a branch to a Gitea repository, offers to create a pull
 * request for it when none is open yet (GitHub: the "Create Pull Request"
 * action on the push notification). The plugin can't create pull requests
 * in the IDE, so the action opens Gitea's compare page for the branch.
 *
 * Listens to [GitPushListener] rather than adding an action to Git's own
 * push notification: that extension point is internal API.
 */
class GiteaPushPrNotifier(private val project: Project) : GitPushListener {

    override fun onCompleted(repository: GitRepository, pushResult: GitPushRepoResult) {
        if (!GiteaSettings.getInstance().suggestPullRequestAfterPush) return
        if (pushResult.type !in SUGGEST_FOR) return
        val pushed = GiteaPushPrSuggestion.pushedBranch(pushResult.sourceBranch, pushResult.targetBranch, pushResult.targetRemote)
            ?: return
        val context = contextFor(repository, pushResult.targetRemote) ?: return
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch(Dispatchers.IO) {
            try {
                suggest(context, pushed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A suggestion nobody asked for isn't worth an error balloon.
                LOG.info("Could not check for a pull request after pushing ${pushed.localBranch}", e)
            }
        }
    }

    /** The Gitea repository [remoteName] of [repository] points at; the
     * project's default account breaks ties between accounts. */
    private fun contextFor(repository: GitRepository, remoteName: String): GiteaRepoContext? {
        val remote = repository.remotes.firstOrNull { it.name == remoteName } ?: return null
        val candidates = GiteaRepoLinkService.getInstance(project).findAllMatches()
            .filter { it.gitRoot == repository.root && it.remoteUrl in remote.urls }
        val defaultAccountId = GiteaProjectDefaultAccountHolder.getInstance(project).account?.id
        return candidates.firstOrNull { it.account.id == defaultAccountId } ?: candidates.firstOrNull()
    }

    private suspend fun suggest(context: GiteaRepoContext, pushed: GiteaPushedBranch) {
        val token = GiteaAccountManager.getInstance().getToken(context.account) ?: return
        val client = GiteaApiClient(context.account.serverUrl, token)
        val defaultBranch = client.getRepository(context.owner, context.repo).defaultBranch
        if (!GiteaPushPrSuggestion.shouldSuggest(pushed, defaultBranch, client.listPullRequests(context.owner, context.repo))) return

        val url = GiteaPushPrSuggestion.compareUrl(context.account.serverUrl, context.owner, context.repo, defaultBranch!!, pushed.remoteBranch)
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "Pushed ${pushed.localBranch}",
                "No pull request is open for ${pushed.remoteBranch} in ${context.owner}/${context.repo}.",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimpleExpiring("Create Pull Request…") { BrowserUtil.browse(url) })
            .notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Gitea Pull Request Suggestions"

        private val LOG = logger<GiteaPushPrNotifier>()

        /** Pushes that leave the branch on the remote; not rejected, failed
         * or up-to-date ones. */
        private val SUGGEST_FOR = setOf(
            GitPushRepoResult.Type.SUCCESS,
            GitPushRepoResult.Type.NEW_BRANCH,
            GitPushRepoResult.Type.FORCED,
        )
    }
}
