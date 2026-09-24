package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.ex.EditorEx
import javax.swing.JComponent

/**
 * Embeds a real Swing [JComponent] inline in an editor as a block element,
 * so review comments render as actual boxes in the code instead of popups —
 * see docs/plan/pr-review-feature-plan.md, "UI pass" for the feasibility
 * check on [ComponentInlayRenderer] (public, `@ApiStatus.Experimental`, the
 * same base class JetBrains' own GitHub plugin builds its PR-review inline
 * UI on, reached without the internal `collaboration-tools` layer).
 */
object InlineComponentHost {

    /** Installs [component] as a block inlay right below (or above, if
     * [showAbove]) the given 0-indexed line. Returns the [Inlay] — itself a
     * [com.intellij.openapi.Disposable]; `dispose()` to remove it, `.update()`
     * after changing the component's preferred size (e.g. expand/collapse). */
    fun install(
        editor: EditorEx,
        line: Int,
        component: JComponent,
        showAbove: Boolean = false,
    ): Inlay<ComponentInlayRenderer<JComponent>>? {
        if (line < 0 || line >= editor.document.lineCount) return null
        val offset = editor.document.getLineStartOffset(line)
        val renderer = ComponentInlayRenderer(component, ComponentInlayAlignment.STRETCH_TO_CONTENT_WIDTH)
        val properties = InlayProperties()
            .showAbove(showAbove)
            .relatesToPrecedingText(true)
            .priority(0)
        return editor.inlayModel.addBlockElement(offset, properties, renderer)
    }
}
