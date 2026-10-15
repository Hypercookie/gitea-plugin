package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/**
 * Review state of the unified diff viewer's one editor, which shows both
 * versions of a file: inlays of a [ReviewSide.NEW] and an [ReviewSide.OLD]
 * model, each mapping its own version's lines into the editor (see
 * [EditorLineTransfer]), behind one set of gutter controls.
 *
 * A new comment goes to the new side wherever the new version has the line
 * (added and unchanged lines), and to the old side only on deleted lines,
 * like Gitea's web diff. Takes the place of the GitLab plugin's
 * `UnifiedCodeReviewItemPosition` handling (migration ledger I16).
 *
 * Either model is null when that side of the diff is empty (an added or
 * deleted file).
 */
class GiteaUnifiedReviewEditorModel(
    cs: CoroutineScope,
    private val new: CodeReviewEditorModel<GiteaEditorInlay>?,
    private val old: CodeReviewEditorModel<GiteaEditorInlay>?,
) : CodeReviewEditorModel<GiteaEditorInlay> {

    override val inlays: StateFlow<Collection<GiteaEditorInlay>> =
        combine(new?.inlays ?: flowOf(emptyList()), old?.inlays ?: flowOf(emptyList())) { n, o -> n + o }
            .stateIn(cs, SharingStarted.Eagerly, emptyList())

    override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> =
        combine(new?.gutterControlsState ?: flowOf(null), old?.gutterControlsState ?: flowOf(null)) { n, o ->
            if (n == null && o == null) null else ControlsState(listOfNotNull(n, o))
        }.stateIn(cs, SharingStarted.Eagerly, null)

    private class ControlsState(
        private val states: List<CodeReviewEditorGutterControlsModel.ControlsState>,
    ) : CodeReviewEditorGutterControlsModel.ControlsState {
        override val linesWithComments: Set<Int> = states.flatMapTo(HashSet()) { it.linesWithComments }
        override val linesWithNewComments: Set<Int> = states.flatMapTo(HashSet()) { it.linesWithNewComments }
        override fun isLineCommentable(lineIdx: Int): Boolean = states.any { it.isLineCommentable(lineIdx) }
    }

    /** The side model a new comment on editor line [lineIdx] belongs to. */
    private fun modelFor(lineIdx: Int): CodeReviewEditorModel<GiteaEditorInlay>? = when (
        commentSide(
            newCommentable = new?.gutterControlsState?.value?.isLineCommentable(lineIdx) == true,
            oldCommentable = old?.gutterControlsState?.value?.isLineCommentable(lineIdx) == true,
        )
    ) {
        ReviewSide.NEW -> new
        ReviewSide.OLD -> old
        null -> null
    }

    override fun canCreateComment(lineIdx: Int): Boolean = modelFor(lineIdx) != null

    override fun requestNewComment(lineIdx: Int) {
        modelFor(lineIdx)?.requestNewComment(lineIdx)
    }

    override fun cancelNewComment(lineIdx: Int) {
        modelFor(lineIdx)?.cancelNewComment(lineIdx)
    }

    override fun toggleComments(lineIdx: Int) {
        new?.toggleComments(lineIdx)
        old?.toggleComments(lineIdx)
    }

    companion object {
        /** Side of a new comment on a unified diff line that the new and/or
         * old side can take a comment on: new wins, so only a deleted line
         * gets an old-side comment. */
        fun commentSide(newCommentable: Boolean, oldCommentable: Boolean): ReviewSide? = when {
            newCommentable -> ReviewSide.NEW
            oldCommentable -> ReviewSide.OLD
            else -> null
        }
    }
}
