package com.github.hypercookie.giteaplugin.pullrequest.review.mention

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CompletionAutoPopupTester
import com.intellij.util.ThrowableRunnable

/** Typing `@` must open the suggestion popup on its own, without Ctrl+Space. */
class GiteaMentionAutoPopupTest : BasePlatformTestCase() {

    private lateinit var tester: CompletionAutoPopupTester

    override fun setUp() {
        super.setUp()
        tester = CompletionAutoPopupTester(myFixture)
    }

    override fun runInDispatchThread(): Boolean = false

    override fun runTestRunnable(testRunnable: ThrowableRunnable<Throwable>) {
        tester.runWithAutoPopupEnabled(testRunnable)
    }

    fun testAtOpensPopupWithUsers() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
            myFixture.configureByText(PlainTextFileType.INSTANCE, "Thanks <caret>")
            GiteaMentionSource.install(myFixture.editor) {
                listOf(GiteaMentionCandidate(GiteaUser(id = 1, login = "alice"), 1.0))
            }
        }
        tester.typeWithPauses("@")
        val lookup = myFixture.lookup
        assertNotNull("popup opened on @", lookup)
        assertEquals(listOf("@alice"), lookup.items.map { it.lookupString })
    }
}
