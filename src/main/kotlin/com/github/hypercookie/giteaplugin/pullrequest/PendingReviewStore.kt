package com.github.hypercookie.giteaplugin.pullrequest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment

/**
 * Comments the user has queued for this PR's review but not yet submitted —
 * one instance per PR (see [PendingReviewStoreService]), shared by the
 * editor, the diff viewers and the submit popup, so pending comments
 * persist when a file is closed and reopened. Purely in-memory; nothing here touches the network until submit.
 */
class PendingReviewStore {

    private val _pendingComments = mutableListOf<CreatePullReviewComment>()
    val pendingComments: List<CreatePullReviewComment> get() = _pendingComments.toList()

    private val _state = MutableStateFlow<List<CreatePullReviewComment>>(emptyList())

    /** Same content as [pendingComments], for flow-based view models. */
    val state: StateFlow<List<CreatePullReviewComment>> = _state.asStateFlow()

    private val listeners = mutableListOf<() -> Unit>()

    fun addComment(comment: CreatePullReviewComment) {
        _pendingComments += comment
        notifyListeners()
    }

    fun removeComment(comment: CreatePullReviewComment) {
        _pendingComments -= comment
        notifyListeners()
    }

    fun clear() {
        _pendingComments.clear()
        notifyListeners()
    }

    /** Called whenever the pending set changes, so any open diff viewer for
     * this PR can re-render its pending markers. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    private fun notifyListeners() {
        _state.value = _pendingComments.toList()
        listeners.forEach { it() }
    }
}
