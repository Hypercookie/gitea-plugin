package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.diff.DiffLines.Change
import org.junit.Assert.assertEquals
import org.junit.Test

class DiffLinesTest {

    // Lines 2-3 replaced by one line, line 6 removed, two lines added before 9.
    private val changes = listOf(Change(2, 4, 2, 3), Change(6, 7, 5, 5), Change(9, 9, 7, 9))

    @Test
    fun `unchanged lines before any change stay`() {
        assertEquals(0, DiffLines.transfer(changes, 0))
        assertEquals(1, DiffLines.transfer(changes, 1))
    }

    @Test
    fun `changed lines go to the start of their replacement`() {
        assertEquals(2, DiffLines.transfer(changes, 2))
        assertEquals(2, DiffLines.transfer(changes, 3))
    }

    @Test
    fun `removed line goes to where it was removed`() {
        assertEquals(5, DiffLines.transfer(changes, 6))
    }

    @Test
    fun `unchanged lines move by the changes before them`() {
        assertEquals(3, DiffLines.transfer(changes, 4))
        assertEquals(4, DiffLines.transfer(changes, 5))
        assertEquals(5, DiffLines.transfer(changes, 7))
        assertEquals(6, DiffLines.transfer(changes, 8))
        assertEquals(9, DiffLines.transfer(changes, 9))
        assertEquals(11, DiffLines.transfer(changes, 11))
    }
}
