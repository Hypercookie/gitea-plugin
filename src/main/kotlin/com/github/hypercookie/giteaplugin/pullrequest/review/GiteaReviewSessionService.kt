package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchKey
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrMatcher
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrResolverService
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchSync
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStoreService
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.settings.GiteaSettingsListener
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The open pull request for the currently checked-out branch, if any, as a
 * flow every editor installer collects. Replaces the per-editor resolution
 * the old `WorkingCopyCommentEditorListener` did on each file open.
 *
 * Git repository changes fire on fetch, commit and index updates too, not
 * only on branch switches, so resolution is debounced and the same PR keeps
 * its [GiteaPullRequestReviewData] instance (updated in place) instead of
 * being rebuilt, which would tear down every open inlay.
 *
 * The session is dropped as soon as the checked-out branch (or its
 * upstream) differs from the one it was resolved for, before any network
 * access, so a failed resolution can't leave another branch's PR behind.
 */
@Service(Service.Level.PROJECT)
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GiteaReviewSessionService(private val project: Project, private val cs: CoroutineScope) {

    private val refreshRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _session = MutableStateFlow<GiteaPullRequestReviewData?>(null)
    val session: StateFlow<GiteaPullRequestReviewData?> = _session.asStateFlow()

    /** What [session] was resolved for; only touched by the refresh loop. */
    @Volatile
    private var sessionKey: GiteaBranchKey? = null

    private val _branchSync = MutableStateFlow<GiteaBranchSync?>(null)

    /** The checked-out branch [session] belongs to and how far it is from
     * the pull request head; read by the out-of-sync editor banner. */
    val branchSync: StateFlow<GiteaBranchSync?> = _branchSync.asStateFlow()

    init {
        // Coming back to the IDE (e.g. after deleting a comment in the
        // browser) refreshes review data, like the GitHub plugin's refresh
        // on frame activation, and picks up a pull request opened for the
        // current branch meanwhile.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(cs).subscribe(
            com.intellij.openapi.application.ApplicationActivationListener.TOPIC,
            object : com.intellij.openapi.application.ApplicationActivationListener {
                override fun applicationActivated(ideFrame: com.intellij.openapi.wm.IdeFrame) {
                    if (GiteaSettings.getInstance().refreshOnFrameActivation) refreshAllData()
                }
            },
        )
        // Turning review in the editor off drops the session (and with it the
        // inlays); turning it on looks the pull request up again.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(cs).subscribe(
            GiteaSettingsListener.TOPIC,
            GiteaSettingsListener { before, after -> if (before.reviewInEditor != after.reviewInEditor) requestRefresh() },
        )
        project.messageBus.connect(cs).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            GitRepositoryChangeListener { requestRefresh() },
        )
        cs.launch(Dispatchers.IO) {
            refreshRequests.debounce(REFRESH_DEBOUNCE_MS).collectLatest {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e // superseded by a newer request
                } catch (e: Exception) {
                    thisLogger().warn("Failed to resolve pull request for current branch", e)
                    clearIfBranchChanged()
                }
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
        // A new head from elsewhere (reload on frame activation, the pull
        // request tab) needs new ahead/behind counts for the banner.
        cs.launch {
            _session.flatMapLatest { it?.pullRequest ?: flowOf(null) }
                .map { it?.head?.sha }
                .distinctUntilChanged()
                .collect { sha -> if (sha != null && sha != _branchSync.value?.prHead) requestRefresh() }
        }
        requestRefresh()
    }

    /** Every live pull request data holder: the current-branch session and
     * the ones behind open pull request tabs and diff viewers. */
    private val liveData = java.util.concurrent.ConcurrentHashMap.newKeySet<GiteaPullRequestReviewData>()

    fun register(data: GiteaPullRequestReviewData) {
        liveData += data
        data.cs.coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { liveData -= data }
    }

    /** Re-fetches pull request details and review comments everywhere, so
     * comments edited or deleted on the server disappear from editors too. */
    fun refreshAllData() {
        liveData.forEach { it.reload() }
        // A pull request may have been opened for the current branch
        // meanwhile; user-paced, so the "no pull request" cache can go.
        GiteaBranchPrResolverService.getInstance(project).invalidate()
        requestRefresh()
    }

    /** Re-fetches [data]'s pull request in every live holder of it: the
     * pull request tab, diff viewers opened from it and the current-branch
     * session each keep their own comments when the pull request isn't the
     * checked-out one. */
    fun refreshPullRequest(data: GiteaPullRequestReviewData) {
        liveData.filter { it === data || (it.repo == data.repo && it.number == data.number) }.forEach { it.reload() }
    }

    fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    private suspend fun refresh() {
        // Turned off in the settings: no lookups, and no review in editors.
        if (!GiteaSettings.getInstance().reviewInEditor) return clear()
        val repoContext = GiteaRepoLinkService.getInstance(project).resolveDefault() ?: return clear()
        val resolver = GiteaBranchPrResolverService.getInstance(project)
        val repository = resolver.repositoryFor(repoContext) ?: return clear()
        val key = resolver.currentBranchKey(repoContext) ?: return clear()
        // Before any credential or network access: if that fails, the
        // previous branch's pull request must not stay around.
        if (key != sessionKey) clear()

        val token = GiteaAccountManager.getInstance().getToken(repoContext.account)
        if (token.isNullOrBlank()) return clear()
        val client = GiteaApiClient(repoContext.account.serverUrl, token)

        val current = _session.value?.takeIf { it.repo == repoContext }
        val pr = current?.let { stillOpenForBranch(client, it, key) }
            ?: resolver.resolve(repoContext, client, key)
            ?: return clear()

        // Published before the session, so the head-change collector in
        // init sees an up-to-date sync and doesn't request another refresh.
        _branchSync.value = computeSync(repository, key, pr)
        sessionKey = key
        if (current != null && current.number == pr.number) {
            current.update(pr)
            return
        }
        val store = PendingReviewStoreService.getInstance(project).storeFor(repoContext.owner, repoContext.repo, pr.number)
        replace(GiteaPullRequestReviewData(project, cs.giteaChildScope("Gitea PR #${pr.number}"), repoContext, client, pr, store))
    }

    /** The session's pull request, fetched fresh, if it is still open and
     * still matches the checked-out branch; one request instead of listing
     * every open pull request on each git event. */
    private suspend fun stillOpenForBranch(client: GiteaApiClient, data: GiteaPullRequestReviewData, key: GiteaBranchKey): PullRequest? {
        val pr = client.getPullRequest(data.owner, data.repoName, data.number)
        if (pr.state != "open" || pr.merged) return null
        return GiteaBranchPrMatcher.match(key.localBranch, key.tracked, listOf(pr))
    }

    /** Ahead/behind counts of HEAD against the pull request head. */
    private fun computeSync(repository: GitRepository, key: GiteaBranchKey, pr: PullRequest): GiteaBranchSync {
        val localHead = repository.currentRevision
        val prHead = pr.head.sha
        val counts = when {
            localHead == null -> null
            localHead == prHead -> 0 to 0
            else -> aheadBehind(repository, prHead)
        }
        return GiteaBranchSync(key.rootPath, key.localBranch, pr.number, localHead, prHead, counts?.first, counts?.second)
    }

    /** `git rev-list --left-right --count HEAD...<sha>`; null when [sha]
     * isn't available locally (not fetched yet). */
    private fun aheadBehind(repository: GitRepository, sha: String): Pair<Int, Int>? {
        if (!SHA_PATTERN.matches(sha)) return null
        val handler = GitLineHandler(project, repository.root, GitCommand.REV_LIST).apply {
            setSilent(true)
            addParameters("--left-right", "--count", "HEAD...$sha")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        val counts = result.outputAsJoinedString.trim().split(WHITESPACE).mapNotNull { it.toIntOrNull() }
        return if (counts.size == 2) counts[0] to counts[1] else null
    }

    /** Drops the session if the checked-out branch is no longer the one it
     * was resolved for; used when a refresh fails part-way. */
    private fun clearIfBranchChanged() {
        val key = runCatching {
            GiteaRepoLinkService.getInstance(project).resolveDefault()
                ?.let { GiteaBranchPrResolverService.getInstance(project).currentBranchKey(it) }
        }.getOrNull()
        if (key == null || key != sessionKey) clear()
    }

    /** Drops the session; banners go right away rather than after the
     * rest of a refresh (which may wait on the network) finishes. */
    private fun clear() {
        val hadSession = _session.value != null
        sessionKey = null
        _branchSync.value = null
        replace(null)
        if (hadSession) EditorNotifications.getInstance(project).updateAllNotifications()
    }

    private fun replace(data: GiteaPullRequestReviewData?) {
        val old = _session.value
        _session.value = data
        if (old !== data) old?.cs?.cancel()
    }

    companion object {
        private const val REFRESH_DEBOUNCE_MS = 300L
        private val SHA_PATTERN = Regex("[0-9a-fA-F]{7,64}")
        private val WHITESPACE = Regex("\\s+")

        fun getInstance(project: Project): GiteaReviewSessionService = project.service()
    }
}
