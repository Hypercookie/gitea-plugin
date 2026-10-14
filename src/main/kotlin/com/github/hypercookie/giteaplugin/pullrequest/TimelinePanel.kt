package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.PullPushBody
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.TimelineEvent
import com.github.hypercookie.giteaplugin.pullrequest.comments.avatarLabel
import com.github.hypercookie.giteaplugin.pullrequest.comments.boxPanel
import com.github.hypercookie.giteaplugin.pullrequest.comments.escapeHtml
import com.github.hypercookie.giteaplugin.pullrequest.comments.markdownLabel
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.primaryButton
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Chronological PR activity feed: top-level comments, reviews, and commit
 * pushes in one timeline, plus a compose box at the bottom for a new
 * top-level comment — mirrors the reference UI's "View Timeline" tab
 * (docs/plan/pr-review-feature-plan.md Phase 27). Backed by Gitea's
 * `/issues/{index}/timeline` (PRs are issues) + `/pulls/{index}/commits` —
 * both confirmed via a live-instance spike for this phase; only the event
 * `type`s actually observed in real data (`comment`, `review`, `pull_push`)
 * get dedicated rendering, everything else falls back to a generic line.
 */
class TimelinePanel(
    private val project: Project,
    private val context: GiteaRepoContext,
    private val client: GiteaApiClient,
    private val pr: PullRequest,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val feedColumn = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val statusLabel = JBLabel("Loading timeline…").apply { border = JBUI.Borders.empty(4, 8) }
    private val composeField = JBTextArea(3, 40).apply { lineWrap = true; wrapStyleWord = true }

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(statusLabel, BorderLayout.NORTH)
        add(JBScrollPane(feedColumn), BorderLayout.CENTER)
        add(buildComposeRow(), BorderLayout.SOUTH)
    }

    init {
        reload()
    }

    private fun buildComposeRow(): JComponent {
        val button = primaryButton("Comment")
        val rowStatus = JBLabel(" ")
        button.addActionListener {
            val text = composeField.text.trim()
            if (text.isEmpty()) return@addActionListener
            button.isEnabled = false
            rowStatus.text = "Posting…"
            GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
                val result = runCatching { client.createIssueComment(context.owner, context.repo, pr.number, text) }
                withContext(Dispatchers.Main) {
                    button.isEnabled = true
                    result.fold(
                        onSuccess = {
                            composeField.text = ""
                            rowStatus.text = " "
                            reload()
                        },
                        onFailure = { error -> rowStatus.text = "Failed: ${error.message}" },
                    )
                }
            }
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(6, 8)
            add(JBScrollPane(composeField), BorderLayout.CENTER)
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT)).apply {
                    add(rowStatus)
                    add(button)
                },
                BorderLayout.SOUTH,
            )
        }
    }

    private fun reload() {
        statusLabel.text = "Loading timeline…"
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching {
                val eventsDeferred = async { client.getTimeline(context.owner, context.repo, pr.number) }
                val commitsDeferred = async { client.getPullRequestCommits(context.owner, context.repo, pr.number) }
                eventsDeferred.await() to commitsDeferred.await()
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { (events, commits) -> render(events, commits) },
                    onFailure = { error -> statusLabel.text = "Failed to load timeline: ${error.message}" },
                )
            }
        }
    }

    private fun render(events: List<TimelineEvent>, commits: List<GitCommit>) {
        statusLabel.text = " "
        feedColumn.removeAll()
        val commitsBySha = commits.associateBy { it.sha }
        events.forEachIndexed { index, event ->
            val spec = renderEvent(event, commitsBySha)
            val row = timelineRow(spec.dotColor, drawAbove = index > 0, drawBelow = index < events.lastIndex, spec.content)
            feedColumn.add(capHeight(row))
        }
        feedColumn.revalidate()
        feedColumn.repaint()
    }

    /** One row = a small left "rail" (connecting line + a colored dot,
     * [TimelineGutter]) plus the event's own content to the right — the
     * "look like a timeline" request. The line is drawn per-row rather than
     * as one continuous shape spanning the whole feed, but since each row's
     * gutter is painted for that row's *full* height (including whatever
     * bottom margin the content itself carries) and rows sit flush against
     * each other in `feedColumn`'s `BoxLayout`, consecutive segments line up
     * into what reads as one unbroken line. */
    private fun timelineRow(dotColor: JBColor, drawAbove: Boolean, drawBelow: Boolean, content: JComponent): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(TimelineGutter(dotColor, drawAbove, drawBelow), BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }

    /** A `BoxLayout` column asks every child's [JComponent.getMaximumSize] to
     * decide how to divide up any leftover vertical space in the scroll
     * viewport. A plain `JPanel` reports a huge default maximum (unbounded),
     * so without this, `BoxLayout` stretches every row to fill the leftover
     * space evenly — the "huge boxes with a blank gap at the bottom" bug.
     * Must run *after* a component's children are added, so its
     * `preferredSize` reflects real content. */
    private fun capHeight(component: JComponent): JComponent {
        component.maximumSize = Dimension(Int.MAX_VALUE, component.preferredSize.height)
        return component
    }

    private fun renderEvent(event: TimelineEvent, commitsBySha: Map<String, GitCommit>): RowSpec =
        when (event.type) {
            "comment" -> if (!event.body.isNullOrBlank()) RowSpec(commentCard(event), COMMENT_DOT) else RowSpec(systemLine(event, "commented"), OTHER_DOT)
            "review" -> if (!event.body.isNullOrBlank()) RowSpec(commentCard(event), REVIEW_DOT) else RowSpec(systemLine(event, "reviewed this pull request"), REVIEW_DOT)
            "pull_push" -> RowSpec(pushCard(event, commitsBySha), PUSH_DOT)
            else -> RowSpec(systemLine(event, event.type.replace('_', ' ')), OTHER_DOT)
        }

    private fun commentCard(event: TimelineEvent): JComponent {
        val wrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply { border = JBUI.Borders.empty(0, 8, 10, 0) }
        val content = boxPanel()
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(avatarLabel(project, event.user))
            add(JBLabel("<html><b>${event.user?.login ?: "unknown"}</b> <span style='color:gray'>${formatTimestamp(event.createdAt)}</span></html>"))
        }
        content.add(header, BorderLayout.NORTH)
        // Markdown rendering/styling shared with the editor-inlay comment
        // boxes (CommentBoxPanels.kt) — same look everywhere a Gitea
        // comment shows up, not an independently-styled lookalike. This
        // call site needs the fixed-width variant since, unlike an editor
        // inlay, nothing here hands the label a known width before layout.
        content.add(markdownLabel(event.body ?: "", widthPx = 440), BorderLayout.CENTER)
        wrapper.add(content, BorderLayout.CENTER)
        return wrapper
    }

    private fun systemLine(event: TimelineEvent, text: String): JComponent {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { border = JBUI.Borders.empty(2, 8, 6, 0) }
        row.add(avatarLabel(project, event.user))
        row.add(JBLabel("<html><b>${event.user?.login ?: "unknown"}</b> $text</html>"))
        return row
    }

    private fun pushCard(event: TimelineEvent, commitsBySha: Map<String, GitCommit>): JComponent {
        val push = event.body?.let { runCatching { json.decodeFromString<PullPushBody>(it) }.getOrNull() }
        val commitIds = push?.commitIds.orEmpty()

        val column = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 8, 6, 0)
        }
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(avatarLabel(project, event.user))
            add(JBLabel("<html><b>${event.user?.login ?: "unknown"}</b> added ${commitIds.size} commit(s) to this pull request</html>"))
        }
        column.add(capHeight(header))

        commitIds.forEach { sha ->
            val commit = commitsBySha[sha]
            val line = if (commit != null) {
                val shortSha = sha.take(7)
                val message = commit.commit.message.lineSequence().first()
                "<html><code>$shortSha</code> ${escapeHtml(message)} " +
                    "<span style='color:gray'>${commit.author?.login ?: commit.commit.author.name}, ${formatTimestamp(commit.commit.author.date)}</span></html>"
            } else {
                "<html><code>${sha.take(7)}</code></html>"
            }
            column.add(
                capHeight(
                    JBPanel<JBPanel<*>>(BorderLayout()).apply {
                        border = JBUI.Borders.empty(1, 30, 1, 0)
                        add(JBLabel(line), BorderLayout.CENTER)
                    },
                ),
            )
        }
        return column
    }
}

/** `2026-09-24T08:51:40+02:00` -> `2026-09-24 08:51`. No timezone handling —
 * matches the raw precision the API gives us, good enough for a dev feed. */
private fun formatTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val withoutZone = iso.substringBefore('+').substringBefore('Z')
    return withoutZone.replace('T', ' ').take(16)
}

private data class RowSpec(val content: JComponent, val dotColor: JBColor)

// Reuses the same color pairs this codebase already uses for "merged"
// (purple) and diff additions (green) elsewhere — the list's
// MERGED_COLOR and PullRequestDetailPanel's ADD_COLOR — rather than
// inventing a new palette just for this.
private val COMMENT_DOT = JBColor(0x0969DA, 0x58A6FF)
private val REVIEW_DOT = JBColor(0x8250DF, 0xA371F7)
private val PUSH_DOT = JBColor(0x1A7F37, 0x3FB950)
private val OTHER_DOT = JBColor.GRAY

/** The left "rail" of one timeline row: a colored dot marking this event,
 * connected to its neighbors by a vertical line. [drawAbove]/[drawBelow]
 * skip the line past the first/last row so it doesn't overshoot the feed.
 * Sized by [BorderLayout] to match its row's actual height (its own
 * `preferredSize` is just a sane minimum) — see [TimelinePanel.timelineRow]. */
private class TimelineGutter(
    private val dotColor: JBColor,
    private val drawAbove: Boolean,
    private val drawBelow: Boolean,
) : JComponent() {

    init {
        isOpaque = false
        preferredSize = Dimension(WIDTH, DOT_Y + DOT_RADIUS * 2)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val centerX = width / 2
        g2.color = JBColor.border()
        g2.stroke = BasicStroke(1.5f)
        if (drawAbove) g2.drawLine(centerX, 0, centerX, DOT_Y - DOT_RADIUS - 1)
        if (drawBelow) g2.drawLine(centerX, DOT_Y + DOT_RADIUS + 1, centerX, height)
        g2.color = dotColor
        g2.fillOval(centerX - DOT_RADIUS, DOT_Y - DOT_RADIUS, DOT_RADIUS * 2, DOT_RADIUS * 2)
        g2.dispose()
    }

    companion object {
        private const val WIDTH = 20
        private const val DOT_RADIUS = 4
        private const val DOT_Y = 14
    }
}
