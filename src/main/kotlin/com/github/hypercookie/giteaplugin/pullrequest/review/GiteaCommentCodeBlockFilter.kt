package com.github.hypercookie.giteaplugin.pullrequest.review

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile

/**
 * Hides errors and warnings inside the fenced code blocks of a comment
 * field. The Markdown plugin injects the fence's language, which gives
 * completion and highlighting, but analyses the snippet as a whole file: a
 * Java statement is reported as "class or interface expected" because the
 * class around it is missing. A snippet is not meant to compile on its own,
 * so only those problems are dropped; completion, syntax colors and the
 * problems of the Markdown itself stay. The Markdown plugin's own
 * "Show problems in code blocks" setting does the same, but for every
 * Markdown file of the project.
 */
class GiteaCommentCodeBlockFilter : HighlightInfoFilter {

    override fun accept(highlightInfo: HighlightInfo, psiFile: PsiFile?): Boolean {
        if (psiFile == null || highlightInfo.severity in KEPT_SEVERITIES) return true
        val manager = InjectedLanguageManager.getInstance(psiFile.project)
        if (!manager.isInjectedFragment(psiFile)) return true
        val document = manager.getTopLevelFile(psiFile)?.viewProvider?.document ?: return true
        return document.getUserData(COMMENT_FIELD) != true
    }

    companion object {
        private val COMMENT_FIELD: Key<Boolean> = Key.create("Gitea.Comment.Field")

        /** Severities that color code rather than report a problem (as the Markdown plugin keeps them). */
        private val KEPT_SEVERITIES: Set<HighlightSeverity> = setOf(
            HighlightInfoType.SYMBOL_TYPE_SEVERITY,
            HighlightInfoType.INJECTED_FRAGMENT_SYNTAX_SEVERITY,
            HighlightInfoType.INJECTED_FRAGMENT_SEVERITY,
            HighlightInfoType.ELEMENT_UNDER_CARET_SEVERITY,
            HighlightInfoType.HIGHLIGHTED_REFERENCE_SEVERITY,
            HighlightSeverity.TEXT_ATTRIBUTES,
        )

        /** Marks [editor] as a comment field, whose code blocks this filter quiets. */
        fun mark(editor: Editor) {
            editor.document.putUserData(COMMENT_FIELD, true)
        }
    }
}
