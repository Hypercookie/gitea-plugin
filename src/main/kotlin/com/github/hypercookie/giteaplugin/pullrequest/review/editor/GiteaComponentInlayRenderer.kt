package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.util.GiteaCodeBlockComponent
import com.intellij.collaboration.ui.codereview.CodeReviewChatItemUIUtil
import com.intellij.collaboration.ui.layout.SizeRestrictedSingleComponentLayout
import com.intellij.collaboration.ui.util.DimensionRestrictions
import com.intellij.openapi.editor.ComponentInlayAlignment
import com.intellij.openapi.editor.ComponentInlayRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Like the platform's `CodeReviewComponentInlayRenderer`, which caps an
 * inlay at the chat text width, but widened to fit the widest code block in
 * it so code lines aren't squeezed. The editor still caps it at its visible
 * width; a code block wider than that scrolls on its own.
 */
internal class GiteaComponentInlayRenderer(component: JComponent) :
    ComponentInlayRenderer<JComponent>(wrapWithFittingWidth(component), ComponentInlayAlignment.FIT_VIEWPORT_WIDTH)

/** What the platform renderer adds to the text width for the comment's
 * avatar column and paddings. */
private const val CHROME_WIDTH = 52

private fun wrapWithFittingWidth(component: JComponent): JComponent {
    val width = object : DimensionRestrictions {
        override fun getWidth(): Int = maxOf(JBUI.scale(CodeReviewChatItemUIUtil.TEXT_CONTENT_WIDTH + CHROME_WIDTH), widestCodeBlock(component))
        override fun getHeight(): Int? = null
    }
    val layout = SizeRestrictedSingleComponentLayout().apply {
        prefSize = width
        maxSize = width
    }
    return JPanel(layout).apply {
        isOpaque = false
        add(component)
    }
}

/** Width [root] needs to show its widest visible code block unwrapped. */
private fun widestCodeBlock(root: JComponent): Int =
    UIUtil.uiTraverser(root).filter(GiteaCodeBlockComponent::class.java)
        .filter { block -> generateSequence<Component>(block) { it.parent }.takeWhile { it !== root }.all { it.isVisible } }
        .maxOfOrNull { block ->
            // Everything around the block (avatar column, paddings, borders):
            // measured once laid out, estimated before that.
            val chrome = if (block.width > 0 && root.width > block.width) root.width - block.width else JBUI.scale(CHROME_WIDTH)
            block.naturalWidth + chrome
        } ?: 0
