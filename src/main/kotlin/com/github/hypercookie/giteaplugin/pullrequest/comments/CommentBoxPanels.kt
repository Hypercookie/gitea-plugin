package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.primaryButton
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JSeparator
import javax.swing.SwingConstants

private const val AVATAR_SIZE = 20

/** `internal`, not `private` — reused by `TimelinePanel` (Phase 27) so the
 * timeline feed's comment rendering matches the gutter comment boxes. */
internal fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun avatarLabel(project: Project, user: GiteaUser?): JBLabel {
    val label = JBLabel(AllIcons.General.User)
    val url = user?.avatarUrl
    if (!url.isNullOrBlank()) {
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val icon: Icon? = AvatarService.loadIcon(url, AVATAR_SIZE)
            withContext(Dispatchers.Main) {
                if (icon != null) label.icon = icon
            }
        }
    }
    return label
}

/** A panel with genuinely rounded corners: background and border painted as
 * one filled/stroked round-rect, not a square fill behind a separate rounded
 * border (the old `RoundedLineBorder` approach) — that combination leaves
 * the square fill peeking out past the border's curve at each corner.
 * Confirmed this is the right shape by checking what JetBrains' own GitHub/
 * GitLab comment boxes are built on: `javap` on the bundled
 * `intellij.platform.collaborationTools.jar` shows a
 * `com.intellij.collaboration.ui.codereview.comment.RoundedPanel` doing the
 * same thing (plus double-buffering/alpha-blending machinery this plugin
 * doesn't need) — not depended on directly, since we don't take a
 * dependency on the bundled GitHub plugin; this is our own, much simpler
 * version of the same idea. */
internal class RoundedCardPanel(layout: LayoutManager, private val arc: Int = 10) : JBPanel<RoundedCardPanel>(layout) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(8, 10)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = BACKGROUND
        g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.color = BORDER_COLOR
        g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        g2.dispose()
        super.paintComponent(g)
    }

    companion object {
        private val BACKGROUND = JBColor.namedColor("EditorTooltip.background", JBColor(0xF5F5F5, 0x4B4D51))
        private val BORDER_COLOR = JBColor.border()
    }
}

/** The one comment "card" shape used everywhere a Gitea comment is shown
 * (editor-inlay threads, timeline feed) — same background/border/radius/
 * padding regardless of caller, so they actually look the same instead of
 * independently-styled lookalikes. */
internal fun boxPanel(): JComponent = RoundedCardPanel(BorderLayout())

private object MarkdownRendering {
    fun toHtml(markdown: String): String = com.github.hypercookie.giteaplugin.util.GiteaMarkdown.toHtml(markdown)

    // Zeroes <p> margins (commonmark wraps every block in <p>, which
    // otherwise carries Swing's default HTML paragraph spacing) and keeps
    // inline code in a monospace font.
    const val STYLE = "<style>p{margin:0 0 6px 0}p:last-child{margin-bottom:0}code{font-family:monospace}</style>"
}

/** Renders a Markdown comment body at a fixed pixel width — for a caller
 * with no other way to give the component a known width before layout
 * (e.g. [com.github.hypercookie.giteaplugin.pullrequest.TimelinePanel]'s
 * plain `BoxLayout` column in a scroll pane). A `JBLabel`'s HTML view only
 * wraps correctly when told an explicit width up front. */
internal fun markdownLabel(text: String, widthPx: Int): JComponent {
    val body = MarkdownRendering.toHtml(text)
    return JBLabel("<html><head>${MarkdownRendering.STYLE}</head><body style='width: ${widthPx}px'>$body</body></html>").apply {
        border = JBUI.Borders.emptyTop(2)
        verticalAlignment = JBLabel.TOP
    }
}
