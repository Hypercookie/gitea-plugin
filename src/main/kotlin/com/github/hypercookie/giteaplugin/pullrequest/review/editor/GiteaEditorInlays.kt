package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaNewCommentViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaThreadViewModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewInlayModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Inlays shown in a review editor; same split as the GitHub plugin's
 * `GHPREditorMappedComponentModel` (thread / new comment), plus pending
 * comments, which GitHub renders as pending threads from its own API but
 * Gitea keeps only in our local [com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore].
 *
 * Instances are stable per [key]; the editor model updates [line] and
 * visibility in place so `renderInlays` keeps the existing component.
 */
sealed class GiteaEditorInlay : CodeReviewInlayModel {
    val lineState = MutableStateFlow<Int?>(null)
    override val line: StateFlow<Int?> get() = lineState

    val hiddenState = MutableStateFlow(false)
    val visibleState = MutableStateFlow(true)
    override val isVisible: StateFlow<Boolean> get() = visibleState

    /** The comment's own line was changed; [line] is then the start of
     * the block that replaced it (see `LineMapping.placeComment`). */
    val outdatedState = MutableStateFlow(false)

    /** An outdated comment shows only a one-line summary until expanded.
     * Unlike [hiddenState] the inlay stays in the editor. */
    val collapsedState = MutableStateFlow(false)

    /** Sets [outdatedState] and collapses or expands on a change of it;
     * [keepExpanded] (a reply being written) skips collapsing. */
    fun updateOutdated(outdated: Boolean, keepExpanded: Boolean = false) {
        collapsedState.value = collapsedAfter(outdatedState.value, outdated, collapsedState.value, keepExpanded)
        outdatedState.value = outdated
    }

    class Thread(val vm: GiteaThreadViewModel) : GiteaEditorInlay() {
        override val key: Any = "thread:${vm.id}"
    }

    class Pending(
        val comment: CreatePullReviewComment,
        /** The current user, who wrote the pending comment. */
        val author: StateFlow<com.github.hypercookie.giteaplugin.api.dto.GiteaUser?>,
        val onDiscard: () -> Unit,
    ) : GiteaEditorInlay() {
        override val key: Any = "pending:${System.identityHashCode(comment)}"
    }

    class NewComment(val vm: GiteaNewCommentViewModel) : GiteaEditorInlay() {
        override val key: Any = "new:${vm.side}:${vm.line}"
    }

    companion object {
        /** Collapsed state after an outdated update: collapse when a comment
         * becomes outdated (unless [keepExpanded]), expand when it becomes
         * current again, otherwise keep what the user chose. */
        fun collapsedAfter(wasOutdated: Boolean, outdated: Boolean, collapsed: Boolean, keepExpanded: Boolean): Boolean = when {
            !outdated -> false
            !wasOutdated -> !keepExpanded
            else -> collapsed
        }
    }
}
