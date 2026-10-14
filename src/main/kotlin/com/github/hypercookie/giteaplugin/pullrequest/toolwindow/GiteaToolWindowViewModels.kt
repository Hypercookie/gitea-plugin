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
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Tool window state: a connected [GiteaProjectViewModel], or null when no
 * Gitea remote or no token is configured (the platform then shows the
 * empty-state content).
 */
class GiteaToolWindowViewModel(private val cs: CoroutineScope, val project: Project) :
    ReviewToolwindowViewModel<GiteaProjectViewModel> {

    private val _projectVm = MutableStateFlow<GiteaProjectViewModel?>(null)
    override val projectVm: StateFlow<GiteaProjectViewModel?> = _projectVm.asStateFlow()

    private var projectScope: CoroutineScope? = null

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    init {
        connect()
    }

    /** Re-resolves the repository and account, e.g. after settings change. */
    fun connect() {
        cs.launch(Dispatchers.IO) {
            _connecting.value = true
            try {
                val repo = GiteaRepoLinkService.getInstance(project).resolveDefault()
                val token = repo?.let { GiteaAccountManager.getInstance().getToken(it.account) }
                val current = _projectVm.value
                if (repo == null || token.isNullOrBlank()) {
                    replace(null)
                    return@launch
                }
                if (current != null && current.repo == repo) return@launch
                val client = GiteaApiClient(repo.account.serverUrl, token)
                val user = runCatching { client.getCurrentUser() }.getOrNull()
                val scope = cs.giteaChildScope("Gitea project ${repo.owner}/${repo.repo}")
                replace(GiteaProjectViewModel(scope, project, repo, client, user), scope)
            } finally {
                _connecting.value = false
            }
        }
    }

    private fun replace(vm: GiteaProjectViewModel?, scope: CoroutineScope? = null) {
        val oldScope = projectScope
        projectScope = scope
        _projectVm.value = vm
        oldScope?.cancel()
    }
}
