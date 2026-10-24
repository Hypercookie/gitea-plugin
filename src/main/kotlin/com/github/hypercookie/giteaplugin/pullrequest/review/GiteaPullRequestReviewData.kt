package com.github.hypercookie.giteaplugin.pullrequest.review

import kotlinx.coroutines.delay
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.GiteaServerFeatures
import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.api.dto.GitCommitWithFiles
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.PullReview
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
import kotlinx.coroutines.channels.Channel
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

    private val _reviews = MutableStateFlow<List<PullReview>?>(null)

    /** The pull request's reviews as of the last comment load; null until
     * then. The reviewer row reads them instead of listing them again. */
    val reviews: StateFlow<List<PullReview>?> = _reviews.asStateFlow()

    /** Inline comments per review, with the review as it was when they were
     * fetched; see [reloadComments]. Only touched under [commentsLoadLock]. */
    private val commentsByReview = HashMap<Long, Pair<PullReview, List<PullReviewComment>>>()

    /** Polls since the last full comment load; see [COMMENTS_FULL_RELOAD_POLLS]. */
    private var pollsSinceFullLoad = 0

    /** Serializes [reloadComments], so a slow response from an earlier
     * fetch (the poll, say) can't overwrite the result of a later one. */
    private val commentsLoadLock = Mutex()

    private val _reloads = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Emits on every [reload], so views that fetch data of their own (the
     * timeline's comments and events) fetch it again too. */
    val reloads: SharedFlow<Unit> = _reloads.asSharedFlow()

    /** Pull request fetches [reload] asks for. Conflated: reloads asked for
     * while one runs (a Refresh clicked repeatedly) make one more, not one
     * each. */
    private val pullRequestReloads = Channel<Unit>(Channel.CONFLATED)

    val threads: StateFlow<List<ReviewThread>> =
        _comments.map(ReviewThreads::group).stateIn(cs, SharingStarted.Eagerly, emptyList())

    val pending: StateFlow<List<CreatePullReviewComment>> = pendingStore.state

    private val _currentUser = MutableStateFlow<GiteaUser?>(null)
    val currentUser: StateFlow<GiteaUser?> = _currentUser.asStateFlow()

    private val _serverFeatures = MutableStateFlow(GiteaServerFeatures.UNKNOWN)
    /** What the server's Gitea version supports; [GiteaServerFeatures.UNKNOWN]
     * (everything) until its version is known. */
    val serverFeatures: StateFlow<GiteaServerFeatures> = _serverFeatures.asStateFlow()

    private val fileContents = ConcurrentHashMap<Pair<String, String>, Deferred<Result<String?>>>()

    /** Bounds concurrent file content requests (to 8): opening the diff chain
     * of a big pull request asks for two versions of every changed file at once. */
    private val fileFetchPermits = Semaphore(8)

    private val localFiles = repo.gitRoot?.let { LocalGitFiles(project, it) }

    private val changedFilesLock = Any()

    /** Commits of the pull request with the head they were fetched for;
     * shared by the timeline and the commits popup. Guarded by [changedFilesLock]. */
    private var commitsCache: Pair<String, Deferred<Result<List<GitCommit>>>>? = null

    /** A commit's changed files never change; kept per commit id. */
    private val commitFiles = ConcurrentHashMap<String, Deferred<Result<GitCommitWithFiles>>>()

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
                if (com.intellij.openapi.application.ApplicationManager.getApplication().isActive) {
                    val full = ++pollsSinceFullLoad >= COMMENTS_FULL_RELOAD_POLLS
                    reloadComments(full)
                }
            }
        }
        cs.launch { loadCurrentUser() }
        cs.launch { _serverFeatures.value = GiteaServerFeatures.of(repo.account.serverUrl, client) }
        cs.launch { reloadComments() }
        cs.launch {
            for (ignored in pullRequestReloads) {
                runCatchingCancellable { client.getPullRequest(owner, repoName, number) }.onSuccess(::update)
                reloadComments()
            }
        }
    }

    /** Re-fetches the pull request, its comments and their reactions, and
     * tells [reloads] collectors to fetch theirs. [reactionsMaxAgeMs] skips
     * reaction lists fetched more recently than that (one request per
     * comment); an explicit Refresh passes 0. */
    fun reload(reactionsMaxAgeMs: Long = 0) {
        if (GiteaSettings.getInstance().showReactions) reactions.refresh(reactionsMaxAgeMs)
        _reloads.tryEmit(Unit)
        // Approve / Request Changes need the current user; retry a failed load.
        if (_currentUser.value == null) cs.launch { loadCurrentUser() }
        pullRequestReloads.trySend(Unit)
    }

    /** Called when the same PR shows up again with possibly newer data
     * (new head commit, edited title). Keeps this instance, so open inlays
     * and reply drafts survive. */
    fun update(pr: PullRequest) {
        val headChanged = pr.head.sha != _pullRequest.value.head.sha
        _pullRequest.value = pr
        if (headChanged) {
            // [changedFiles] checks the key anyway; this just drops the stale list.
            synchronized(changedFilesLock) {
                changedFilesCache = null
                commitsCache = null
            }
            cs.launch { reloadComments() }
        }
    }

    private suspend fun loadCurrentUser() {
        runCatchingCancellable { client.getCurrentUser() }
            .onSuccess { _currentUser.value = it }
            .onFailure { thisLogger().warn("Failed to load the current Gitea user", it) }
    }

    /**
     * Fetches the inline comments again. Gitea has no endpoint for all of a
     * pull request's inline comments, only one per review, so this lists the
     * reviews and then asks for the comments of each review that has any.
     *
     * With [full] false (the background poll), a review whose comment count
     * and update time are unchanged keeps the comments fetched before: new
     * and deleted comments show up, edits and resolutions wait for the next
     * full load (every [COMMENTS_FULL_RELOAD_POLLS] polls, Refresh, coming
     * back to the IDE, or this user's own changes).
     */
    suspend fun reloadComments(full: Boolean = true) = commentsLoadLock.withLock {
        runCatchingCancellable {
            val reviews = client.listReviews(owner, repoName, number)
            val loaded = reviews.associate { review ->
                val cached = commentsByReview[review.id]
                    ?.takeIf { (before, _) -> !full && before.commentsCount == review.commentsCount && before.updatedAt == review.updatedAt }
                    ?.second
                val comments = when {
                    cached != null -> cached
                    review.commentsCount == 0 -> emptyList()
                    else -> client.listReviewComments(owner, repoName, number, review.id)
                }
                review.id to (review to comments)
            }
            reviews to loaded
        }
            .onSuccess { (reviews, loaded) ->
                if (full) pollsSinceFullLoad = 0
                commentsByReview.clear()
                commentsByReview.putAll(loaded)
                _reviews.value = reviews
                _comments.value = reviews.flatMap { loaded.getValue(it.id).second }
            }
            .onFailure { if (it !is CancellationException) thisLogger().warn("Failed to load review comments for #$number", it) }
        Unit
    }

    /** The pull request's commits, fetched once per head commit. Failures
     * are not cached. */
    suspend fun commits(): List<GitCommit> {
        val head = _pullRequest.value.head.sha
        val deferred = synchronized(changedFilesLock) {
            commitsCache?.takeIf { it.first == head }?.second
                ?: cs.async { runCatchingCancellable { client.getPullRequestCommits(owner, repoName, number) } }
                    .also { commitsCache = head to it }
        }
        return deferred.await().getOrElse { e ->
            synchronized(changedFilesLock) { if (commitsCache?.second === deferred) commitsCache = null }
            throw e
        }
    }

    /** [sha] with its changed files; cached, since a commit never changes. */
    suspend fun commitWithFiles(sha: String): GitCommitWithFiles {
        val deferred = commitFiles.computeIfAbsent(sha) {
            cs.async { runCatchingCancellable { client.getCommitWithFiles(owner, repoName, sha) } }
        }
        return deferred.await().getOrElse { e ->
            commitFiles.remove(sha, deferred)
            throw e
        }
    }

    /**
     * File text at [ref] with line separators normalized to `\n`, which is
     * what IntelliJ documents use (a raw CRLF text diffed against a document
     * has every line changed). Null when the file doesn't exist at that ref.
     *
     * Read from the local repository when it has the commit (see
     * [LocalGitFiles]), else from the Gitea API.
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
                    fileFetchPermits.withPermit {
                        when (val local = localFiles?.read(path, ref) ?: LocalGitFiles.Read.Unavailable) {
                            is LocalGitFiles.Read.Found -> local.text
                            LocalGitFiles.Read.Missing -> null
                            LocalGitFiles.Read.Unavailable -> client.getFileContentOrNull(owner, repoName, path, ref)
                        }
                    }?.let(StringUtil::convertLineSeparators)
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

        /** Every this many polls, one loads every review's comments again,
         * to pick up edits and resolutions from elsewhere. */
        const val COMMENTS_FULL_RELOAD_POLLS = 5
    }
}
