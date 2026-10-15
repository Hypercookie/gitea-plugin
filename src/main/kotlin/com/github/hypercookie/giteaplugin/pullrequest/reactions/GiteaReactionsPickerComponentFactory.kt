package com.github.hypercookie.giteaplugin.pullrequest.reactions

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.InlineIconButton
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.event.ActionListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Popup with one button per reaction the server allows; picking one
 * toggles it. Mirrors the GitHub plugin's `GHReactionsPickerComponentFactory`
 * (intellij-community, Apache 2.0, build 252). GitHub always offers its
 * eight reactions in one row; a Gitea server can allow more, so they wrap
 * into rows of [EMOJIS_PER_ROW].
 */
object GiteaReactionsPickerComponentFactory {
    private const val EMOJI_PICKER_GAP = 7
    private const val EMOJI_PICKER_BORDER = 7
    private const val EMOJI_SIZE = 26
    private const val EMOJI_PADDING = 5
    private const val EMOJIS_PER_ROW = 8

    fun showPopup(reactionsVm: GiteaReactionsViewModel, parentComponent: JComponent) {
        var popup: JBPopup? = null
        val emojiPicker = create(reactionsVm, reactionsVm.availableReactions.value) { reaction ->
            reactionsVm.toggle(reaction)
            popup?.cancel()
        }
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(emojiPicker, emojiPicker)
            .setResizable(false)
            .setFocusable(true)
            .setRequestFocus(true)
            .createPopup()

        popup.showUnderneathOf(parentComponent)
    }

    private fun create(
        reactionsVm: GiteaReactionsViewModel,
        reactions: List<String>,
        onClick: (String) -> Unit,
    ): JComponent {
        return JPanel(ListLayout.vertical(EMOJI_PICKER_GAP)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(EMOJI_PICKER_BORDER)
            reactions.chunked(EMOJIS_PER_ROW).forEach { row ->
                add(
                    JPanel(ListLayout.horizontal(EMOJI_PICKER_GAP)).apply {
                        isOpaque = false
                        row.map { reaction -> createEmojiLabel(reactionsVm, reaction, onClick) }.forEach { label -> add(label) }
                    },
                )
            }
        }
    }

    private fun createEmojiLabel(
        reactionsVm: GiteaReactionsViewModel,
        reaction: String,
        onClick: (String) -> Unit,
    ): JComponent {
        val icon = reactionsVm.reactionIconsProvider.getIcon(reaction, EMOJI_SIZE)
        return InlineIconButton(icon).apply {
            isFocusable = false
            withBackgroundHover = true
            toolTipText = GiteaEmoji.presentableName(reaction)
            actionListener = ActionListener { onClick(reaction) }
            margin = JBInsets.create(EMOJI_PADDING, EMOJI_PADDING)
        }
    }
}
