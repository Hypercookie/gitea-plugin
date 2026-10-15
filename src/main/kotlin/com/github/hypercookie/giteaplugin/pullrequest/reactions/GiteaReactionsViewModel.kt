package com.github.hypercookie.giteaplugin.pullrequest.reactions

import com.github.hypercookie.giteaplugin.api.dto.GiteaReaction
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reactions of one comment or description. Mirrors the GitHub plugin's
 * `GHReactionsViewModel` (intellij-community, Apache 2.0, build 252),
 * keyed by Gitea reaction name instead of `GHReactionContent`.
 */
interface GiteaReactionsViewModel {
    val reactionIconsProvider: IconsProvider<String>

    /** Reactions the picker offers. */
    val availableReactions: StateFlow<List<String>>

    val reactionsWithInfo: StateFlow<Map<String, ReactionInfo>>

    /** False until the current user is known. */
    val canReact: StateFlow<Boolean>

    fun toggle(reaction: String)
}

data class ReactionInfo(
    val users: List<GiteaUser>,
    val isReactedByCurrentUser: Boolean,
)

internal class GiteaReactionsViewModelImpl(
    parentCs: CoroutineScope,
    private val reactable: GiteaReactable,
    private val currentUser: StateFlow<GiteaUser?>,
    private val reactionsService: GiteaReactionsService,
) : GiteaReactionsViewModel {
    private val cs = parentCs.giteaChildScope("Gitea Reactions View Model")

    override val reactionIconsProvider: IconsProvider<String> = reactionsService.reactionIconsProvider

    override val availableReactions: StateFlow<List<String>> = reactionsService.allowedReactions

    override val reactionsWithInfo: StateFlow<Map<String, ReactionInfo>> =
        combine(reactionsService.reactions(reactable), currentUser) { data, user -> info(data, user) }
            .stateIn(cs, SharingStarted.Eagerly, info(reactionsService.reactions(reactable).value, currentUser.value))

    override val canReact: StateFlow<Boolean> =
        currentUser.map { it != null }.stateIn(cs, SharingStarted.Eagerly, currentUser.value != null)

    override fun toggle(reaction: String) {
        val user = currentUser.value ?: return
        cs.launch {
            val isReacted = reactionsWithInfo.value[reaction]?.isReactedByCurrentUser ?: false
            try {
                if (isReacted) reactionsService.removeReaction(reactable, reaction, user.id)
                else reactionsService.addReaction(reactable, reaction)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Failed to ${if (isReacted) "remove" else "add"} reaction '$reaction' on $reactable", e)
            }
        }
    }

    private fun info(data: List<GiteaReaction>, user: GiteaUser?): Map<String, ReactionInfo> {
        val reactionToUsers = data.groupBy({ it.content }, { it.user })
        return reactionToUsers.mapValues { (_, usersNullable) ->
            val users = usersNullable.filterNotNull()
            ReactionInfo(users, user != null && users.any { it.id == user.id })
        }
    }

    private companion object {
        val LOG = logger<GiteaReactionsViewModelImpl>()
    }
}
