package com.github.hypercookie.giteaplugin.pullrequest.review

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.openapi.progress.DumbProgressIndicator

/**
 * One changed region between two versions of a file, 0-based, end-exclusive
 * on both sides. A pure insertion has `start1 == end1`, a pure deletion
 * `start2 == end2`. Blocks in a list are sorted and don't overlap.
 *
 * Own replacement for the official plugins' `ReviewInEditorUtil` line
 * transfer helpers, which are `@ApiStatus.Internal` (migration ledger I6).
 */
data class ChangedBlock(val start1: Int, val end1: Int, val start2: Int, val end2: Int) {
    fun swapped() = ChangedBlock(start2, end2, start1, end1)
}

/** Side-2 line a review comment is shown on, and whether its own line is gone. */
data class CommentPlacement(val line: Int, val isOutdated: Boolean)

object LineMapping {

    /** Changed blocks from [before] to [after], via the platform diff engine. */
    fun compare(before: CharSequence, after: CharSequence): List<ChangedBlock> =
        ComparisonManager.getInstance()
            .compareLines(before, after, ComparisonPolicy.DEFAULT, DumbProgressIndicator.INSTANCE)
            .map { ChangedBlock(it.startLine1, it.endLine1, it.startLine2, it.endLine2) }

    /**
     * Where side-1 [line] ends up on side 2, or null when that line was
     * changed or deleted (it has no unchanged counterpart).
     */
    fun transfer(blocks: List<ChangedBlock>, line: Int): Int? {
        var shift = 0
        for (block in blocks) {
            if (line < block.start1) return line + shift
            if (line < block.end1) return null
            shift = block.end2 - block.end1
        }
        return line + shift
    }

    /** Like [transfer], but a line inside a changed block maps to the start
     * of that block's side-2 range instead of null. Used for range bounds,
     * which must always land somewhere. */
    fun transferClamped(blocks: List<ChangedBlock>, line: Int): Int {
        var shift = 0
        for (block in blocks) {
            if (line < block.start1) return line + shift
            if (line < block.end1) return block.start2
            shift = block.end2 - block.end1
        }
        return line + shift
    }

    /**
     * Where a comment on side-1 [line] is shown on side 2, whose last line
     * is [lastLine]. A line with an unchanged counterpart goes there; a
     * changed or deleted one is outdated and stays at the start of the
     * block that replaced it, like GitHub's web UI. Gitea has no outdated
     * flag of its own (migration ledger G10).
     */
    fun placeComment(blocks: List<ChangedBlock>, line: Int, lastLine: Int): CommentPlacement {
        transfer(blocks, line)?.let { return CommentPlacement(it, isOutdated = false) }
        // A deletion at the end of the file starts past the last line.
        return CommentPlacement(transferClamped(blocks, line).coerceIn(0, maxOf(lastLine, 0)), isOutdated = true)
    }

    fun inverse(blocks: List<ChangedBlock>): List<ChangedBlock> = blocks.map { it.swapped() }

    /**
     * Pushes the pull request's own changes (merge base -> head) through the
     * head -> document mapping, so the result describes PR changes in
     * document coordinates without counting the user's local edits as PR
     * changes. Side 1 of each result block stays in merge-base lines.
     */
    fun prChangesInDocument(baseToHead: List<ChangedBlock>, headToDocument: List<ChangedBlock>): List<ChangedBlock> =
        baseToHead.map { block ->
            val start = transferClamped(headToDocument, block.start2)
            val end = if (block.end2 == block.start2) start else maxOf(start, transferClamped(headToDocument, block.end2))
            ChangedBlock(block.start1, block.end1, start, end)
        }
}
