package com.github.hypercookie.giteaplugin.pullrequest.reactions

import com.github.hypercookie.giteaplugin.util.bindVisibility
import com.intellij.collaboration.ui.ComponentListPanelFactory
import com.intellij.collaboration.ui.codereview.comment.CodeReviewCommentUIUtil
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionComponent
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionPillPresentation
import com.intellij.collaboration.ui.codereview.reactions.CodeReviewReactionsUIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Reaction pills under a comment, with an "add reaction" pill at the end
 * while there are any, and the "add reaction" header button. Mirrors the
 * GitHub plugin's `GHReactionsComponentFactory` (intellij-community,
 * Apache 2.0, build 252) on the platform's `CodeReviewReactionComponent`.
 */
object GiteaReactionsComponentFactory {

    fun create(cs: CoroutineScope, reactionsVm: GiteaReactionsViewModel): JComponent {
        return ComponentListPanelFactory.createHorizontal(
            cs,
            reactionsVm.reactionsWithInfo.map { it.keys.toList() },
            gap = CodeReviewReactionsUIUtil.HORIZONTAL_GAP,
            componentFactory = { reaction -> createReactionLabel(this, reactionsVm, reaction) },
        ).apply {
            val reactionPicker = createReactionPickerButton(reactionsVm).apply {
                bindVisibility(cs, combine(reactionsVm.reactionsWithInfo, reactionsVm.canReact) { reactions, canReact -> canReact && reactions.isNotEmpty() })
            }
            add(reactionPicker, -1)
            bindVisibility(cs, reactionsVm.reactionsWithInfo.map { it.isNotEmpty() })
        }
    }

    /** The smiley button GitHub puts among a comment's header actions;
     * hidden while the current user is unknown. */
    fun createAddReactionButton(cs: CoroutineScope, reactionsVm: GiteaReactionsViewModel): JComponent =
        CodeReviewCommentUIUtil.createAddReactionButton {
            GiteaReactionsPickerComponentFactory.showPopup(reactionsVm, it.source as JComponent)
        }.apply {
            bindVisibility(cs, reactionsVm.canReact)
        }

    private fun createReactionLabel(cs: CoroutineScope, reactionsVm: GiteaReactionsViewModel, reaction: String): JComponent {
        val state: Flow<CodeReviewReactionPillPresentation> = reactionsVm.reactionsWithInfo.mapNotNull { reactionsWithInfo ->
            reactionsWithInfo[reaction]?.let { Presentation(reactionsVm, reaction, it) }
        }
        return CodeReviewReactionComponent.createReactionButtonIn(cs, state) {
            reactionsVm.toggle(reaction)
        }
    }

    private fun createReactionPickerButton(reactionsVm: GiteaReactionsViewModel): JComponent =
        CodeReviewReactionComponent.createNewReactionButton {
            GiteaReactionsPickerComponentFactory.showPopup(reactionsVm, it)
        }

    private data class Presentation(
        private val reactionsVm: GiteaReactionsViewModel,
        private val reaction: String,
        private val reactionInfo: ReactionInfo,
    ) : CodeReviewReactionPillPresentation {
        override val reactionName: String get() = GiteaEmoji.presentableName(reaction)
        override val reactors: List<String> get() = reactionInfo.users.map { it.fullName?.takeIf(String::isNotBlank) ?: it.login }
        override val isOwnReaction: Boolean get() = reactionInfo.isReactedByCurrentUser

        override fun getIcon(size: Int): Icon = reactionsVm.reactionIconsProvider.getIcon(reaction, size)
    }
}
