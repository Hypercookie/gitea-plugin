package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Comments the user has queued for this PR's review but not yet submitted —
 * one instance per PR (see [PendingReviewStoreService]), shared by the
 * editor, the diff viewers and the submit popup, so pending comments
 * persist when a file is closed and reopened. Nothing here touches the
 * network until submit; [PendingReviewStoreService] saves the drafts with
 * the project, so they also survive an IDE restart.
 *
 * Safe to use from any thread: the EDT adds comments while the submit runs
 * in a coroutine.
 */
class PendingReviewStore(initial: List<CreatePullReviewComment> = emptyList()) {

    private val _state = MutableStateFlow(initial)

    /** The pending comments, in the order they were added. */
    val state: StateFlow<List<CreatePullReviewComment>> = _state.asStateFlow()

    val pendingComments: List<CreatePullReviewComment> get() = _state.value

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addComment(comment: CreatePullReviewComment) {
        _state.update { it + comment }
        notifyListeners()
    }

    fun removeComment(comment: CreatePullReviewComment) {
        _state.update { it - comment }
        notifyListeners()
    }

    /** Removes exactly [comments] (the ones a submit sent), keeping any
     * added meanwhile. */
    fun removeAll(comments: List<CreatePullReviewComment>) {
        _state.update { current ->
            val remaining = current.toMutableList()
            comments.forEach { remaining.remove(it) }
            remaining
        }
        notifyListeners()
    }

    fun clear() {
        _state.value = emptyList()
        notifyListeners()
    }

    /** Called whenever the pending set changes, so any open diff viewer for
     * this PR can re-render its pending markers. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun notifyListeners() {
        listeners.forEach { it() }
    }
}
