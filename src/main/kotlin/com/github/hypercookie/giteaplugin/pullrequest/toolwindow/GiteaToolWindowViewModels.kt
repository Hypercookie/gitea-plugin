package com.github.hypercookie.giteaplugin.pullrequest.toolwindow

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaListSearchHistoryModel
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaPullRequestListViewModel
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaSearchPanelViewModel
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.collaboration.ui.toolwindow.ReviewTab
import com.intellij.collaboration.ui.toolwindow.ReviewTabViewModel
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowProjectViewModel
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowTabs
import com.intellij.collaboration.ui.toolwindow.ReviewToolwindowViewModel
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A closable pull request tab in the tool window. */
data class GiteaPullRequestTab(val number: Long) : ReviewTab {
    override val id: String = "pr-$number"
    override val reuseTabOnRequest: Boolean = true
}

class GiteaPullRequestTabViewModel(val number: Long, title: String?) : ReviewTabViewModel {
    override val displayName: String = "#$number"
    override val description: String = title?.let { "#$number $it" } ?: displayName
}

/**
 * The connected repository: list, search and open tabs. Gitea counterpart
 * of the GitHub plugin's `GHPRToolWindowProjectViewModel`; tab management
 * itself is the platform's (`manageReviewToolwindowTabs`).
 */
class GiteaProjectViewModel(
    cs: CoroutineScope,
    val project: Project,
    val repo: GiteaRepoContext,
    val client: GiteaApiClient,
    val currentUser: GiteaUser?,
) : ReviewToolwindowProjectViewModel<GiteaPullRequestTab, GiteaPullRequestTabViewModel> {

    override val projectName: String = "${repo.owner}/${repo.repo}"

    val searchVm = GiteaSearchPanelViewModel(cs, repo, client, GiteaListSearchHistoryModel(), currentUser)
    override val listVm = GiteaPullRequestListViewModel(cs, repo, client, searchVm)

    private val _tabs = MutableStateFlow(ReviewToolwindowTabs<GiteaPullRequestTab, GiteaPullRequestTabViewModel>(emptyMap(), null))
    override val tabs: StateFlow<ReviewToolwindowTabs<GiteaPullRequestTab, GiteaPullRequestTabViewModel>> = _tabs.asStateFlow()

    fun openPullRequest(number: Long, title: String? = null) {
        val tab = GiteaPullRequestTab(number)
        _tabs.update { current ->
            val vm = current.tabs[tab] ?: GiteaPullRequestTabViewModel(number, title)
            current.copy(tabs = current.tabs + (tab to vm), selectedTab = tab)
        }
    }

    override fun selectTab(tab: GiteaPullRequestTab?) {
        _tabs.update { it.copy(selectedTab = tab) }
    }

    override fun closeTab(tab: GiteaPullRequestTab) {
        _tabs.update { current ->
            current.copy(tabs = current.tabs - tab, selectedTab = current.selectedTab.takeIf { it != tab })
        }
    }
}

/** Why the tool window has no connected repository. */
enum class GiteaNotConnectedReason {
    /** No configured account matches a remote of this project. */
    NO_MATCH,

    /** Several accounts match and no project default account picks one. */
    AMBIGUOUS,
}

/**
 * Tool window state: a connected [GiteaProjectViewModel], or null when no
 * Gitea remote or no token is configured (the platform then shows the
 * empty-state content, which explains [notConnectedReason]).
 *
 * Reconnects whenever accounts are added, changed or removed, so a removed
 * account's repository disappears from the tool window right away. The
 * client looks the token up on each request, so a new token for the same
 * account needs no reconnect.
 */
class GiteaToolWindowViewModel(private val cs: CoroutineScope, val project: Project) :
    ReviewToolwindowViewModel<GiteaProjectViewModel> {

    private val _projectVm = MutableStateFlow<GiteaProjectViewModel?>(null)
    override val projectVm: StateFlow<GiteaProjectViewModel?> = _projectVm.asStateFlow()

    private val _notConnectedReason = MutableStateFlow(GiteaNotConnectedReason.NO_MATCH)
    val notConnectedReason: StateFlow<GiteaNotConnectedReason> = _notConnectedReason.asStateFlow()

    private val connectRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Only touched by the connect loop, which runs one connect at a time. */
    private var projectScope: CoroutineScope? = null

    init {
        cs.launch(Dispatchers.IO) {
            merge(connectRequests, GiteaAccountManager.getInstance().accountsState.map { })
                .collectLatest { connectNow() }
        }
    }

    /** Re-resolves the repository and account, e.g. after settings change. */
    fun connect() {
        connectRequests.tryEmit(Unit)
    }

    private suspend fun connectNow() {
        val linkService = GiteaRepoLinkService.getInstance(project)
        val accountManager = GiteaAccountManager.getInstance()
        val repo = linkService.resolveDefault()
        if (repo == null || accountManager.getToken(repo.account) == null) {
            _notConnectedReason.value =
                if (repo == null && linkService.findAllMatches().size > 1) GiteaNotConnectedReason.AMBIGUOUS
                else GiteaNotConnectedReason.NO_MATCH
            replace(null)
            return
        }
        if (_projectVm.value?.repo == repo) return
        val client = accountManager.clientFor(repo.account)
        val user = try {
            client.getCurrentUser()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The list still works; only the "me" filters are missing.
            thisLogger().warn("Failed to load the current Gitea user", e)
            null
        }
        val scope = cs.giteaChildScope("Gitea project ${repo.owner}/${repo.repo}")
        replace(GiteaProjectViewModel(scope, project, repo, client, user), scope)
    }

    private fun replace(vm: GiteaProjectViewModel?, scope: CoroutineScope? = null) {
        val oldScope = projectScope
        projectScope = scope
        _projectVm.value = vm
        oldScope?.cancel()
    }
}
