package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.InlayProperties
import com.intellij.openapi.editor.addComponentInlay
import com.intellij.openapi.editor.ex.EditorEx
import javax.swing.JComponent

/**
 * Embeds a real Swing [JComponent] inline in an editor as a block element,
 * so review comments render as actual boxes in the code instead of popups —
 * see docs/plan/pr-review-feature-plan.md, "UI pass" for the feasibility
 * check.
 *
 * Uses the top-level `com.intellij.openapi.editor.addComponentInlay`
 * function (public, same `ComponentInlay.kt` file as [ComponentInlayRenderer]),
 * **not** `EditorEx.inlayModel.addBlockElement` directly with a bare
 * `ComponentInlayRenderer`. The two are not equivalent: `addComponentInlay`
 * delegates to `com.intellij.openapi.editor.impl.ComponentInlayManager`,
 * which actually mounts the component into the editor's real Swing
 * hierarchy; `addBlockElement` alone just registers a custom-painted block
 * element and never mounts anything, so the component's children never get
 * a real layout pass and nothing but its own background paints. Confirmed
 * by disassembling the same call in JetBrains' own GitHub plugin
 * (`EditorComponentInlaysUtilKt.insertComponent`, `intellij.platform.collaborationTools.jar`)
 * — it calls this exact function instead of the raw `InlayModel` API.
 */
object InlineComponentHost {

    /** Installs [component] as a block inlay right below (or above, if
     * [showAbove]) the given 0-indexed line. Returns the [Inlay] — itself a
     * [com.intellij.openapi.Disposable]; `dispose()` to remove it,
     * [relayout] after changing the component's preferred size. */
    fun install(
        editor: EditorEx,
        line: Int,
        component: JComponent,
        showAbove: Boolean = false,
    ): Inlay<ComponentInlayRenderer<JComponent>>? {
        if (line < 0 || line >= editor.document.lineCount) return null
        val offset = editor.document.getLineStartOffset(line)
        val properties = InlayProperties()
            .showAbove(showAbove)
            .relatesToPrecedingText(true)
            .priority(0)
        return editor.addComponentInlay(offset, properties, component, ComponentInlayAlignment.STRETCH_TO_CONTENT_WIDTH)
    }

    /** Call after the hosted component's content/preferred size changed
     * (e.g. expand/collapse, a new comment appended). */
    fun relayout(inlay: Inlay<ComponentInlayRenderer<JComponent>>) {
        val component = inlay.renderer.component
        component.revalidate()
        component.repaint()
        inlay.update()
    }
}
