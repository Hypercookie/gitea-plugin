package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
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
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
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
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().escapeHtml(true).build()

    /** `escapeHtml(true)` escapes any literal HTML a commenter typed, so a
     * comment body can't inject markup. `<img>` tags are stripped from the
     * output afterward regardless — Swing's HTML view fetches `<img src>`
     * eagerly and unconditionally, so a comment's Markdown image syntax
     * would otherwise make the IDE fetch an arbitrary attacker-chosen URL
     * just from a reviewer opening the comment. */
    fun toHtml(markdown: String): String =
        renderer.render(parser.parse(markdown)).replace(Regex("<img[^>]*>"), "[image]")

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

/** Renders a Markdown comment body for a caller that *does* give its
 * component a real assigned width before layout — the editor-inlay comment
 * boxes, via [InlineComponentHost]'s `ComponentInlayAlignment.STRETCH_TO_CONTENT_WIDTH`.
 * A `JEditorPane` wraps against whatever width it's actually given, same as
 * the `JBTextArea` this replaces, but (unlike a text area) understands HTML. */
internal fun markdownView(text: String): JComponent {
    val body = MarkdownRendering.toHtml(text)
    return JEditorPane("text/html", "<html><head>${MarkdownRendering.STYLE}</head><body>$body</body></html>").apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.emptyTop(2)
        font = UIUtil.getLabelFont()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
    }
}

/**
 * Comments rendered as real boxes embedded in the editor (via
 * [InlineComponentHost]) — collapsed by default (avatar + author + a
 * snippet), click to expand into the full conversation with a reply box.
 * Replaces the old `showThreadPopup` JBPopup.
 */
class CommentThreadBoxPanel(
    private val project: Project,
    private var thread: List<PullReviewComment>,
    private val onReply: (text: String, onResult: (Result<Unit>) -> Unit) -> Unit,
    private val onSetResolved: (resolved: Boolean, onResult: (Result<Unit>) -> Unit) -> Unit,
    private val onResized: () -> Unit,
) : JBPanel<CommentThreadBoxPanel>(BorderLayout()) {

    private var expanded = false

    /** Gitea tracks resolution on the conversation's first comment. */
    private val resolver get() = thread.first().resolver

    init {
        maximumSize = Dimension(Int.MAX_VALUE, maximumSize.height)
        rebuild()
    }

    fun updateThread(newThread: List<PullReviewComment>) {
        thread = newThread
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        val content = boxPanel()
        content.add(if (expanded) expandedColumn() else collapsedRow(), BorderLayout.CENTER)
        add(content, BorderLayout.CENTER)
        revalidate()
        repaint()
        onResized()
    }

    private fun collapsedRow(): JComponent {
        val first = thread.first()
        val suffix = if (thread.size > 1) "  <span style='color:gray'>(+${thread.size - 1} more)</span>" else ""
        val resolvedTag = resolver?.let { "<span style='color:gray'>✓ Resolved</span>  " } ?: ""
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { isOpaque = false }
        row.add(avatarLabel(project, first.user))
        row.add(
            JBLabel(
                "<html>$resolvedTag<b>${first.user?.login ?: "unknown"}</b>  " +
                    "${escapeHtml(first.body.take(90)).replace("\n", " ")}$suffix</html>",
            ).apply {
                // Resolved conversations read as dimmed, matching the
                // GitHub plugin's collapsed-resolved look.
                if (resolver != null) foreground = UIUtil.getContextHelpForeground()
            },
        )
        row.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        row.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                expanded = true
                rebuild()
            }
        })
        return row
    }

    private fun expandedColumn(): JComponent {
        val column = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val header = JBLabel("▲ Collapse", SwingConstants.LEFT).apply {
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = JBColor.GRAY
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    expanded = false
                    rebuild()
                }
            })
        }
        column.add(header)

        thread.forEachIndexed { index, comment ->
            if (index > 0) column.add(JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 1) })
            val row = JBPanel<JBPanel<*>>(BorderLayout()).apply { isOpaque = false }
            val authorRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
                isOpaque = false
                add(avatarLabel(project, comment.user))
                add(JBLabel("<html><b>${comment.user?.login ?: "unknown"}</b></html>"))
            }
            row.add(authorRow, BorderLayout.NORTH)
            row.add(markdownView(comment.body), BorderLayout.CENTER)
            column.add(row)
        }

        val replyField = JBTextArea(2, 40).apply { lineWrap = true; wrapStyleWord = true }
        val replyButton = JButton("Reply")
        val statusLabel = JBLabel(" ")
        val currentResolver = resolver
        val resolveLink = ActionLink(if (currentResolver == null) "Resolve" else "Unresolve")
        resolveLink.addActionListener {
            resolveLink.isEnabled = false
            statusLabel.text = if (currentResolver == null) "Resolving…" else "Unresolving…"
            onSetResolved(currentResolver == null) { result ->
                // On success the controller re-renders the whole thread
                // from fresh comments, so only the failure path needs to
                // restore this panel's state.
                result.onFailure { error ->
                    resolveLink.isEnabled = true
                    statusLabel.text = "Failed: ${error.message}"
                }
            }
        }
        replyButton.addActionListener {
            val text = replyField.text.trim()
            if (text.isEmpty()) return@addActionListener
            replyButton.isEnabled = false
            statusLabel.text = "Sending…"
            onReply(text) { result ->
                replyButton.isEnabled = true
                result.fold(
                    onSuccess = {
                        replyField.text = ""
                        statusLabel.text = " "
                    },
                    onFailure = { error -> statusLabel.text = "Failed: ${error.message}" },
                )
            }
        }
        val replyRow = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(JBScrollPane(replyField), BorderLayout.CENTER)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                    isOpaque = false
                    add(statusLabel)
                    add(resolveLink)
                    add(replyButton)
                },
                BorderLayout.SOUTH,
            )
        }
        if (currentResolver != null) {
            column.add(
                JBLabel("Resolved by ${currentResolver.login}").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.emptyTop(4)
                },
            )
        }
        column.add(replyRow)

        return column
    }
}

/** Inline box for typing a brand-new (not yet submitted) review comment —
 * replaces the old `showAddCommentPopup` JBPopup. Launched from the gutter
 * "+" icon. */
class NewCommentBoxPanel(
    private val startLabel: String = "Start Review",
    private val onAdd: (text: String) -> Unit,
    private val onCancel: () -> Unit,
    private val onResized: () -> Unit,
) : JBPanel<NewCommentBoxPanel>(BorderLayout()) {

    private val textArea = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }

    init {
        val content = boxPanel()
        content.add(JBLabel("New comment (queued until you submit the review)"), BorderLayout.NORTH)
        content.add(JBScrollPane(textArea), BorderLayout.CENTER)
        val buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
            isOpaque = false
            add(JButton("Cancel").apply { addActionListener { onCancel() } })
            add(
                // "Start Review"/"Add to Review" (caller decides which,
                // based on whether a review is already pending), not "Add"
                // or a split button with "Add Single Comment" — this always
                // just queues into the pending review (see
                // PendingReviewStore), there's no separate immediate-submit
                // path backing a second option, so a real split button
                // would promise a capability that doesn't exist. The label
                // describes what actually happens.
                JButton(startLabel).apply {
                    addActionListener {
                        val text = textArea.text.trim()
                        if (text.isNotEmpty()) onAdd(text)
                    }
                },
            )
        }
        content.add(buttons, BorderLayout.SOUTH)
        add(content, BorderLayout.CENTER)
        onResized()
    }

    fun focusInput() {
        textArea.requestFocusInWindow()
    }
}

/** Inline box for a comment queued locally but not yet submitted — replaces
 * the old `showPendingPopup` JBPopup. */
class PendingCommentBoxPanel(
    private val pending: CreatePullReviewComment,
    private val onDiscard: () -> Unit,
    onResized: () -> Unit,
) : JBPanel<PendingCommentBoxPanel>(BorderLayout()) {

    init {
        val content = boxPanel()
        content.add(JBLabel("Pending — not yet submitted"), BorderLayout.NORTH)
        content.add(markdownView(pending.body), BorderLayout.CENTER)
        content.add(
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                isOpaque = false
                add(JButton("Discard").apply { addActionListener { onDiscard() } })
            },
            BorderLayout.SOUTH,
        )
        add(content, BorderLayout.CENTER)
        onResized()
    }
}
