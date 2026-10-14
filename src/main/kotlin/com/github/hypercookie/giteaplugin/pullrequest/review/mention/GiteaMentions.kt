package com.github.hypercookie.giteaplugin.pullrequest.review.mention

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** A user offered for `@` completion; higher [priority] sorts first. */
data class GiteaMentionCandidate(val user: GiteaUser, val priority: Double)

/**
 * Users that can be mentioned in a comment field. Put on a comment editor
 * with [install]; [GiteaMentionCompletionContributor] reads it from there,
 * the same hand-off the GitHub plugin uses (`MENTIONS_COMPLETION_KEY`).
 */
fun interface GiteaMentionSource {
    suspend fun candidates(): List<GiteaMentionCandidate>

    companion object {
        val KEY: Key<GiteaMentionSource> = Key.create("Gitea.Comment.Mentions")

        fun install(editor: Editor, source: GiteaMentionSource) {
            editor.putUserData(KEY, source)
        }
    }
}

/**
 * Mention candidates for one pull request: its author, requested reviewers
 * and everyone who already commented come first; then every user who can be
 * assigned in the repository (Gitea's `/assignees`: users with write access,
 * the people a mention is useful for).
 */
class GiteaPullRequestMentionSource(private val data: GiteaPullRequestReviewData) : GiteaMentionSource {

    private val repositoryUsers: Deferred<List<GiteaUser>> by lazy {
        data.cs.async {
            runCatching { data.client.listAssignees(data.owner, data.repoName) }
                .onFailure { thisLogger().warn("Failed to load mentionable users", it) }
                .getOrDefault(emptyList())
        }
    }

    override suspend fun candidates(): List<GiteaMentionCandidate> {
        val pr = data.pullRequest.value
        val participants = buildList {
            pr.user?.let { add(GiteaMentionCandidate(it, AUTHOR_PRIORITY)) }
            pr.requestedReviewers.orEmpty().forEach { add(GiteaMentionCandidate(it, PARTICIPANT_PRIORITY)) }
            data.comments.value.mapNotNull { it.user }.forEach { add(GiteaMentionCandidate(it, PARTICIPANT_PRIORITY)) }
        }
        val others = repositoryUsers.await().map { GiteaMentionCandidate(it, DEFAULT_PRIORITY) }
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
