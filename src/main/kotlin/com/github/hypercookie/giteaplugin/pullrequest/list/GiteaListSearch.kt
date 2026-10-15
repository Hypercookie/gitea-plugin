package com.github.hypercookie.giteaplugin.pullrequest.list

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GiteaLabel
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.collaboration.ui.codereview.list.search.ReviewListQuickFilter
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchHistoryModel
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchPanelViewModelBase
import com.intellij.collaboration.ui.codereview.list.search.ReviewListSearchValue
import kotlinx.coroutines.CoroutineScope

/**
 * Pull request list filter, the Gitea counterpart of the GitHub plugin's
 * `GHPRListSearchValue`. GitHub's review-state filters (approved, changes
 * requested, reviewed by me...) are left out: Gitea's list endpoints can't
 * filter on review state. "Review requests" is kept
 * as [reviewRequested], served by `/repos/issues/search`.
 */
data class GiteaListSearchValue(
    override val searchQuery: String? = null,
    val state: State? = null,
    val author: String? = null,
    val label: GiteaLabel? = null,
    val assignee: String? = null,
    val reviewRequested: Boolean? = null,
    val sort: Sort? = null,
) : ReviewListSearchValue {

    override val filterCount: Int
        get() = listOfNotNull(searchQuery, state, author, label, assignee, reviewRequested, sort).size

    enum class State { OPEN, CLOSED, MERGED }

    /** Gitea `/pulls` `sort` values; null means Gitea's default (newest). */
    enum class Sort(val apiValue: String?) {
        NEWEST(null),
        OLDEST("oldest"),
        MOST_COMMENTED("mostcomment"),
        LEAST_COMMENTED("leastcomment"),
        RECENTLY_UPDATED("recentupdate"),
        LEAST_RECENTLY_UPDATED("leastupdate"),
    }

    companion object {
        val EMPTY = GiteaListSearchValue()
    }
}

sealed class GiteaListQuickFilter(user: GiteaUser?) : ReviewListQuickFilter<GiteaListSearchValue> {
    protected val login: String? = user?.login

    class Open(user: GiteaUser?) : GiteaListQuickFilter(user) {
        override val filter = GiteaListSearchValue(state = GiteaListSearchValue.State.OPEN)
    }

    class YourPullRequests(user: GiteaUser?) : GiteaListQuickFilter(user) {
        override val filter = GiteaListSearchValue(state = GiteaListSearchValue.State.OPEN, author = login)
    }

    class AssignedToYou(user: GiteaUser?) : GiteaListQuickFilter(user) {
        override val filter = GiteaListSearchValue(state = GiteaListSearchValue.State.OPEN, assignee = login)
    }

    class ReviewRequests(user: GiteaUser?) : GiteaListQuickFilter(user) {
        override val filter = GiteaListSearchValue(state = GiteaListSearchValue.State.OPEN, reviewRequested = true)
    }
}

/** Search history kept for the IDE session only. The GitHub plugin
 * persists it in a project-level state component; not needed yet. */
class GiteaListSearchHistoryModel : ReviewListSearchHistoryModel<GiteaListSearchValue> {
    private val history = ArrayDeque<GiteaListSearchValue>()
    override var lastFilter: GiteaListSearchValue? = null
    override fun getHistory(): List<GiteaListSearchValue> = history.toList()
    override fun add(search: GiteaListSearchValue) {
        history.remove(search)
        history.addLast(search)
        while (history.size > HISTORY_LIMIT) history.removeFirst()
    }

    private companion object {
        const val HISTORY_LIMIT = 10
    }
}

class GiteaSearchPanelViewModel(
    scope: CoroutineScope,
    private val repo: GiteaRepoContext,
    private val client: GiteaApiClient,
    historyModel: GiteaListSearchHistoryModel,
    currentUser: GiteaUser?,
) : ReviewListSearchPanelViewModelBase<GiteaListSearchValue, GiteaListQuickFilter>(
    scope,
    historyModel,
    emptySearch = GiteaListSearchValue.EMPTY,
    defaultFilter = GiteaListQuickFilter.Open(currentUser).filter,
) {
    override fun GiteaListSearchValue.withQuery(query: String?) = copy(searchQuery = query)

    override val quickFilters: List<GiteaListQuickFilter> = listOf(
        GiteaListQuickFilter.Open(currentUser),
        GiteaListQuickFilter.YourPullRequests(currentUser),
        GiteaListQuickFilter.AssignedToYou(currentUser),
        GiteaListQuickFilter.ReviewRequests(currentUser),
    )

    val stateFilterState = searchState.partialState(GiteaListSearchValue::state) { copy(state = it) }
    val authorFilterState = searchState.partialState(GiteaListSearchValue::author) { copy(author = it) }
    val labelFilterState = searchState.partialState(GiteaListSearchValue::label) { copy(label = it) }
    val assigneeFilterState = searchState.partialState(GiteaListSearchValue::assignee) { copy(assignee = it) }
    val sortFilterState = searchState.partialState(GiteaListSearchValue::sort) { copy(sort = it) }

    suspend fun getUsers(): List<GiteaUser> = client.listAssignees(repo.owner, repo.repo)
    suspend fun getLabels(): List<GiteaLabel> = client.listLabels(repo.owner, repo.repo)
}
