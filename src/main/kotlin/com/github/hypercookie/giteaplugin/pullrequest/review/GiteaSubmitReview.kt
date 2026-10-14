package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.bindOnEdt
import com.github.hypercookie.giteaplugin.util.bindVisibility
import com.github.hypercookie.giteaplugin.util.errorPresenter
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.github.hypercookie.giteaplugin.util.primaryButton
import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.codereview.list.error.ErrorStatusPanelFactory
import com.intellij.collaboration.ui.codereview.review.CodeReviewSubmitViewModel
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import icons.CollaborationToolsIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.miginfocom.layout.CC
import net.miginfocom.layout.LC
import net.miginfocom.swing.MigLayout
import java.awt.Component
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/** Submit-review state for one pull request (GitHub: `GHPRSubmitReviewViewModel`). */
class GiteaSubmitReviewViewModel(
    private val cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
    private val onClose: () -> Unit,
) : CodeReviewSubmitViewModel {

    override val draftCommentsCount: StateFlow<Int> =
        data.pending.map { it.size }.stateIn(cs, SharingStarted.Eagerly, data.pending.value.size)
    override val text = MutableStateFlow("")

    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<Throwable?>(null)
    override val error: StateFlow<Throwable?> = _error.asStateFlow()

    /**
     * Whether Approve / Request Changes are offered. Gitea rejects both on
     * your own pull request ("approve/reject your own pull is not allowed"),
     * so they stay hidden until the current user is known and isn't the
     * author.
     */
    val canApproveOrReject: StateFlow<Boolean> =
        combine(data.currentUser, data.pullRequest) { user, pr -> user?.login != null && user.login != pr.user?.login }
            .stateIn(cs, SharingStarted.Eagerly, false)

    /**
     * Mirrors Gitea's `preparePullReviewType` (routers/api/v1/repo/pull_review.go):
     * REQUEST_CHANGES needs a non-blank body even when inline comments are
     * attached, COMMENT needs a non-blank body or at least one comment, and
     * APPROVED needs neither. Bodies are checked after trimming whitespace.
     */
    val canRequestChanges: StateFlow<Boolean> =
        text.map { it.isNotBlank() }.stateIn(cs, SharingStarted.Eagerly, false)

    val canComment: StateFlow<Boolean> =
        combine(text, draftCommentsCount) { text, count -> text.isNotBlank() || count > 0 }
            .stateIn(cs, SharingStarted.Eagerly, draftCommentsCount.value > 0)

    fun submit(event: ReviewEvent) {
        if (_isBusy.value) return
        _isBusy.value = true
        _error.value = null
        cs.launch {
            try {
                val pending = data.pendingStore.pendingComments
                data.client.createReview(
                    data.owner, data.repoName, data.number,
                    CreatePullReviewOptions(event = event, body = text.value, commitId = data.pullRequest.value.head.sha, comments = pending),
                )
                withContext(Dispatchers.Main) {
                    data.pendingStore.clear()
                    NotificationGroupManager.getInstance().getNotificationGroup("Gitea")
                        .createNotification("Review submitted", NotificationType.INFORMATION).notify(data.project)
                    onClose()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun discard() {
        data.pendingStore.clear()
    }

    override fun cancel() = onClose()
}

/**
 * The "Submit Review" popup. Mirrors the platform's
 * `CodeReviewSubmitPopupHandler` (internal, migration ledger I10) and the
 * GitHub plugin's `GHPRSubmitReviewPopup`: title with pending count,
 * discard and close buttons, a review body editor, an error line, and
 * Approve / Request Changes / Comment.
 */
object GiteaSubmitReviewPopup {

    fun show(data: GiteaPullRequestReviewData, parent: Component, above: Boolean = false) {
        val popup = createPopup(data)
        if (above) {
            // Platform's showAbove helper for popups is collaboration-tools
            // internal; position the popup above the button directly.
            val height = popup.content.preferredSize.height
            popup.show(RelativePoint(parent, java.awt.Point(0, -height)))
        } else {
            popup.showUnderneathOf(parent)
        }
    }

    /**
     * Builds the popup without showing it. The review body editor belongs to
     * its [EditorTextField], which creates it when the popup is shown and
     * releases it when the popup's content is removed. The popup's scope is
     * EDT-dispatched, as in the platform's `CodeReviewSubmitPopupHandler`,
     * and cancelled when the popup is disposed, also if it was never shown.
     */
    internal fun createPopup(data: GiteaPullRequestReviewData): JBPopup {
        val cs = GiteaProjectCoroutineScope.getInstance(data.project).coroutineScope
            .giteaChildScope("Gitea submit review", Dispatchers.EDT)
        var popup: JBPopup? = null
        val vm = GiteaSubmitReviewViewModel(cs, data) { popup?.cancel() }
        val editor = createEditor(cs, vm.text)
        val content = createPanel(cs, vm, editor)
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, editor)
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .createPopup()
            .apply {
                addListener(object : JBPopupListener {
                    override fun onClosed(event: LightweightWindowEvent) = cs.cancel()
                })
                Disposer.register(this) { cs.cancel() }
            }
        return popup
    }

    private fun createPanel(cs: CoroutineScope, vm: GiteaSubmitReviewViewModel, editor: EditorTextField): JComponent {
        val title = JBLabel(CollaborationToolsBundle.message("review.submit.review.title")).apply {
            font = font.deriveFont(font.style or Font.BOLD)
        }
        val count = JBLabel()
        cs.bindOnEdt(vm.draftCommentsCount) {
            count.text = if (it > 0) CollaborationToolsBundle.message("review.pending.comments.count", it) else ""
            count.isVisible = it > 0
        }
        val discard = InplaceButton(IconButton("Discard pending comments", CollaborationToolsIcons.Delete, CollaborationToolsIcons.DeleteHovered)) {
            if (MessageDialogBuilder.yesNo("Discard Pending Review", "Are you sure you want to delete all pending comments?").ask(editor)) {
                vm.discard()
            }
        }.apply { border = JBUI.Borders.empty(5) }
        cs.bindOnEdt(vm.draftCommentsCount) { discard.isVisible = it > 0 }
        val close = InplaceButton(IconButton(null, AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { vm.cancel() }.apply {
            border = JBUI.Borders.empty(5)
        }
        val titlePanel = JPanel(HorizontalLayout(TITLE_ACTIONS_GAP)).apply {
            isOpaque = false
            add(title, HorizontalLayout.LEFT)
            add(count, HorizontalLayout.LEFT)
            add(close, HorizontalLayout.RIGHT)
            add(discard, HorizontalLayout.RIGHT)
        }
        // Same wrapping error panel as the platform's CodeReviewSubmitPopupHandler,
        // so long server messages are shown in full instead of being cut off.
        val errorPanel = ErrorStatusPanelFactory.create(
            cs, vm.error, errorPresenter(CollaborationToolsBundle.message("review.submit.failed")), ErrorStatusPanelFactory.Alignment.LEFT,
        )
        return JPanel(MigLayout(LC().insets("12").fill().flowY().noGrid().hideMode(3))).apply {
            background = JBUI.CurrentTheme.Popup.BACKGROUND
            preferredSize = JBDimension(500, 200)
            add(titlePanel, CC().growX())
            add(editor, CC().growX().growY())
            add(errorPanel, CC().growY().growPrioY(0))
            add(createActions(cs, vm), CC())
        }
    }

    private fun createActions(cs: CoroutineScope, vm: GiteaSubmitReviewViewModel): JComponent {
        // Enablement follows Gitea's server-side rules (see the view model), so
        // a disabled button explains what is missing instead of failing with
        // HTTP 422 on submit.
        val buttons = buildList<JComponent> {
            // GitHub draws Approve with the green `InstallButton`, which is
            // `@ApiStatus.Internal` (migration ledger I20); the platform's
            // default-button style marks it as the primary action instead.
            add(
                primaryButton("Approve").apply {
                    addActionListener { vm.submit(ReviewEvent.APPROVED) }
                    cs.bindOnEdt(vm.isBusy) { isEnabled = !it }
                    bindVisibility(cs, vm.canApproveOrReject)
                },
            )
            add(
                JButton("Request Changes").apply {
                    isOpaque = false
                    addActionListener { vm.submit(ReviewEvent.REQUEST_CHANGES) }
                    bindVisibility(cs, vm.canApproveOrReject)
                    cs.bindOnEdt(combine(vm.isBusy, vm.canRequestChanges, ::Pair)) { (busy, allowed) ->
                        isEnabled = !busy && allowed
                        toolTipText = if (allowed) {
                            "Submit feedback that must be addressed before merging"
                        } else {
                            "Gitea requires a review message to request changes"
                        }
                    }
                },
            )
            add(
                JButton("Comment").apply {
                    isOpaque = false
                    addActionListener { vm.submit(ReviewEvent.COMMENT) }
                    cs.bindOnEdt(combine(vm.isBusy, vm.canComment, ::Pair)) { (busy, allowed) ->
                        isEnabled = !busy && allowed
                        toolTipText = if (allowed) {
                            "Submit general feedback without explicit approval"
                        } else {
                            "Write a review message or add pending comments to comment"
                        }
                    }
                },
            )
        }
        return JPanel(ListLayout.horizontal(ACTIONS_GAP)).apply {
            isOpaque = false
            buttons.forEach(::add)
        }
    }

    private fun createEditor(cs: CoroutineScope, text: MutableStateFlow<String>): EditorTextField =
        EditorTextField(text.value, null, FileTypes.PLAIN_TEXT).apply {
            setOneLineMode(false)
            setPlaceholder(CollaborationToolsBundle.message("review.comment.placeholder"))
            addSettingsProvider {
                it.settings.isUseSoftWraps = true
                it.setVerticalScrollbarVisible(true)
                it.scrollPane.viewportBorder = JBUI.Borders.emptyLeft(4)
            }
            document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    text.value = document.text
                }
            })
        }

    private const val ACTIONS_GAP = 6
    private const val TITLE_ACTIONS_GAP = 5
}

/**
 * "Submit Review…" button (with pending count), as in the GitHub plugin's
 * details actions and editor review toolbar. Primary (default) button
 * style, per the plugin's button convention.
 */
object GiteaSubmitReviewButton {
    /** Live-updating button for components with their own scope. */
    fun create(cs: CoroutineScope, data: GiteaPullRequestReviewData, above: Boolean = false): JComponent {
        val button = createStatic(data, above)
        cs.bindOnEdt(data.pending.map { it.size }) { button.text = text(it) }
        return button
    }

    /** Button with the count at creation time, for editor notification
     * banners, which are rebuilt whenever pending comments change. */
    fun createStatic(data: GiteaPullRequestReviewData, above: Boolean = false): JButton =
        primaryButton(text(data.pending.value.size)).apply {
            addActionListener { GiteaSubmitReviewPopup.show(data, this, above) }
        }

    private fun text(count: Int) = if (count == 0) {
        CollaborationToolsBundle.message("review.start.submit.action")
    } else {
        CollaborationToolsBundle.message("review.start.submit.action.with.comments", count)
    }
}
