package com.github.hypercookie.giteaplugin.pullrequest.reactions

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GiteaReaction
import com.intellij.collaboration.ui.icon.IconsProvider
import com.intellij.openapi.diagnostic.logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** What a reaction is attached to. */
sealed interface GiteaReactable {
    /** A top-level or review (code) comment. */
    data class Comment(val id: Long) : GiteaReactable

    /** The pull request itself, shown as reactions on its description. */
    data object PullRequest : GiteaReactable
}

/**
 * Reactions of one pull request's comments and description, plus the
 * reactions the server accepts. Plays the part of the GitHub plugin's
 * `GHReactionsService` and the reaction data its GraphQL queries return
 * with each comment.
 *
 * Gitea returns no reactions with comments (migration ledger G2), so each
 * reactable's list is fetched on first use, a few at a time, and kept here
 * so a rebuilt timeline or a reopened diff shows it without asking again.
 * Every view of the same reactable shares one flow, and adding or removing
 * a reaction updates it in place. [refresh] fetches the known lists again.
 */
class GiteaReactionsService(
    private val cs: CoroutineScope,
    private val client: GiteaApiClient,
    private val owner: String,
    private val repo: String,
    private val number: () -> Long,
    serverUrl: String,
) {
    private val _allowedReactions = MutableStateFlow(DEFAULT_REACTIONS)

    /** Reactions offered in the picker: the server's `[ui] REACTIONS`. */
    val allowedReactions: StateFlow<List<String>> = _allowedReactions.asStateFlow()

    private val customEmojis = MutableStateFlow<Set<String>>(emptySet())

    val reactionIconsProvider: IconsProvider<String> = GiteaReactionIcons(cs, serverUrl) { it in customEmojis.value }

    private val byReactable = ConcurrentHashMap<GiteaReactable, MutableStateFlow<List<GiteaReaction>>>()

    /** Bounds concurrent reaction requests: a long timeline asks for one
     * list per comment. */
    private val permits = Semaphore(4)

    init {
        cs.launch {
            runCatching { client.getUISettings() }
                .onSuccess { settings ->
                    customEmojis.value = settings.customEmojis.toSet()
                    if (settings.allowedReactions.isNotEmpty()) _allowedReactions.value = settings.allowedReactions
                }
                .onFailure { if (it !is CancellationException) LOG.info("Failed to load allowed reactions", it) }
        }
    }

    /** Reactions on [reactable]; empty until the first fetch returns. */
    fun reactions(reactable: GiteaReactable): StateFlow<List<GiteaReaction>> {
        var created = false
        val flow = byReactable.computeIfAbsent(reactable) {
            created = true
            MutableStateFlow(emptyList())
        }
        if (created) load(reactable, flow)
        return flow.asStateFlow()
    }

    /** Fetches every list fetched so far again, to pick up other users' reactions. */
    fun refresh() {
        byReactable.forEach { (reactable, flow) -> load(reactable, flow) }
    }

    private fun load(reactable: GiteaReactable, flow: MutableStateFlow<List<GiteaReaction>>) {
        cs.launch {
            runCatching { permits.withPermit { fetch(reactable) } }
                .onSuccess { flow.value = it }
                .onFailure { if (it !is CancellationException) LOG.info("Failed to load reactions of $reactable", it) }
        }
    }

    private suspend fun fetch(reactable: GiteaReactable): List<GiteaReaction> = when (reactable) {
        is GiteaReactable.Comment -> client.listCommentReactions(owner, repo, reactable.id)
        GiteaReactable.PullRequest -> client.listIssueReactions(owner, repo, number())
    }

    suspend fun addReaction(reactable: GiteaReactable, content: String) {
        val reaction = when (reactable) {
            is GiteaReactable.Comment -> client.addCommentReaction(owner, repo, reactable.id, content)
            GiteaReactable.PullRequest -> client.addIssueReaction(owner, repo, number(), content)
        }
        // 200 instead of 201 when the reaction already existed; don't count it twice.
        flowFor(reactable).update { list ->
            if (list.any { it.content == reaction.content && it.user?.id == reaction.user?.id }) list else list + reaction
        }
    }

    suspend fun removeReaction(reactable: GiteaReactable, content: String, userId: Long) {
        when (reactable) {
            is GiteaReactable.Comment -> client.removeCommentReaction(owner, repo, reactable.id, content)
            GiteaReactable.PullRequest -> client.removeIssueReaction(owner, repo, number(), content)
        }
        flowFor(reactable).update { list -> list.filterNot { it.content == content && it.user?.id == userId } }
    }

    private fun flowFor(reactable: GiteaReactable) = byReactable.computeIfAbsent(reactable) { MutableStateFlow(emptyList()) }

    companion object {
        private val LOG = logger<GiteaReactionsService>()

        /** Gitea's default `[ui] REACTIONS`, used until the server's list is loaded. */
        val DEFAULT_REACTIONS = listOf("+1", "-1", "laugh", "hooray", "confused", "heart", "rocket", "eyes")
    }
}
