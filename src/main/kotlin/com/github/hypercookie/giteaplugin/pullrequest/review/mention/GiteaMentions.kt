package com.github.hypercookie.giteaplugin.pullrequest.review.mention

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/** A user offered for `@` completion; higher [priority] sorts first. */
data class GiteaMentionCandidate(val user: GiteaUser, val priority: Double)

/**
 * Users that can be mentioned in a comment field. Put on a comment editor
 * with [install]; [GiteaMentionCompletionContributor] reads it from there,
 * the same hand-off the GitHub plugin uses (`MENTIONS_COMPLETION_KEY`).
 *
 * [candidates] never touches the network: completion runs inside a read
 * action, where waiting on a request would block every write in the IDE.
 * Sources that need a request start it in [preload], which [install] calls
 * as soon as the comment field exists.
 */
fun interface GiteaMentionSource {
    /** The candidates known right now. */
    fun candidates(): List<GiteaMentionCandidate>

    /** Starts loading what [candidates] will need; must not block. */
    fun preload() = Unit

    companion object {
        val KEY: Key<GiteaMentionSource> = Key.create("Gitea.Comment.Mentions")

        fun install(editor: Editor, source: GiteaMentionSource) {
            editor.putUserData(KEY, source)
            source.preload()
        }
    }
}

/**
 * The users who can be assigned in a repository (Gitea's `/assignees`:
 * users with write access, the people a mention is useful for), loaded once
 * per repository and kept for [TTL_MS], so comment fields, pull requests and
 * diff viewers of one repository share a single request. A failed load is
 * not kept; the next comment field tries again.
 */
@Service(Service.Level.PROJECT)
class GiteaRepositoryUsersService(private val cs: CoroutineScope) {

    private class Entry(val users: Deferred<List<GiteaUser>?>, val loadedAt: Long)

    private val entries = ConcurrentHashMap<Triple<String, String, String>, Entry>()

    /** The repository's users, loading them in the background if needed. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun users(repo: GiteaRepoContext, client: GiteaApiClient): Deferred<List<GiteaUser>?> {
        val key = Triple(repo.account.serverUrl, repo.owner.lowercase(), repo.repo.lowercase())
        return entries.compute(key) { _, entry ->
            val now = System.currentTimeMillis()
            val stale = entry == null || now - entry.loadedAt > TTL_MS ||
                (entry.users.isCompleted && !entry.users.isCancelled && entry.users.getCompleted() == null)
            if (!stale) entry else Entry(cs.async { load(repo, client) }, now)
        }!!.users
    }

    private suspend fun load(repo: GiteaRepoContext, client: GiteaApiClient): List<GiteaUser>? =
        try {
            client.listAssignees(repo.owner, repo.repo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            thisLogger().warn("Failed to load mentionable users of ${repo.owner}/${repo.repo}", e)
            null
        }

    companion object {
        const val TTL_MS = 10 * 60_000L

        fun getInstance(project: Project): GiteaRepositoryUsersService = project.service()
    }
}

/**
 * Mention candidates for one pull request: its author, requested reviewers
 * and everyone who already commented come first; then the repository's
 * users ([GiteaRepositoryUsersService]), once they are loaded.
 */
class GiteaPullRequestMentionSource(private val data: GiteaPullRequestReviewData) : GiteaMentionSource {

    private fun repositoryUsers(): Deferred<List<GiteaUser>?> =
        GiteaRepositoryUsersService.getInstance(data.project).users(data.repo, data.client)

    override fun preload() {
        repositoryUsers()
    }

    /** Waits until the repository's users are loaded (or failed to load). */
    suspend fun awaitRepositoryUsers() {
        repositoryUsers().await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun candidates(): List<GiteaMentionCandidate> {
        val pr = data.pullRequest.value
        val participants = buildList {
            pr.user?.let { add(GiteaMentionCandidate(it, AUTHOR_PRIORITY)) }
            pr.requestedReviewers.orEmpty().forEach { add(GiteaMentionCandidate(it, PARTICIPANT_PRIORITY)) }
            data.comments.value.mapNotNull { it.user }.forEach { add(GiteaMentionCandidate(it, PARTICIPANT_PRIORITY)) }
        }
        val users = repositoryUsers()
        val loaded = if (users.isCompleted && !users.isCancelled) users.getCompleted().orEmpty() else emptyList()
        val others = loaded.map { GiteaMentionCandidate(it, DEFAULT_PRIORITY) }
        return GiteaMentions.merge(participants + others, excludeLogin = data.currentUser.value?.login)
    }

    private companion object {
        const val AUTHOR_PRIORITY = 15.0
        const val PARTICIPANT_PRIORITY = 10.0
        const val DEFAULT_PRIORITY = 1.0
    }
}

object GiteaMentions {

    /**
     * The `@...` token directly before the caret, or null when the caret is
     * not in a mention (the token must start with `@` and contain no
     * whitespace, like the GitHub plugin's check).
     */
    fun mentionPrefix(textBeforeCaret: CharSequence): String? {
        val token = textBeforeCaret.takeLastWhile { !it.isWhitespace() }.toString()
        if (!token.startsWith("@")) return null
        // "foo@bar" is an e-mail address, not a mention; Gitea also only
        // treats @login after whitespace or at the start as a mention.
        return token.takeIf { it.lastIndexOf('@') == 0 }
    }

    /** One entry per login, keeping the highest priority, without [excludeLogin]. */
    fun merge(candidates: List<GiteaMentionCandidate>, excludeLogin: String?): List<GiteaMentionCandidate> =
        candidates
            .filter { it.user.login != excludeLogin }
            .groupBy { it.user.login.lowercase() }
            .map { (_, sameUser) -> sameUser.maxBy { it.priority } }
            .sortedWith(compareByDescending<GiteaMentionCandidate> { it.priority }.thenBy { it.user.login.lowercase() })
}
