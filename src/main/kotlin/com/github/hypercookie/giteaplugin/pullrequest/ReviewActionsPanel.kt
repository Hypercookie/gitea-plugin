package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.primaryButton
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent

/**
 * A single "Submit Review (N)…" button, bound to one PR's
 * [PendingReviewStore]. Shared between [PullRequestDetailPanel] (per-PR
 * detail tab) and the tool window's current-branch panel — the whole point
 * of promoting `PendingReviewStore` to a project-level service (see
 * [PendingReviewStoreService]) was so both places read/write the same
 * pending queue.
 *
 * Clicking it opens a popup card (title + pending count + Discard, a review-
 * body text area wired into [CreatePullReviewOptions.body] — previously
 * always sent empty — then Approve/Request Changes/Comment) instead of
 * always-visible inline buttons with no way to leave a review summary —
 * matches the reference GitHub-plugin "Submit Review" popup (see
 * docs/plan/pr-review-feature-plan.md Phase 28).
 */
class ReviewActionsPanel(
    private val project: Project,
    private val client: GiteaApiClient,
    private val owner: String,
    private val repo: String,
    private val prNumber: Long,
    private val headSha: String,
    private val pendingStore: PendingReviewStore,
    /** The PR author's login (`pr.user?.login`) — Gitea forbids
     * Approve/Request Changes on your own PR ("approve your own pull is
     * not allowed", hit repeatedly during manual testing throughout this
     * plan), so those two buttons get disabled rather than left to fail
     * after a submit attempt. */
    private val authorLogin: String?,
) {
    private val statusLabel = JBLabel(" ")
    private val submitButton = primaryButton("Submit Review…").apply { addActionListener { showSubmitPopup() } }

    /** Fetched once on init; null until it resolves (self-review buttons
     * just stay enabled for that brief window rather than blocking on it —
     * Gitea itself is still the real enforcement either way). */
    private var currentUserLogin: String? = null

    val component: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                add(submitButton)
                add(statusLabel)
            },
            BorderLayout.NORTH,
        )
    }

    init {
        pendingStore.addListener { updateSubmitButton() }
        updateSubmitButton()
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            currentUserLogin = runCatching { client.getCurrentUser().login }.getOrNull()
        }
    }

    private fun updateSubmitButton() {
        val count = pendingStore.pendingComments.size
        submitButton.text = if (count == 0) "Submit Review…" else "Submit Review ($count)…"
    }

    private fun showSubmitPopup() {
        val count = pendingStore.pendingComments.size
        val bodyField = JBTextArea(4, 36).apply { lineWrap = true; wrapStyleWord = true }
        val titleLabel = JBLabel("Submit Review" + if (count > 0) "   $count pending comment(s)" else "").apply {
            font = font.deriveFont(Font.BOLD)
        }
        // Text button, not an icon — this codebase deliberately avoids
        // guessing icon constant names it hasn't confirmed exist (see
        // Phase 38); `PendingCommentBoxPanel`'s own discard button is the
        // same plain `JButton("Discard")` for the same reason.
        val discardButton = JButton("Discard").apply { isEnabled = count > 0 }
        val isSelfAuthored = authorLogin != null && authorLogin == currentUserLogin
        val selfReviewTooltip = "Gitea doesn't allow this on your own pull request"
        // One accent button per group: Approve when allowed, otherwise
        // Comment, the only verdict left on your own PR.
        val approveButton = (if (isSelfAuthored) JButton("Approve") else primaryButton("Approve")).apply {
            isEnabled = !isSelfAuthored
            if (isSelfAuthored) toolTipText = selfReviewTooltip
        }
        val requestChangesButton = JButton("Request Changes").apply {
            isEnabled = !isSelfAuthored
            if (isSelfAuthored) toolTipText = selfReviewTooltip
        }
        val commentButton = if (isSelfAuthored) primaryButton("Comment") else JButton("Comment")

        val content = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(
                JBPanel<JBPanel<*>>(BorderLayout()).apply {
                    add(titleLabel, BorderLayout.WEST)
                    add(discardButton, BorderLayout.EAST)
                },
                BorderLayout.NORTH,
            )
            add(
                JBScrollPane(bodyField).apply {
                    preferredSize = Dimension(360, 100)
                    border = JBUI.Borders.empty(8, 0)
                },
                BorderLayout.CENTER,
            )
            add(
                JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
                    add(approveButton)
                    add(requestChangesButton)
                    add(commentButton)
                },
                BorderLayout.SOUTH,
            )
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, bodyField)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .createPopup()

        discardButton.addActionListener {
            pendingStore.clear()
            popup.cancel()
        }
        approveButton.addActionListener { submitReview(ReviewEvent.APPROVED, bodyField.text); popup.closeOk(null) }
        requestChangesButton.addActionListener { submitReview(ReviewEvent.REQUEST_CHANGES, bodyField.text); popup.closeOk(null) }
        commentButton.addActionListener { submitReview(ReviewEvent.COMMENT, bodyField.text); popup.closeOk(null) }

        popup.showUnderneathOf(submitButton)
    }

    /** Bundles every queued pending comment into one review submission —
     * Gitea creates and submits a review in the same call. `commitId`
     * pinned to the PR's current head SHA so the review doesn't show as
     * stale on the next commit. */
    private fun submitReview(event: ReviewEvent, body: String) {
        statusLabel.text = "Submitting…"
        val pendingComments = pendingStore.pendingComments
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching {
                client.createReview(
                    owner, repo, prNumber,
                    CreatePullReviewOptions(event = event, body = body, commitId = headSha, comments = pendingComments),
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
