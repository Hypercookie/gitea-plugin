package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment

/**
 * Comments the user has queued for this PR's review but not yet submitted —
 * one instance per opened PR detail tab, shared across every diff viewer
 * opened for that PR (so pending comments persist if a file's diff is closed
 * and reopened, and Phase 9's submit action can see comments added from any
 * file). Purely in-memory; nothing here touches the network until submit.
 */
class PendingReviewStore {

    private val _pendingComments = mutableListOf<CreatePullReviewComment>()
    val pendingComments: List<CreatePullReviewComment> get() = _pendingComments.toList()

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
        listeners.forEach { it() }
    }
}
