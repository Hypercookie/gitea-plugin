package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.intellij.collaboration.ui.codereview.comment.CodeReviewSubmittableTextViewModelBase
import com.intellij.collaboration.ui.codereview.timeline.thread.CodeReviewResolvableItemViewModel
import com.intellij.openapi.diagnostic.thisLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One review conversation as shown in an editor inlay. Mirrors the shape of
 * the GitHub plugin's `GHPRCompactReviewThreadViewModel` (reply toggling,
 * resolve state) on Gitea data.
 */
class GiteaThreadViewModel(
    private val cs: CoroutineScope,
    val data: GiteaPullRequestReviewData,
    initial: ReviewThread,
) : CodeReviewResolvableItemViewModel {

    private val _thread = MutableStateFlow(initial)
    val thread: StateFlow<ReviewThread> = _thread.asStateFlow()

    val id: Long get() = _thread.value.id

    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    override val isResolved: StateFlow<Boolean> = _thread.map { it.isResolved }.stateIn(cs, SharingStarted.Eagerly, initial.isResolved)
    /** Gitea 1.26+; older servers have no resolve endpoint. */
    override val canChangeResolvedState: StateFlow<Boolean> = data.serverFeatures.map { it.resolveComments }
        .stateIn(cs, SharingStarted.Eagerly, data.serverFeatures.value.resolveComments)

    /** Gitea 1.27+; older servers have no reply endpoint. */
    val canReply: StateFlow<Boolean> = data.serverFeatures.map { it.replyToComments }
        .stateIn(cs, SharingStarted.Eagerly, data.serverFeatures.value.replyToComments)

    private val _isWritingReply = MutableStateFlow(false)
    val isWritingReply: StateFlow<Boolean> = _isWritingReply.asStateFlow()

    val replyVm = GiteaReplyViewModel(cs, this)

    fun update(thread: ReviewThread) {
        _thread.value = thread
    }

    fun startWritingReply() {
        _isWritingReply.value = true
        replyVm.requestFocus()
    }

    fun stopWritingReply() {
        _isWritingReply.value = false
    }

    override fun changeResolvedState() {
        if (_isBusy.value) return
        _isBusy.value = true
        cs.launch {
            try {
                data.setResolved(_thread.value, !_thread.value.isResolved)
            } catch (e: Exception) {
                thisLogger().warn("Failed to change resolved state of thread ${_thread.value.id}", e)
            } finally {
                _isBusy.value = false
            }
        }
    }
}

class GiteaReplyViewModel(
    cs: CoroutineScope,
    private val threadVm: GiteaThreadViewModel,
) : CodeReviewSubmittableTextViewModelBase(threadVm.data.project, cs, "") {

    val currentUser: StateFlow<GiteaUser?> get() = threadVm.data.currentUser

    fun submitReply() = submit { text ->
        threadVm.data.reply(threadVm.thread.value, text)
        this.text.value = ""
        threadVm.stopWritingReply()
    }
}

/**
 * Compose box for a brand-new comment on [headLine] (0-based line of the
 * file at the PR head). Offers the same submit choices as GitHub's
 * `GHPRReviewNewCommentEditorViewModel`: start or extend the pending review,
 * or post a single comment right away.
 */
class GiteaNewCommentViewModel(
    cs: CoroutineScope,
    val data: GiteaPullRequestReviewData,
    val path: String,
    val side: ReviewSide,
    val line: Int,
    private val onDone: () -> Unit,
) : CodeReviewSubmittableTextViewModelBase(data.project, cs, "") {

    val currentUser: StateFlow<GiteaUser?> get() = data.currentUser

    /** True once a review is pending: the primary action then reads
     * "Add Review Comment" instead of "Start Review". */
    val hasPendingReview: StateFlow<Boolean> = data.pending.map { it.isNotEmpty() }
        .stateIn(cs, SharingStarted.Eagerly, data.pending.value.isNotEmpty())

    private fun comment(body: String) = CreatePullReviewComment(
        body = body,
        path = path,
        newPosition = if (side == ReviewSide.NEW) line + 1L else 0,
        oldPosition = if (side == ReviewSide.OLD) line + 1L else 0,
    )

    fun addToReview() = submit { body ->
        data.pendingStore.addComment(comment(body))
        onDone()
    }

    fun postSingle() = submit { body ->
        data.postSingleComment(comment(body))
        onDone()
    }

    fun cancel() = onDone()
}
