package com.github.hypercookie.giteaplugin.util

import com.intellij.collaboration.messages.CollaborationToolsBundle
import com.intellij.collaboration.ui.codereview.comment.CodeReviewSubmittableTextViewModel
import com.intellij.collaboration.ui.codereview.comment.CommentInputActionsComponentFactory
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.ShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.KeyStroke

/*
 * Shared pieces of the comment inputs built with
 * `CodeReviewCommentTextFieldFactory` (new comment, reply, timeline comment).
 */

/**
 * Submit action of a comment input, enabled only while the text is not
 * blank and nothing is being submitted, as the platform's own
 * `submitActionIn` (which builds on internal API) does.
 */
fun CodeReviewSubmittableTextViewModel.submitAction(cs: CoroutineScope, name: String, perform: () -> Unit): Action =
    giteaAction(name) { if (text.value.isNotBlank()) perform() }.apply {
        isEnabled = text.value.isNotBlank()
        bindEnabled(cs, combine(text, state) { text, state -> text.isNotBlank() && state?.isInProgress != true })
    }

/** Shift + the submit shortcut (Shift+Cmd+Enter on macOS, Shift+Ctrl+Enter elsewhere). */
val SHIFT_SUBMIT_SHORTCUT: ShortcutSet
    get() = CustomShortcutSet(
        KeyStroke.getKeyStroke(
            KeyEvent.VK_ENTER,
            (if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK) or InputEvent.SHIFT_DOWN_MASK,
        ),
    )

val shiftSubmitShortcutText: String get() = KeymapUtil.getFirstKeyboardShortcutText(SHIFT_SUBMIT_SHORTCUT)

/**
 * Runs [action] on [shortcut] while the focus is in this component, if the
 * action is enabled. Registered on the component itself, so it takes
 * precedence over the editor's keymap actions on the same keys.
 */
fun JComponent.installShortcut(action: Action, shortcut: ShortcutSet) {
    val component = this
    object : DumbAwareAction() {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = action.isEnabled
        }

        override fun actionPerformed(e: AnActionEvent) {
            action.actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, null))
        }
    }.registerCustomShortcutSet(shortcut, component)
}

/**
 * Hides the "Enter to add a new line" hint the platform adds next to the
 * submit hint: plain Enter inserting a new line needs no explanation.
 */
fun JComponent.hideNewLineHint(): JComponent = apply {
    val hint = CollaborationToolsBundle.message("review.comment.new.line.hint", CommentInputActionsComponentFactory.newLineShortcutText)
    UIUtil.findComponentsOfType(this, JLabel::class.java).filter { it.text == hint }.forEach { it.isVisible = false }
}
