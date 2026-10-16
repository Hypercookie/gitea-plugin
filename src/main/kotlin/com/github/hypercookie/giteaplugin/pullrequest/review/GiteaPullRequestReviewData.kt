package com.github.hypercookie.giteaplugin.pullrequest.review

import kotlinx.coroutines.delay
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.pullrequest.reactions.GiteaReactionsService
import com.github.hypercookie.giteaplugin.pullrequest.review.mention.GiteaPullRequestMentionSource
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.util.runCatchingCancellable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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

    /** Serializes [reloadComments], so a slow response from an earlier
     * fetch (the poll, say) can't overwrite the result of a later one. */
    private val commentsLoadLock = Mutex()

    private val _reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits on every [reload], so views that fetch data of their own (the
     * timeline's comments and events) fetch it again too. */
    val reloads: SharedFlow<Unit> = _reloads.asSharedFlow()

    val threads: StateFlow<List<ReviewThread>> =
        _comments.map(ReviewThreads::group).stateIn(cs, SharingStarted.Eagerly, emptyList())

    val pending: StateFlow<List<CreatePullReviewComment>> = pendingStore.state

    private val _currentUser = MutableStateFlow<GiteaUser?>(null)
    val currentUser: StateFlow<GiteaUser?> = _currentUser.asStateFlow()

    private val fileContents = ConcurrentHashMap<Pair<String, String>, Deferred<Result<String?>>>()

    /** Bounds concurrent file content requests (to 8): opening the diff chain
     * of a big pull request asks for two versions of every changed file at once. */
    private val fileFetchPermits = Semaphore(8)

    private val changedFilesLock = Any()

    /** Changed files by new path, with the (head, merge base) pair they
     * were fetched for; see [changedFiles]. Guarded by [changedFilesLock]. */
    private var changedFilesCache: Pair<Pair<String, String?>, Deferred<Result<Map<String, ChangedFile>>>>? = null

    /** `@` completion candidates for comment fields of this pull request. */
    val mentions: GiteaPullRequestMentionSource by lazy { GiteaPullRequestMentionSource(this) }

    /** Reactions on this pull request's comments and description. */
    val reactions: GiteaReactionsService by lazy {
        GiteaReactionsService(cs, client, repo.owner, repo.repo, { number }, repo.account.serverUrl)
    }

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
        cs.launch { loadCurrentUser() }
        cs.launch { reloadComments() }
    }

    /** Re-fetches the pull request, its comments and their reactions, and
     * tells [reloads] collectors to fetch theirs. */
    fun reload() {
        if (GiteaSettings.getInstance().showReactions) reactions.refresh()
        _reloads.tryEmit(Unit)
        // Approve / Request Changes need the current user; retry a failed load.
        if (_currentUser.value == null) cs.launch { loadCurrentUser() }
        cs.launch {
            runCatchingCancellable { client.getPullRequest(owner, repoName, number) }.onSuccess(::update)
            reloadComments()
        }
    }

    /** Called when the same PR shows up again with possibly newer data
     * (new head commit, edited title). Keeps this instance, so open inlays
     * and reply drafts survive. */
    fun update(pr: PullRequest) {
        val headChanged = pr.head.sha != _pullRequest.value.head.sha
        _pullRequest.value = pr
        if (headChanged) {
            // [changedFiles] checks the key anyway; this just drops the stale list.
            synchronized(changedFilesLock) { changedFilesCache = null }
            cs.launch { reloadComments() }
        }
    }

    private suspend fun loadCurrentUser() {
        runCatchingCancellable { client.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
            .onFailure { thisLogger().warn("Failed to load the current Gitea user", it) }
    }

    suspend fun reloadComments() = commentsLoadLock.withLock {
        runCatchingCancellable { client.getAllReviewComments(owner, repoName, number) }
            .onSuccess { _comments.value = it }
            .onFailure { if (it !is CancellationException) thisLogger().warn("Failed to load review comments for #$number", it) }
        Unit
    }

    /**
     * File text at [ref] with line separators normalized to `\n`, which is
     * what IntelliJ documents use (a raw CRLF text diffed against a document
     * has every line changed). Null when the file doesn't exist at that ref.
     *
     * Successful results, "doesn't exist" included, are cached for the
     * lifetime of this PR. A failed request (timeout, rate limit, server
     * error) is not cached, so the next call retries.
     *
     * @throws Exception the request's failure, so callers can tell a missing
     *   file from one that couldn't be loaded.
     */
    suspend fun fileContent(path: String, ref: String): String? {
        val key = path to ref
        val deferred = fileContents.computeIfAbsent(key) {
            cs.async {
                runCatchingCancellable {
                    fileFetchPermits.withPermit { client.getFileContentOrNull(owner, repoName, path, ref) }
                        ?.let(StringUtil::convertLineSeparators)
                }
            }
        }
        return deferred.await().getOrElse { e ->
            fileContents.remove(key, deferred)
            if (e !is CancellationException) thisLogger().info("Failed to load $path at $ref for #$number", e)
            throw e
        }
    }

    /**
     * The pull request's changed files by new path (`filename`), fetched
     * once per head commit and merge base. Lets the editor find a renamed
     * file's previous path and tell an added file from a failed fetch.
     * Failures are not cached.
     */
    suspend fun changedFiles(): Map<String, ChangedFile> {
        val pr = _pullRequest.value
        val key = pr.head.sha to pr.mergeBase
        val deferred = synchronized(changedFilesLock) {
            changedFilesCache?.takeIf { it.first == key }?.second
                ?: cs.async {
                    runCatchingCancellable { client.getPullRequestFiles(owner, repoName, pr.number).associateBy { it.filename } }
                }.also { changedFilesCache = key to it }
        }
        return deferred.await().getOrElse { e ->
            synchronized(changedFilesLock) {
                if (changedFilesCache?.second === deferred) changedFilesCache = null
            }
            if (e !is CancellationException) thisLogger().info("Failed to load changed files of #${pr.number}", e)
            throw e
        }
    }

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
