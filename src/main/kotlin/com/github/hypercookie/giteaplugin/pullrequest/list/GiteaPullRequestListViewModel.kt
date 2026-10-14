package com.github.hypercookie.giteaplugin.pullrequest.list

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GiteaIssue
import com.github.hypercookie.giteaplugin.api.dto.GiteaLabel
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.collaboration.ui.codereview.list.ReviewListViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One row of the pull request list, built from either `/pulls` or the
 * issues endpoints (which return less: no mergeability, no reviewers). */
data class GiteaPullRequestListItem(
    val number: Long,
    val title: String,
    val state: String,
    val merged: Boolean,
    val draft: Boolean,
    val author: GiteaUser?,
    val labels: List<GiteaLabel>,
    val assignees: List<GiteaUser>,
    val reviewers: List<GiteaUser>,
    val comments: Int,
    val createdAt: String?,
    val htmlUrl: String?,
    val mergeable: Boolean?,
) {
    companion object {
        fun of(pr: PullRequest) = GiteaPullRequestListItem(
            pr.number, pr.title, pr.state, pr.merged, pr.draft, pr.user, pr.labels, pr.assignees.orEmpty(),
            pr.requestedReviewers.orEmpty(), pr.comments ?: 0, pr.createdAt, pr.htmlUrl, pr.mergeable,
        )

        fun of(issue: GiteaIssue) = GiteaPullRequestListItem(
            issue.number, issue.title, issue.state, issue.pullRequest?.merged == true, issue.pullRequest?.draft == true,
            issue.user, issue.labels, issue.assignees.orEmpty(), emptyList(), issue.comments, issue.createdAt,
            issue.pullRequest?.htmlUrl ?: issue.htmlUrl, null,
        )
    }
}

/**
 * Paged pull request list driven by the search panel. Replaces the
 * collaboration tools' internal list loaders (`ReloadablePotentiallyInfiniteListLoader`,
 * migration ledger I4) with a plain page counter.
 */
class GiteaPullRequestListViewModel(
    private val cs: CoroutineScope,
    private val repo: GiteaRepoContext,
    private val client: GiteaApiClient,
    val searchVm: GiteaSearchPanelViewModel,
) : ReviewListViewModel {

    private val _items = MutableStateFlow<List<GiteaPullRequestListItem>>(emptyList())
    val items: StateFlow<List<GiteaPullRequestListItem>> = _items.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    val error: StateFlow<Throwable?> = _error.asStateFlow()

    private val mutex = Mutex()
    private var nextPage = 1
    private var hasMore = true
    private var loadJob: Job? = null

    init {
        cs.launch {
            searchVm.searchState.collectLatest { reloadNow() }
        }
    }

    fun requestMore() {
        if (_isLoading.value || !hasMore || loadJob?.isActive == true) return
        loadJob = cs.launch { loadNextPage() }
    }

    override fun refresh() = reload()

    override fun reload() {
        cs.launch { reloadNow() }
    }

    private suspend fun reloadNow() {
        loadJob?.cancelAndJoin()
        mutex.withLock {
            nextPage = 1
            hasMore = true
            _items.value = emptyList()
            _error.value = null
        }
        loadNextPage()
    }

    private suspend fun loadNextPage() = mutex.withLock {
        if (!hasMore) return@withLock
        _isLoading.value = true
        try {
            val search = searchVm.searchState.value
            val (raw, rows) = fetchPage(search, nextPage)
            hasMore = raw >= PAGE_SIZE
            nextPage++
            _items.value = _items.value + rows
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _error.value = e
            hasMore = false
        } finally {
            _isLoading.value = false
        }
    }

    /** Returns (number of rows the server sent, rows after client-side filtering). */
    private suspend fun fetchPage(search: GiteaListSearchValue, page: Int): Pair<Int, List<GiteaPullRequestListItem>> {
        val apiState = when (search.state) {
            GiteaListSearchValue.State.OPEN -> "open"
            GiteaListSearchValue.State.CLOSED, GiteaListSearchValue.State.MERGED -> "closed"
            null -> "all"
        }
        val raw: List<GiteaPullRequestListItem> = when {
            search.reviewRequested == true -> client.searchReviewRequested(repo.owner, apiState, page, PAGE_SIZE)
                .also { if (it.size < PAGE_SIZE) hasMore = false }
                .filter { it.repository?.fullName == null || it.repository.fullName.equals("${repo.owner}/${repo.repo}", ignoreCase = true) }
                .map(GiteaPullRequestListItem::of)

            search.searchQuery != null || search.assignee != null || search.sort == null || search.sort == GiteaListSearchValue.Sort.NEWEST ->
                client.searchPullRequestIssues(
                    repo.owner, repo.repo, apiState, search.searchQuery, search.author, search.assignee,
                    search.label?.name, page, PAGE_SIZE,
                ).map(GiteaPullRequestListItem::of)

            else -> client.listPullRequestsPage(
                repo.owner, repo.repo, apiState, search.sort.apiValue, search.author, search.label?.id, page, PAGE_SIZE,
            ).map(GiteaPullRequestListItem::of)
        }
        val filtered = when (search.state) {
            GiteaListSearchValue.State.MERGED -> raw.filter { it.merged }
            GiteaListSearchValue.State.CLOSED -> raw.filter { !it.merged }
            else -> raw
        }
        return raw.size to filtered
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
