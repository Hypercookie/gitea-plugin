package com.github.hypercookie.giteaplugin.pullrequest.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineMappingTest {

    // 10-line file; side 2 replaces lines 3..4 with three lines and deletes line 7.
    private val blocks = listOf(
        ChangedBlock(3, 5, 3, 6),
        ChangedBlock(7, 8, 8, 8),
    )

    @Test
    fun linesBeforeFirstBlockAreUnchanged() {
        assertEquals(0, LineMapping.transfer(blocks, 0))
        assertEquals(2, LineMapping.transfer(blocks, 2))
    }

    @Test
    fun linesInsideChangedBlockHaveNoCounterpart() {
        assertNull(LineMapping.transfer(blocks, 3))
        assertNull(LineMapping.transfer(blocks, 4))
        assertNull(LineMapping.transfer(blocks, 7))
    }

    @Test
    fun linesAfterBlocksAreShifted() {
        assertEquals(6, LineMapping.transfer(blocks, 5))
        assertEquals(7, LineMapping.transfer(blocks, 6))
        assertEquals(8, LineMapping.transfer(blocks, 8))
        assertEquals(9, LineMapping.transfer(blocks, 9))
    }

    @Test
    fun pureInsertionShiftsFollowingLines() {
        val insertion = listOf(ChangedBlock(2, 2, 2, 5))
        assertEquals(1, LineMapping.transfer(insertion, 1))
        assertEquals(5, LineMapping.transfer(insertion, 2))
    }

    @Test
    fun inverseMapsDocumentBackAndRejectsLocalEdits() {
        // head -> document: user typed two new lines at document lines 4..5.
        val headToDoc = listOf(ChangedBlock(4, 4, 4, 6))
        val docToHead = LineMapping.inverse(headToDoc)
        assertEquals(3, LineMapping.transfer(docToHead, 3))
        assertNull(LineMapping.transfer(docToHead, 4))
        assertNull(LineMapping.transfer(docToHead, 5))
        assertEquals(4, LineMapping.transfer(docToHead, 6))
    }

    @Test
    fun clampedTransferLandsOnBlockStart() {
        assertEquals(3, LineMapping.transferClamped(blocks, 4))
        assertEquals(8, LineMapping.transferClamped(blocks, 7))
        assertEquals(6, LineMapping.transferClamped(blocks, 5))
    }

    @Test
    fun commentOnUnchangedLineIsPlacedExactly() {
        assertEquals(CommentPlacement(2, isOutdated = false), LineMapping.placeComment(blocks, 2, lastLine = 9))
        assertEquals(CommentPlacement(6, isOutdated = false), LineMapping.placeComment(blocks, 5, lastLine = 9))
    }

    @Test
    fun commentOnChangedLineIsOutdatedAtBlockStart() {
        assertEquals(CommentPlacement(3, isOutdated = true), LineMapping.placeComment(blocks, 3, lastLine = 9))
        assertEquals(CommentPlacement(3, isOutdated = true), LineMapping.placeComment(blocks, 4, lastLine = 9))
    }

    @Test
    fun commentOnDeletedLineIsOutdatedAtFollowingLine() {
        assertEquals(CommentPlacement(8, isOutdated = true), LineMapping.placeComment(blocks, 7, lastLine = 9))
    }

    @Test
    fun commentOnLineDeletedAtEndOfFileStaysOnLastLine() {
        // 5-line file, side 2 deletes the last two lines.
        val deletion = listOf(ChangedBlock(3, 5, 3, 3))
        assertEquals(CommentPlacement(2, isOutdated = true), LineMapping.placeComment(deletion, 4, lastLine = 2))
        assertEquals(CommentPlacement(0, isOutdated = true), LineMapping.placeComment(listOf(ChangedBlock(0, 1, 0, 0)), 0, lastLine = -1))
    }

    @Test
    fun undoingAnEditMakesCommentCurrentAgain() {
        // Edit: the commented line 4 is replaced by two lines; undo: no changes left.
        val edited = listOf(ChangedBlock(4, 5, 4, 6))
        assertEquals(CommentPlacement(4, isOutdated = true), LineMapping.placeComment(edited, 4, lastLine = 6))
        assertEquals(CommentPlacement(4, isOutdated = false), LineMapping.placeComment(emptyList(), 4, lastLine = 5))
    }

    @Test
    fun prChangesIgnoreLocalEditsOutsideThem() {
        // PR changed head lines 2..3; user inserted 3 lines at document line 0.
        val baseToHead = listOf(ChangedBlock(2, 3, 2, 4))
        val headToDoc = listOf(ChangedBlock(0, 0, 0, 3))
        val result = LineMapping.prChangesInDocument(baseToHead, headToDoc)
        assertEquals(listOf(ChangedBlock(2, 3, 5, 7)), result)
    }

    @Test
    fun prDeletionStaysEmptyInDocument() {
        val baseToHead = listOf(ChangedBlock(2, 4, 2, 2))
        val result = LineMapping.prChangesInDocument(baseToHead, emptyList())
        assertEquals(listOf(ChangedBlock(2, 4, 2, 2)), result)
    }
}
