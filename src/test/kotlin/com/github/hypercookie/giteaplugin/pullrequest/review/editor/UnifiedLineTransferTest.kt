package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedLineTransferTest {

    // Base "a b c d", head "a X c d e". The unified editor shows:
    //   0 a (both)  1 b (old 1)  2 X (new 1)  3 c (both)  4 d (both)  5 e (new 4)
    // Tables as the viewer's transferLineFromOnesideStrict returns them.
    private val oldLines = EditorLineTransfer.of(intArrayOf(0, 1, -1, 2, 3, -1))
    private val newLines = EditorLineTransfer.of(intArrayOf(0, -1, 1, 2, 3, 4))

    private val cs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = cs.cancel()

    @Test
    fun sideLinesMoveToTheirUnifiedLines() {
        assertEquals(0, newLines.toEditor(0))
        assertEquals(2, newLines.toEditor(1))
        assertEquals(5, newLines.toEditor(4))
        assertEquals(1, oldLines.toEditor(1))
        assertEquals(4, oldLines.toEditor(3))
        assertNull(newLines.toEditor(5))
    }

    @Test
    fun unifiedLinesOfTheOtherSideHaveNoCounterpart() {
        assertNull(newLines.fromEditor(1))
        assertNull(oldLines.fromEditor(2))
        assertNull(oldLines.fromEditor(5))
        assertNull(newLines.fromEditor(6))
        assertEquals(3, newLines.fromEditor(4))
        assertEquals(3, oldLines.fromEditor(4))
    }

    @Test
    fun emptyLayoutMapsNothing() {
        val empty = EditorLineTransfer.of(IntArray(0))
        assertNull(empty.toEditor(0))
        assertNull(empty.fromEditor(0))
    }

    @Test
    fun deletedLineCommentsGoToOldSideOthersToNew() {
        val new = FakeSideModel(newLines)
        val old = FakeSideModel(oldLines)
        val model = GiteaUnifiedReviewEditorModel(cs, new, old)

        for (line in 0..5) model.requestNewComment(line)
        assertEquals(listOf(0, 2, 3, 4, 5), new.requested)
        assertEquals(listOf(1), old.requested)
    }

    @Test
    fun gutterStateIsTheUnionOfBothSides() {
        val model = GiteaUnifiedReviewEditorModel(cs, FakeSideModel(newLines, comments = setOf(2)), FakeSideModel(oldLines, comments = setOf(1)))
        val state = model.gutterControlsState.value!!
        assertEquals(setOf(1, 2), state.linesWithComments)
        assertTrue((0..5).all(state::isLineCommentable))
        assertFalse(state.isLineCommentable(6))
    }

    @Test
    fun addedFileHasOnlyTheNewSide() {
        val new = FakeSideModel(EditorLineTransfer.Identity)
        val model = GiteaUnifiedReviewEditorModel(cs, new, null)
        model.requestNewComment(3)
        assertEquals(listOf(3), new.requested)
        assertEquals(ReviewSide.OLD, GiteaUnifiedReviewEditorModel.commentSide(newCommentable = false, oldCommentable = true))
        assertNull(GiteaUnifiedReviewEditorModel.commentSide(newCommentable = false, oldCommentable = false))
    }

    /** A side model whose every line of its own version can take a comment. */
    private class FakeSideModel(lines: EditorLineTransfer, comments: Set<Int> = emptySet()) : CodeReviewEditorModel<GiteaEditorInlay> {
        val requested = mutableListOf<Int>()

        override val inlays: StateFlow<Collection<GiteaEditorInlay>> = MutableStateFlow(emptyList())
        override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> = MutableStateFlow(
            object : CodeReviewEditorGutterControlsModel.ControlsState {
                override val linesWithComments: Set<Int> = comments
                override fun isLineCommentable(lineIdx: Int): Boolean = lines.fromEditor(lineIdx) != null
            },
        )

        override fun canCreateComment(lineIdx: Int): Boolean = gutterControlsState.value?.isLineCommentable(lineIdx) == true
        override fun requestNewComment(lineIdx: Int) {
            requested += lineIdx
        }

        override fun toggleComments(lineIdx: Int) = Unit
    }
}
