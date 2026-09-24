package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Approve/Comment/Request Changes buttons + a live pending-comment count,
 * bound to one PR's [PendingReviewStore]. Shared between
 * [PullRequestDetailPanel] (per-PR detail tab) and the tool window's
 * current-branch panel — the whole point of promoting `PendingReviewStore`
 * to a project-level service (see [PendingReviewStoreService]) was so both
 * places read/write the same pending queue.
 */
class ReviewActionsPanel(
    private val project: Project,
    private val client: GiteaApiClient,
    private val owner: String,
    private val repo: String,
    private val prNumber: Long,
    private val headSha: String,
    private val pendingStore: PendingReviewStore,
) {
    private val pendingLabel = JBLabel(" ")
    private val statusLabel = JBLabel(" ")

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Approve").apply { addActionListener { submitReview(ReviewEvent.APPROVED) } })
                add(JButton("Comment").apply { addActionListener { submitReview(ReviewEvent.COMMENT) } })
                add(JButton("Request Changes").apply { addActionListener { submitReview(ReviewEvent.REQUEST_CHANGES) } })
                add(statusLabel)
            },
            BorderLayout.NORTH,
        )
        add(pendingLabel, BorderLayout.SOUTH)
    }

    init {
        pendingStore.addListener { updatePendingLabel() }
        updatePendingLabel()
    }

    private fun updatePendingLabel() {
        val count = pendingStore.pendingComments.size
        pendingLabel.text = if (count == 0) " " else "$count pending comment(s) — not yet submitted"
    }

    /** Bundles every queued pending comment into one review submission —
     * Gitea creates and submits a review in the same call. `commitId`
     * pinned to the PR's current head SHA so the review doesn't show as
     * stale on the next commit. */
    private fun submitReview(event: ReviewEvent) {
        statusLabel.text = "Submitting…"
        val pendingComments = pendingStore.pendingComments
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching {
                client.createReview(
                    owner, repo, prNumber,
                    CreatePullReviewOptions(event = event, commitId = headSha, comments = pendingComments),
                )
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = {
                        pendingStore.clear()
                        statusLabel.text = "Review submitted (${event.name.lowercase()})."
                        notify("Review submitted.", NotificationType.INFORMATION)
                    },
                    onFailure = { error ->
                        // Inline label alone was easy to miss during manual
                        // testing (user reported "nothing happened" for a
                        // real failure — Gitea's self-review restriction).
                        // Also surface it as a notification.
                        statusLabel.text = "Failed to submit review: ${error.message}"
                        notify("Failed to submit review: ${error.message}", NotificationType.ERROR)
                    },
                )
            }
        }
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Gitea")
            .createNotification(text, type)
            .notify(project)
    }
}
