package com.github.hypercookie.giteaplugin.pullrequest.review

import com.intellij.lang.Language
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.injection.MultiHostInjector
import com.intellij.lang.injection.MultiHostRegistrar
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.xml.XmlText
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * An injected fragment stands in for a comment's code block: a lone `(` is
 * an unclosed RegExp group, the kind of "incomplete file" error a snippet
 * gets. Marked comment fields drop it; other editors keep it.
 */
class GiteaCommentCodeBlockFilterTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        val regExp = Language.findLanguageByID("RegExp")!!
        InjectedLanguageManager.getInstance(project).registerMultiHostInjector(object : MultiHostInjector {
            override fun getLanguagesToInject(registrar: MultiHostRegistrar, context: PsiElement) {
                registrar.startInjecting(regExp)
                    .addPlace(null, null, context as PsiLanguageInjectionHost, TextRange(0, context.textLength))
                    .doneInjecting()
            }

            override fun elementsToInjectIn(): List<Class<out PsiElement>> = listOf(XmlText::class.java)
        }, testRootDisposable)
    }

    private fun injectedErrors(markAsComment: Boolean): List<String> {
        myFixture.configureByText("a.xml", "<a>(</a>")
        if (markAsComment) GiteaCommentCodeBlockFilter.mark(myFixture.editor)
        return myFixture.doHighlighting(HighlightSeverity.ERROR).map { it.description }
    }

    fun testErrorsInCodeBlockShownOutsideCommentFields() {
        assertFalse(injectedErrors(markAsComment = false).isEmpty())
    }

    fun testErrorsInCodeBlockHiddenInCommentFields() {
        assertEquals(emptyList<String>(), injectedErrors(markAsComment = true))
    }

    fun testErrorsOutsideCodeBlocksStayInCommentFields() {
        myFixture.configureByText("a.xml", "<a>x</b>")
        GiteaCommentCodeBlockFilter.mark(myFixture.editor)
        assertFalse(myFixture.doHighlighting(HighlightSeverity.ERROR).isEmpty())
    }
}
