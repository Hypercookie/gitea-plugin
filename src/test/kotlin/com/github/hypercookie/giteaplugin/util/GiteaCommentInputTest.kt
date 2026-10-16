package com.github.hypercookie.giteaplugin.util

import com.intellij.collaboration.ui.codereview.comment.CodeReviewSubmittableTextViewModelBase
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.event.ActionEvent

/** Submit actions of the comment inputs stay disabled while the text is blank. */
class GiteaCommentInputTest : BasePlatformTestCase() {

    private class Vm(project: Project, cs: CoroutineScope) : CodeReviewSubmittableTextViewModelBase(project, cs, "")

    fun testEnabledOnlyWithNonBlankText() {
        val cs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vm = Vm(project, cs)
            var submitted = 0
            val action = vm.submitAction(cs, "Comment") { submitted++ }
            assertFalse(action.isEnabled)

            vm.text.value = "  \n\t "
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(action.isEnabled)
            // The shortcut path calls the action directly; it must not submit either.
            action.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, null))
            assertEquals(0, submitted)

            vm.text.value = "looks good"
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertTrue(action.isEnabled)
            action.actionPerformed(ActionEvent(this, ActionEvent.ACTION_PERFORMED, null))
            assertEquals(1, submitted)

            vm.text.value = ""
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            assertFalse(action.isEnabled)
        } finally {
            cs.cancel()
        }
    }
}
