package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaCodeBlockComponent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.github.hypercookie.giteaplugin.util.APPLY_BUTTON_NAME
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import java.awt.event.ActionEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent

/** Code blocks in review inlays: Apply replaces the commented line, and the
 * inlay widens to fit a long code line. */
class GiteaCodeBlockInlayTest : BasePlatformTestCase() {

    private lateinit var cs: CoroutineScope

    override fun setUp() {
        super.setUp()
        cs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun tearDown() {
        try {
            cs.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testApplyReplacesCommentedLine() {
        myFixture.configureByText("a.txt", "one\ntwo\nthree")
        val inlay = pending("Use this:\n\n```\nTWO\n  2\n```", line = 1)
        val apply = applyButton(GiteaReviewEditorComponents.create(cs, project, inlay, myFixture.editor))
        assertTrue(apply.isEnabled)

        apply.actionListener!!.actionPerformed(ActionEvent(apply, ActionEvent.ACTION_PERFORMED, null))
        assertEquals("one\nTWO\n  2\nthree", myFixture.editor.document.text)
    }

    fun testApplyDisabledWhileOutdated() {
        myFixture.configureByText("a.txt", "one\ntwo")
        val inlay = pending("```\nx\n```", line = 0)
        val apply = applyButton(GiteaReviewEditorComponents.create(cs, project, inlay, myFixture.editor))
        inlay.updateOutdated(true, keepExpanded = true)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse(apply.isEnabled)
    }

    fun testApplyDisabledInReadOnlyEditor() {
        myFixture.configureByText("a.txt", "one")
        myFixture.editor.document.setReadOnly(true)
        val apply = applyButton(GiteaReviewEditorComponents.create(cs, project, pending("```\nx\n```", line = 0), myFixture.editor))
        assertFalse(apply.isEnabled)
        assertTrue(apply.tooltip!!, apply.tooltip!!.contains("read-only"))
    }

    fun testCopyAppearsOnHoverNextToApply() {
        myFixture.configureByText("a.txt", "one")
        val component = GiteaReviewEditorComponents.create(cs, project, pending("```\nx\n```", line = 0), myFixture.editor)
        val block = UIUtil.findComponentOfType(component, GiteaCodeBlockComponent::class.java)!!
        val buttons = UIUtil.findComponentsOfType(block, InlineIconButton::class.java)
        assertEquals(2, buttons.size)
        val copy = buttons.single { it.name != APPLY_BUTTON_NAME }
        assertFalse(copy.isVisible)
        block.dispatchEvent(MouseEvent(block, MouseEvent.MOUSE_ENTERED, 0, 0, 1, 1, 0, false))
        assertTrue(copy.isVisible)
        assertSame("same parent as Apply", applyButton(component).parent, copy.parent)
    }

    fun testInlayWidensToLongestCodeLine() {
        myFixture.configureByText("a.txt", "one")
        val short = GiteaComponentInlayRenderer(GiteaReviewEditorComponents.create(cs, project, pending("```\nx\n```", 0), myFixture.editor))
        val long = GiteaComponentInlayRenderer(
            GiteaReviewEditorComponents.create(cs, project, pending("```\n${"x".repeat(400)}\n```", 0), myFixture.editor),
        )
        val shortWidth = short.component.preferredSize.width
        val longWidth = long.component.preferredSize.width
        assertTrue("$longWidth > $shortWidth", longWidth > shortWidth)
        val block = UIUtil.findComponentOfType(long.component, GiteaCodeBlockComponent::class.java)!!
        assertTrue("$longWidth >= ${block.naturalWidth}", longWidth >= block.naturalWidth)
    }

    private fun pending(body: String, line: Int) =
        GiteaEditorInlay.Pending(CreatePullReviewComment(body, "a.txt", newPosition = line + 1L), MutableStateFlow(null)) {}.apply {
            lineState.value = line
        }

    private fun applyButton(component: JComponent): InlineIconButton {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return UIUtil.findComponentsOfType(component, InlineIconButton::class.java).single { it.name == APPLY_BUTTON_NAME }
    }
}
