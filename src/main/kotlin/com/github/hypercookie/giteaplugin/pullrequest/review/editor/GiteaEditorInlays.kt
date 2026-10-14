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

    class Thread(val vm: GiteaThreadViewModel) : GiteaEditorInlay() {
        override val key: Any = "thread:${vm.id}"
    }

    class Pending(val comment: CreatePullReviewComment, val onDiscard: () -> Unit) : GiteaEditorInlay() {
        override val key: Any = "pending:${System.identityHashCode(comment)}"
    }

    class NewComment(val vm: GiteaNewCommentViewModel) : GiteaEditorInlay() {
        override val key: Any = "new:${vm.side}:${vm.line}"
    }
}
