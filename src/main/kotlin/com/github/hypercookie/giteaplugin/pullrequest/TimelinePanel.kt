package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.PullPushBody
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.TimelineEvent
import com.github.hypercookie.giteaplugin.pullrequest.comments.avatarLabel
import com.github.hypercookie.giteaplugin.pullrequest.comments.boxPanel
import com.github.hypercookie.giteaplugin.pullrequest.comments.escapeHtml
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
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
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.Box
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
        val button = JButton("Comment")
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
        events.forEach { event -> feedColumn.add(capHeight(renderEvent(event, commitsBySha))) }
        feedColumn.revalidate()
        feedColumn.repaint()
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

    private fun renderEvent(event: TimelineEvent, commitsBySha: Map<String, GitCommit>): JComponent =
        when (event.type) {
            "comment", "review" -> if (!event.body.isNullOrBlank()) commentCard(event) else systemLine(event, "reviewed this pull request")
            "pull_push" -> pushCard(event, commitsBySha)
            else -> systemLine(event, event.type.replace('_', ' '))
        }

    private fun commentCard(event: TimelineEvent): JComponent {
        val wrapper = JBPanel<JBPanel<*>>(BorderLayout()).apply { border = JBUI.Borders.emptyBottom(8) }
        val content = boxPanel()
        val header = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(avatarLabel(project, event.user))
            add(JBLabel("<html><b>${event.user?.login ?: "unknown"}</b> <span style='color:gray'>${formatTimestamp(event.createdAt)}</span></html>"))
        }
        content.add(header, BorderLayout.NORTH)
        content.add(wrappedBody(event.body ?: ""), BorderLayout.CENTER)
        wrapper.add(content, BorderLayout.CENTER)
        return wrapper
    }

    /** `readOnlyBody` (a `JBTextArea`) is right for the editor-inlay comment
     * boxes it was built for — there, [InlineComponentHost] hands the inlay
     * a known width before layout, so Swing's line-wrap calculation works.
     * Here, inside a plain `BoxLayout` column in a scroll pane, that same
     * `JTextArea` never gets a width to wrap against and reports a huge
     * unwrapped preferred size — the "way too big" box. An HTML `JBLabel`
     * with an explicit `width` style forces wrapping at a fixed pixel width
     * regardless of container layout, and uses the UI's normal proportional
     * font instead of the text area's monospace default. */
    private fun wrappedBody(text: String): JComponent {
        val html = escapeHtml(text).replace("\n", "<br>")
        return JBLabel("<html><body style='width: 440px'>$html</body></html>").apply {
            border = JBUI.Borders.emptyTop(4)
            verticalAlignment = JBLabel.TOP
        }
    }

    private fun systemLine(event: TimelineEvent, text: String): JComponent {
        val row = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 6, 2)).apply { border = JBUI.Borders.empty(2, 0, 6, 0) }
        row.add(avatarLabel(project, event.user))
        row.add(JBLabel("<html><b>${event.user?.login ?: "unknown"}</b> $text</html>"))
        return row
    }

    private fun pushCard(event: TimelineEvent, commitsBySha: Map<String, GitCommit>): JComponent {
        val push = event.body?.let { runCatching { json.decodeFromString<PullPushBody>(it) }.getOrNull() }
        val commitIds = push?.commitIds.orEmpty()

        val column = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(2, 0, 6, 0)
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
