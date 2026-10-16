package com.github.hypercookie.giteaplugin.pullrequest.timeline

import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.TimelineEvent
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Timeline events and commits of [data]'s pull request, fetched once and
 * again on [reload] or whenever [data] is reloaded (the pull request's
 * Refresh action, the tool window's Refresh, coming back to the IDE).
 *
 * A newer load replaces one still in flight, so an older response can't
 * overwrite a newer one.
 */
internal class GiteaTimelineLoader(cs: CoroutineScope, private val data: GiteaPullRequestReviewData) {

    sealed interface State {
        data class Items(val events: List<TimelineEvent>, val commits: List<GitCommit>) : State
        data class Error(val error: Throwable) : State
        data object Loading : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    private val requests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        cs.launch {
            requests.collectLatest {
                val loaded = try {
                    coroutineScope {
                        val events = async { data.client.getTimeline(data.owner, data.repoName, data.number) }
                        val commits = async { data.commits() }
                        State.Items(events.await(), commits.await())
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    State.Error(e)
                }
                _state.value = loaded
            }
        }
        cs.launch { data.reloads.collect { requests.emit(Unit) } }
        // A push shows up as a new head after a reload's timeline request
        // went out; its commits (and the push event) need another one.
        cs.launch { data.pullRequest.map { it.head.sha }.distinctUntilChanged().drop(1).collect { requests.emit(Unit) } }
        requests.tryEmit(Unit)
    }

    /** Fetches the timeline and its reactions again. */
    fun reload() {
        if (GiteaSettings.getInstance().showReactions) data.reactions.refresh()
        requests.tryEmit(Unit)
    }
}
