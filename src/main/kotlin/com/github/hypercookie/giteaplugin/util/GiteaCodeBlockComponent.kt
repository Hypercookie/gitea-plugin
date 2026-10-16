package com.github.hypercookie.giteaplugin.util

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.LayoutManager
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Apply for the code blocks of a comment: [apply] replaces the commented
 * code with a block's code. While [unavailableReason] is set the button is
 * disabled with that reason as its tooltip.
 */
class GiteaCodeBlockApply(
    val cs: CoroutineScope,
    val unavailableReason: StateFlow<String?>,
    val apply: (code: String) -> Unit,
)

/**
 * A fenced code block of a comment with its lines unwrapped: as wide as its
 * longest line ([naturalWidth]) when there is room, scrolling horizontally
 * when not. While the mouse is over the block, Copy and (when [apply] is
 * given) Apply show as icon buttons in its top right corner, over the code.
 */
class GiteaCodeBlockComponent(block: GiteaMarkdown.CodeBlock, apply: GiteaCodeBlockApply?) : JPanel() {

    private val code = GiteaMarkdown.createCodePane(block.html).apply { isOpaque = false }
    private val view = JPanel(BorderLayout()).apply {
        background = GiteaMarkdown.codeBackground()
        add(code)
    }
    private val scrollPane = CodeScrollPane(view)
    private val copyButton = iconButton(AllIcons.Actions.Copy, "Copy")
    private val buttonGap = JBUI.scale(6)
    private val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, buttonGap, 0)).apply {
        isOpaque = true
        background = GiteaMarkdown.codeBackground()
        border = JBUI.Borders.empty(4, 6)
        add(copyButton)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
        layout = OverlayCornerLayout()
        background = GiteaMarkdown.codeBackground()
        scrollPane.background = background
        scrollPane.viewport.background = background

        var revert: Timer? = null
        copyButton.actionListener = ActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(block.code))
            copyButton.icon = AllIcons.Actions.Checked
            copyButton.tooltip = "Copied"
            revert?.stop()
            revert = Timer(1500) {
                copyButton.icon = AllIcons.Actions.Copy
                copyButton.tooltip = "Copy"
            }.apply { isRepeats = false; start() }
        }
        if (apply != null) {
            // Same kind of button as Copy, right of it.
            val applyButton = iconButton(APPLY_ICON, APPLY_TOOLTIP).apply {
                name = APPLY_BUTTON_NAME
                actionListener = ActionListener { apply.apply(block.code) }
            }
            apply.cs.bindOnEdt(apply.unavailableReason) { reason ->
                applyButton.isEnabled = reason == null
                applyButton.tooltip = reason ?: APPLY_TOOLTIP
            }
            buttons.add(applyButton)
        }
        buttons.isVisible = false

        // Buttons first: the earlier child is painted on top.
        add(buttons)
        add(scrollPane)
        installHover()
    }

    private fun iconButton(icon: Icon, tooltip: String) =
        InlineIconButton(icon, disabledIcon = IconLoader.getDisabledIcon(icon), tooltip = tooltip)

    /** Width that shows every line unwrapped. */
    val naturalWidth: Int get() = scrollPane.naturalWidth + insets.left + insets.right

    // Buttons overlap the code.
    override fun isOptimizedDrawingEnabled(): Boolean = false

    private fun installHover() {
        val listener = object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) = setHovered(true)
            override fun mouseExited(e: MouseEvent) {
                // Moving onto a child exits the parent; only leaving the block counts.
                SwingUtilities.invokeLater { setHovered(getMousePosition(true) != null) }
            }
        }
        listOf(this, scrollPane, scrollPane.viewport, code, buttons, copyButton).forEach { it.addMouseListener(listener) }
        buttons.components.forEach { it.addMouseListener(listener) }
    }

    private fun setHovered(hovered: Boolean) {
        if (buttons.isVisible == hovered) return
        buttons.isVisible = hovered
        revalidate()
        repaint()
    }

    /** The scroll pane fills the block; the buttons sit in its top right corner. */
    private inner class OverlayCornerLayout : LayoutManager {
        override fun addLayoutComponent(name: String?, comp: Component?) = Unit
        override fun removeLayoutComponent(comp: Component?) = Unit

        override fun preferredLayoutSize(parent: Container): Dimension {
            val i = parent.insets
            val size = scrollPane.preferredSize
            return Dimension(size.width + i.left + i.right, size.height + i.top + i.bottom)
        }

        override fun minimumLayoutSize(parent: Container): Dimension =
            Dimension(0, preferredLayoutSize(parent).height)

        override fun layoutContainer(parent: Container) {
            val i = parent.insets
            val w = parent.width - i.left - i.right
            val h = parent.height - i.top - i.bottom
            scrollPane.setBounds(i.left, i.top, w, h)
            val size = buttons.preferredSize
            buttons.setBounds(i.left + (w - size.width).coerceAtLeast(0), i.top, size.width.coerceAtMost(w), size.height.coerceAtMost(h))
        }
    }
}

/** Component name of the Apply button. */
const val APPLY_BUTTON_NAME = "Gitea code block Apply"

/** The diff viewer's "apply this change" arrow. */
private val APPLY_ICON = AllIcons.Diff.Arrow
private const val APPLY_TOOLTIP = "Apply: replace the commented line with this code"

/**
 * Scrolls only horizontally, and only when narrower than its view. Its
 * height leaves room for the scroll bar when one shows, and vertical wheel
 * scrolling goes to the editor around it.
 */
private class CodeScrollPane(view: JComponent) : JBScrollPane(
    view,
    ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
) {
    private var scrolls = false

    init {
        border = JBUI.Borders.empty()
        viewportBorder = null
        isOpaque = true
        viewport.isOpaque = true
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (needsScrollBar() != scrolls) revalidate()
            }
        })
    }

    val naturalWidth: Int get() = viewport.view.preferredSize.width

    private fun needsScrollBar() = width in 1 until naturalWidth

    override fun getPreferredSize(): Dimension {
        val view = viewport.view.preferredSize
        scrolls = needsScrollBar()
        val bar = if (scrolls) horizontalScrollBar.preferredSize.height else 0
        return Dimension(view.width, view.height + bar)
    }

    override fun processMouseWheelEvent(e: MouseWheelEvent) {
        if (e.isShiftDown && scrolls) {
            super.processMouseWheelEvent(e)
            return
        }
        parent?.let { it.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, it)) }
    }
}
