package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrResolverService
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStoreService
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * The open pull request for the currently checked-out branch, if any, as a
 * flow every editor installer collects. Replaces the per-editor resolution
 * the old `WorkingCopyCommentEditorListener` did on each file open.
 *
 * Git repository changes fire on fetch, commit and index updates too, not
 * only on branch switches, so resolution is debounced and the same PR keeps
 * its [GiteaPullRequestReviewData] instance (updated in place) instead of
 * being rebuilt, which would tear down every open inlay.
 */
@Service(Service.Level.PROJECT)
@OptIn(FlowPreview::class)
class GiteaReviewSessionService(private val project: Project, private val cs: CoroutineScope) {

    private val refreshRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _session = MutableStateFlow<GiteaPullRequestReviewData?>(null)
    val session: StateFlow<GiteaPullRequestReviewData?> = _session.asStateFlow()

    init {
        project.messageBus.connect(cs).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { requestRefresh() },
        )
        cs.launch(Dispatchers.IO) {
            refreshRequests.debounce(REFRESH_DEBOUNCE_MS).collectLatest {
                runCatching { refresh() }.onFailure { thisLogger().warn("Failed to resolve pull request for current branch", it) }
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
        requestRefresh()
    }

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    private suspend fun refresh() {
        val repoContext = GiteaRepoLinkService.getInstance(project).resolveDefault()
        val token = repoContext?.let { GiteaAccountManager.getInstance().getToken(it.account) }
        if (repoContext == null || token.isNullOrBlank()) return replace(null)

        val client = GiteaApiClient(repoContext.account.serverUrl, token)
        val resolver = GiteaBranchPrResolverService.getInstance(project)
        resolver.invalidate()
        val pr = resolver.resolveForCurrentBranch(repoContext, client) ?: return replace(null)

        val current = _session.value
        if (current != null && current.repo == repoContext && current.number == pr.number) {
            current.update(pr)
            return
        }
        val store = PendingReviewStoreService.getInstance(project).storeFor(repoContext.owner, repoContext.repo, pr.number)
        replace(GiteaPullRequestReviewData(project, cs.giteaChildScope("Gitea PR #${pr.number}"), repoContext, client, pr, store))
    }

    private fun replace(data: GiteaPullRequestReviewData?) {
        val old = _session.value
        _session.value = data
        if (old !== data) old?.cs?.cancel()
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 300L

        fun getInstance(project: Project): GiteaReviewSessionService = project.service()
    }
}
