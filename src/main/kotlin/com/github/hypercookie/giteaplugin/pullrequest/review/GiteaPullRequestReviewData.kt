package com.github.hypercookie.giteaplugin.pullrequest.review

import kotlinx.coroutines.delay
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.pullrequest.review.mention.GiteaPullRequestMentionSource
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything review-related for one open pull request, as flows the
 * collaboration-tools view models consume. One instance per PR, shared by
 * every editor and diff viewer showing that PR; lives in [cs], which the
 * owner cancels when the PR is no longer relevant.
 */
class GiteaPullRequestReviewData(
    val project: Project,
    val cs: CoroutineScope,
    val repo: GiteaRepoContext,
    val client: GiteaApiClient,
    pullRequest: PullRequest,
    val pendingStore: PendingReviewStore,
) {
    private val _pullRequest = MutableStateFlow(pullRequest)
    val pullRequest: StateFlow<PullRequest> = _pullRequest.asStateFlow()

    private val _comments = MutableStateFlow<List<PullReviewComment>>(emptyList())
    val comments: StateFlow<List<PullReviewComment>> = _comments.asStateFlow()

    val threads: StateFlow<List<ReviewThread>> =
        _comments.map(ReviewThreads::group).stateIn(cs, SharingStarted.Eagerly, emptyList())

    val pending: StateFlow<List<CreatePullReviewComment>> = pendingStore.state

    private val _currentUser = MutableStateFlow<GiteaUser?>(null)
    val currentUser: StateFlow<GiteaUser?> = _currentUser.asStateFlow()

    private val fileContents = ConcurrentHashMap<Pair<String, String>, Deferred<String?>>()

    /** `@` completion candidates for comment fields of this pull request. */
    val mentions: GiteaPullRequestMentionSource by lazy { GiteaPullRequestMentionSource(this) }

    val owner: String get() = repo.owner
    val repoName: String get() = repo.repo
    val number: Long get() = _pullRequest.value.number

    init {
        GiteaReviewSessionService.getInstance(project).register(this)
        // Poll while the IDE is in front, so comments added, edited or
        // deleted on the server show up without a manual refresh.
        cs.launch {
            while (true) {
                delay(COMMENTS_POLL_MS)
                if (com.intellij.openapi.application.ApplicationManager.getApplication().isActive) reloadComments()
            }
        }
        cs.launch { _currentUser.value = runCatching { client.getCurrentUser() }.getOrNull() }
        cs.launch { reloadComments() }
        // A submitted review empties the pending store; its comments are now
        // real server-side comments, so fetch them.
        cs.launch {
            var previous = pendingStore.state.value
            pendingStore.state.collect { current ->
                if (previous.isNotEmpty() && current.isEmpty()) reloadComments()
                previous = current
            }
        }
    }

    /** Re-fetches the pull request and its comments. */
    fun reload() {
        cs.launch {
            runCatching { client.getPullRequest(owner, repoName, number) }.onSuccess(::update)
            reloadComments()
        }
    }

    /** Called when the same PR shows up again with possibly newer data
     * (new head commit, edited title). Keeps this instance, so open inlays
     * and reply drafts survive. */
    fun update(pr: PullRequest) {
        val headChanged = pr.head.sha != _pullRequest.value.head.sha
        _pullRequest.value = pr
        if (headChanged) cs.launch { reloadComments() }
    }

    suspend fun reloadComments() {
        runCatching { client.getAllReviewComments(owner, repoName, number) }
            .onSuccess { _comments.value = it }
            .onFailure { thisLogger().warn("Failed to load review comments for #$number", it) }
    }

    /** File text at [ref], cached for the lifetime of this PR; null when
     * the file doesn't exist at that ref. */
    suspend fun fileContent(path: String, ref: String): String? =
        fileContents.computeIfAbsent(path to ref) {
            cs.async { runCatching { client.getFileContentOrNull(owner, repoName, path, ref) }.getOrNull() }
        }.await()

    suspend fun reply(thread: ReviewThread, body: String) {
        client.replyToComment(owner, repoName, number, thread.id, body)
        reloadComments()
    }

    suspend fun setResolved(thread: ReviewThread, resolved: Boolean) {
        if (resolved) client.resolveComment(owner, repoName, thread.id)
        else client.unresolveComment(owner, repoName, thread.id)
        reloadComments()
    }

    /** Posts one comment right away, outside the pending review: a review
     * with event COMMENT carrying just this comment (Gitea has no separate
     * single-comment endpoint for line comments). */
    suspend fun postSingleComment(comment: CreatePullReviewComment) {
        client.createReview(
            owner, repoName, number,
            CreatePullReviewOptions(
                event = ReviewEvent.COMMENT,
                commitId = _pullRequest.value.head.sha,
                comments = listOf(comment),
            ),
        )
        reloadComments()
    }

    private companion object {
        const val COMMENTS_POLL_MS = 60_000L
    }
}
