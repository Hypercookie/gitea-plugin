package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffContext
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.util.Side
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Pins down how the platform's one-sided and unified viewers report sides and lines, which
 * [com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaCommentDiffExtension]
 * turns into an [EditorLineTransfer] per side after each rediff.
 */
class UnifiedDiffViewerLinesTest : BasePlatformTestCase() {

    private val context = object : DiffContext() {
        override fun getProject(): Project = this@UnifiedDiffViewerLinesTest.project
        override fun isWindowFocused(): Boolean = false
        override fun isFocusedInWindow(): Boolean = false
        override fun requestFocusInWindow() = Unit
    }

    /** An added file: the one-sided viewer shows the head (new) side. */
    fun testOnesideViewerOfAddedFileShowsRightSide() {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest("t", factory.createEmpty(), factory.create("a\nb\n"), null, null)
        val viewer = SimpleOnesideDiffViewer(context, request)
        try {
            assertEquals(Side.RIGHT, viewer.side)
            assertEquals("a\nb\n", viewer.editor.document.text)
        } finally {
            Disposer.dispose(viewer)
        }
    }

    fun testStrictTransferGivesEachSidesLineAtAUnifiedLine() {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest("t", factory.create("a\nb\nc\nd\n"), factory.create("a\nX\nc\nd\ne\n"), null, null)
        val viewer = UnifiedDiffViewer(context, request)
        try {
            var rediffs = 0
            viewer.addListener(object : DiffViewerListener() {
                override fun onAfterRediff() {
                    rediffs++
                }
            })
            viewer.init()
            PlatformTestUtil.waitWhileBusy { viewer.hasPendingRediff() || rediffs == 0 }
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

            // The viewer may append a separator line after the last one.
            assertTrue(viewer.editor.document.text.startsWith("a\nb\nX\nc\nd\ne\n"))
            val lineCount = viewer.editor.document.lineCount
            val old = IntArray(lineCount) { viewer.transferLineFromOnesideStrict(Side.LEFT, it) }.toList()
            val new = IntArray(lineCount) { viewer.transferLineFromOnesideStrict(Side.RIGHT, it) }.toList()
            assertEquals(listOf(0, 1, -1, 2, 3, -1), old.take(6))
            assertEquals(listOf(0, -1, 1, 2, 3, 4), new.take(6))

            val newLines = EditorLineTransfer.of(new.toIntArray())
            assertEquals(viewer.transferLineToOnesideStrict(Side.RIGHT, 4), newLines.toEditor(4))
            assertEquals(2, newLines.toEditor(1))
        } finally {
            Disposer.dispose(viewer)
        }
    }
}
