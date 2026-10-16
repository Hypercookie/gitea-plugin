package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaCodeBlockComponent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
        val apply = applyLink(GiteaReviewEditorComponents.create(cs, project, inlay, myFixture.editor))
        assertTrue(apply.isEnabled)

        apply.doClick()
        assertEquals("one\nTWO\n  2\nthree", myFixture.editor.document.text)
    }

    fun testApplyDisabledWhileOutdated() {
        myFixture.configureByText("a.txt", "one\ntwo")
        val inlay = pending("```\nx\n```", line = 0)
        val apply = applyLink(GiteaReviewEditorComponents.create(cs, project, inlay, myFixture.editor))
        inlay.updateOutdated(true, keepExpanded = true)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertFalse(apply.isEnabled)
    }

    fun testNoApplyInReadOnlyEditor() {
        myFixture.configureByText("a.txt", "one")
        myFixture.editor.document.setReadOnly(true)
        val component = GiteaReviewEditorComponents.create(cs, project, pending("```\nx\n```", line = 0), myFixture.editor)
        assertNotNull(UIUtil.findComponentOfType(component, GiteaCodeBlockComponent::class.java))
        assertTrue(UIUtil.findComponentsOfType(component, ActionLink::class.java).none { it.text == "Apply" })
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

    private fun applyLink(component: JComponent): ActionLink {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        return UIUtil.findComponentsOfType(component, ActionLink::class.java).single { it.text == "Apply" }
    }
}
