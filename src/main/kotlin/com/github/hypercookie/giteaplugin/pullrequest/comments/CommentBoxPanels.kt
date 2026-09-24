package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JSeparator
import javax.swing.SwingConstants

private const val AVATAR_SIZE = 20

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

private fun avatarLabel(project: Project, user: GiteaUser?): JBLabel {
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

private fun boxPanel(): JBPanel<JBPanel<*>> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
    background = JBColor.namedColor("EditorTooltip.background", JBColor(0xF5F5F5, 0x4B4D51))
    border = JBUI.Borders.compound(
        RoundedLineBorder(JBColor.border(), 8, 1),
        JBUI.Borders.empty(6, 10),
    )
}

private fun readOnlyBody(text: String): JComponent =
    JBTextArea(text).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        border = JBUI.Borders.emptyTop(2)
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
    private val onResized: () -> Unit,
) : JBPanel<CommentThreadBoxPanel>(BorderLayout()) {

    private var expanded = false

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
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { isOpaque = false }
        row.add(avatarLabel(project, first.user))
        row.add(
            JBLabel(
                "<html><b>${first.user?.login ?: "unknown"}</b>  " +
                    "${escapeHtml(first.body.take(90)).replace("\n", " ")}$suffix</html>",
            ),
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
            row.add(readOnlyBody(comment.body), BorderLayout.CENTER)
            column.add(row)
        }

        val replyField = JBTextArea(2, 40).apply { lineWrap = true; wrapStyleWord = true }
        val replyButton = JButton("Reply")
        val statusLabel = JBLabel(" ")
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
                    add(replyButton)
                },
                BorderLayout.SOUTH,
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
                JButton("Add").apply {
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
        content.add(readOnlyBody(pending.body), BorderLayout.CENTER)
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
