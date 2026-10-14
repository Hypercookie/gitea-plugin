package com.github.hypercookie.giteaplugin.pullrequest.details

import com.github.hypercookie.giteaplugin.api.dto.GitCommit
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaPullRequestActions
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJob
import com.intellij.collaboration.ui.codereview.details.data.CodeReviewCIJobState
import com.intellij.collaboration.ui.codereview.details.data.ReviewRequestState
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranches
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewBranchesViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeDetails
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangeListViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewChangesViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewDetailsViewModel
import com.intellij.collaboration.ui.codereview.details.model.CodeReviewStatusViewModel
import com.intellij.collaboration.util.ChangesSelection
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import git4idea.GitRevisionNumber
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.util.Date

/** Title, description and state for the details header. */
class GiteaPullRequestDetailsViewModel(val data: GiteaPullRequestReviewData) : CodeReviewDetailsViewModel {
    private val pr get() = data.pullRequest.value
    override val number: String = "#${pr.number}"
    override val url: String = pr.htmlUrl.orEmpty()
    override val title: Flow<String> = data.pullRequest.map { it.title }
    override val description: Flow<String> = data.pullRequest.map { it.body.orEmpty() }
    override val reviewRequestState: Flow<ReviewRequestState> = data.pullRequest.map {
        when {
            it.merged -> ReviewRequestState.MERGED
            it.state == "closed" -> ReviewRequestState.CLOSED
            it.draft -> ReviewRequestState.DRAFT
            else -> ReviewRequestState.OPENED
        }
    }
}

/**
 * Commit selection ("Changes from N commits") and the matching change
 * list. Index -1 means all commits, as in the platform's commits popup.
 */
class GiteaPullRequestChangesViewModel(
    private val cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
) : CodeReviewChangesViewModel<GitCommit> {

    private val _commits = MutableStateFlow<List<GitCommit>>(emptyList())
    override val reviewCommits: SharedFlow<List<GitCommit>> = _commits.asStateFlow()

    private val _selectedIndex = MutableStateFlow(-1)
    override val selectedCommitIndex: SharedFlow<Int> = _selectedIndex.asStateFlow()
    override val selectedCommit: SharedFlow<GitCommit?> =
        combine(_commits, _selectedIndex) { commits, index -> commits.getOrNull(index) }
            .stateIn(cs, SharingStarted.Eagerly, null)

    private val _changeList = MutableStateFlow<Result<GiteaChangeListViewModel>?>(null)

    /** Null while loading. */
    val changeListVm: StateFlow<Result<GiteaChangeListViewModel>?> = _changeList.asStateFlow()

    init {
        cs.launch {
            _commits.value = runCatching { data.client.getPullRequestCommits(data.owner, data.repoName, data.number) }
                .onFailure { thisLogger().warn("Failed to load commits of #${data.number}", it) }
                .getOrDefault(emptyList())
        }
        cs.launch {
            combine(_selectedIndex, _commits, data.pullRequest) { index, commits, _ -> commits.getOrNull(index) }
                .collect { commit -> _changeList.value = null; _changeList.value = runCatching { loadChanges(commit) } }
        }
    }

    private suspend fun loadChanges(commit: GitCommit?): GiteaChangeListViewModel {
        val pr = data.pullRequest.value
        val changes = if (commit == null) {
            val base = GitRevisionNumber(pr.mergeBase ?: pr.base.sha)
            val head = GitRevisionNumber(pr.head.sha)
            data.client.getPullRequestFiles(data.owner, data.repoName, data.number).map { file ->
                RefComparisonChange(
                    base, if (file.status == "added") null else GiteaChangePaths.filePath(data, file.previousFilename ?: file.filename),
                    head, if (file.status == "removed" || file.status == "deleted") null else GiteaChangePaths.filePath(data, file.filename),
                )
            }
        } else {
            val details = data.client.getCommitWithFiles(data.owner, data.repoName, commit.sha)
            val parent = GitRevisionNumber(details.parents.firstOrNull()?.sha ?: commit.sha)
            val head = GitRevisionNumber(commit.sha)
            details.files.orEmpty().map { file ->
                RefComparisonChange(
                    parent, if (file.status == "added") null else GiteaChangePaths.filePath(data, file.filename),
                    head, if (file.status == "removed" || file.status == "deleted") null else GiteaChangePaths.filePath(data, file.filename),
                )
            }
        }
        return GiteaChangeListViewModel(cs, data, changes)
    }

    override fun selectCommit(index: Int) {
        _selectedIndex.value = index
    }

    override fun selectNextCommit() {
        _selectedIndex.value = (_selectedIndex.value + 1).coerceAtMost(_commits.value.lastIndex)
    }

    override fun selectPreviousCommit() {
        _selectedIndex.value = (_selectedIndex.value - 1).coerceAtLeast(0)
    }

    override fun commitHash(commit: GitCommit): String = commit.sha.take(7)
}

/**
 * Changes tree model. Own implementation of the platform's internal
 * `CodeReviewChangeListViewModelBase` (migration ledger I5). Files are
 * never "viewed" (Gitea has no API for it, G4); the discussion count is
 * the number of unresolved threads on the file.
 */
class GiteaChangeListViewModel(
    cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
    override val changes: List<RefComparisonChange>,
) : CodeReviewChangeListViewModel.WithDetails {

    override val project: Project get() = data.project

    private val _selectionRequests = MutableSharedFlow<CodeReviewChangeListViewModel.SelectionRequest>(replay = 1)
    override val selectionRequests: SharedFlow<CodeReviewChangeListViewModel.SelectionRequest> = _selectionRequests.asSharedFlow()

    private val _selection = MutableStateFlow<ChangesSelection?>(null)
    override val changesSelection: StateFlow<ChangesSelection?> = _selection.asStateFlow()

    override val detailsByChange: StateFlow<Map<RefComparisonChange, CodeReviewChangeDetails>> =
        data.threads.map { threads ->
            changes.associateWith { change ->
                val path = change.filePathAfter?.let { GiteaChangePaths.relative(data, it) }
                val count = threads.count { it.path == path && it.side == ReviewSide.NEW && !it.isResolved }
                CodeReviewChangeDetails(isRead = true, discussions = count)
            }
        }.stateIn(cs, SharingStarted.Eagerly, emptyMap())

    override fun updateSelectedChanges(selection: ChangesSelection?) {
        _selection.value = selection
    }

    override fun showDiffPreview() = showDiff()

    override fun showDiff() {
        val selection = _selection.value
        val selected = selection?.changes ?: changes
        GiteaPullRequestDiff.show(data, selected, selection?.selectedIdx ?: 0)
    }
}

/** Branch chip: source branch, checkout, "show branches" popup. */
class GiteaPullRequestBranchesViewModel(
    private val cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
) : CodeReviewBranchesViewModel {

    private val repository: GitRepository?
        get() {
            val manager = GitRepositoryManager.getInstance(data.project)
            return data.repo.gitRoot?.let { root -> manager.repositories.find { it.root == root } } ?: manager.repositories.firstOrNull()
        }

    override val sourceBranch: StateFlow<String> =
        data.pullRequest.map { it.head.ref }.stateIn(cs, SharingStarted.Eagerly, data.pullRequest.value.head.ref)

    private val _isCheckedOut = MutableStateFlow(false)
    override val isCheckedOut: SharedFlow<Boolean> = _isCheckedOut.asStateFlow()

    private val _showBranches = MutableSharedFlow<CodeReviewBranches>(extraBufferCapacity = 1)
    override val showBranchesRequests: SharedFlow<CodeReviewBranches> = _showBranches.asSharedFlow()

    init {
        refreshCheckedOut()
        data.project.messageBus.connect(cs).subscribe(
            GitRepository.GIT_REPO_CHANGE,
            git4idea.repo.GitRepositoryChangeListener { refreshCheckedOut() },
        )
    }

    private fun refreshCheckedOut() {
        _isCheckedOut.value = repository?.currentBranchName == data.pullRequest.value.head.ref
    }

    override fun fetchAndCheckoutRemoteBranch() {
        val repo = repository ?: return
        cs.launch(Dispatchers.Main) {
            GiteaPullRequestActions.checkout(data.project, repo, data.pullRequest.value.head.ref) { refreshCheckedOut() }
        }
    }

    override fun showBranches() {
        val pr = data.pullRequest.value
        _showBranches.tryEmit(CodeReviewBranches(pr.head.ref, pr.base.ref))
    }
}

/**
 * Status rows: conflicts and CI. Gitea reports commit statuses, not check
 * runs; each status becomes one CI job (migration ledger G11). Gitea
 * statuses carry no "required" flag, so all are treated as required.
 */
class GiteaPullRequestStatusViewModel(
    private val cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
) : CodeReviewStatusViewModel {

    private val _mergeability = MutableStateFlow(GiteaMergeability.OK)

    /** See [GiteaMergeability]: a false `mergeable` is re-checked a few times
     * before it is reported as blocked, since Gitea also reports false while
     * its background conflict check is still running. */
    val mergeability: StateFlow<GiteaMergeability> = _mergeability.asStateFlow()

    /** Platform contract: null while checking, true when blocked. */
    override val hasConflicts: SharedFlow<Boolean?> = _mergeability.map {
        when (it) {
            GiteaMergeability.OK -> false
            GiteaMergeability.CHECKING -> null
            GiteaMergeability.BLOCKED -> true
        }
    }.stateIn(cs, SharingStarted.Eagerly, false)

    override val requiredConversationsResolved: SharedFlow<Boolean> = MutableStateFlow(false)

    private val _ciJobs = MutableStateFlow<List<CodeReviewCIJob>>(emptyList())
    override val ciJobs: SharedFlow<List<CodeReviewCIJob>> = _ciJobs.asStateFlow()

    private val _showJobs = MutableSharedFlow<List<CodeReviewCIJob>>(extraBufferCapacity = 1)
    override val showJobsDetailsRequests: SharedFlow<List<CodeReviewCIJob>> = _showJobs.asSharedFlow()

    init {
        cs.launch {
            data.pullRequest.collectLatest { pr ->
                var assessment = GiteaMergeability.of(pr.state, pr.merged, pr.draft, pr.mergeable)
                _mergeability.value = assessment
                var attempt = 0
                while (assessment == GiteaMergeability.CHECKING && attempt < RECHECK_ATTEMPTS) {
                    attempt++
                    delay(RECHECK_DELAY_MS)
                    val fresh = runCatching { data.client.getPullRequest(data.owner, data.repoName, pr.number) }.getOrNull() ?: continue
                    assessment = GiteaMergeability.of(fresh.state, fresh.merged, fresh.draft, fresh.mergeable)
                    if (assessment == GiteaMergeability.OK) {
                        _mergeability.value = GiteaMergeability.OK
                        data.update(fresh)
                        return@collectLatest
                    }
                }
                if (assessment == GiteaMergeability.CHECKING) _mergeability.value = GiteaMergeability.BLOCKED
            }
        }
        cs.launch {
            // Re-poll while any check is still running, so the row moves from
            // "in progress" to its result without a manual refresh.
            data.pullRequest.collectLatest { pr ->
                do {
                    val combined = runCatching { data.client.getCombinedStatus(data.owner, data.repoName, pr.head.sha) }.getOrNull()
                    if (combined != null) _ciJobs.value = combined.statuses.orEmpty().map(::toJob)
                    val running = _ciJobs.value.any { it.status == CodeReviewCIJobState.PENDING }
                    if (running) delay(CI_POLL_MS)
                } while (running)
            }
        }
    }

    /** Gitea commit status states: pending, success, error, failure, warning.
     * A warning doesn't fail the combined status in Gitea, so it counts as
     * passed here; the platform has no warning state. */
    private fun toJob(status: com.github.hypercookie.giteaplugin.api.dto.CommitStatus) = CodeReviewCIJob(
        status.context ?: status.description ?: "status",
        when (status.status) {
            "success", "warning" -> CodeReviewCIJobState.SUCCESS
            "failure", "error" -> CodeReviewCIJobState.FAILED
            "skipped" -> CodeReviewCIJobState.SKIPPED
            else -> CodeReviewCIJobState.PENDING
        },
        true,
        status.targetUrl,
    )

    private companion object {
        const val CI_POLL_MS = 15_000L
        const val RECHECK_ATTEMPTS = 3
        const val RECHECK_DELAY_MS = 2_000L
    }

    override fun showJobsDetails() {
        val jobs = _ciJobs.value
        val single = jobs.singleOrNull()?.detailsUrl
        if (single != null) {
            cs.launch(Dispatchers.Main) { BrowserUtil.browse(single) }
        } else {
            _showJobs.tryEmit(jobs)
        }
    }
}

internal fun String?.toDate(): Date? = this?.let { runCatching { Date.from(OffsetDateTime.parse(it).toInstant()) }.getOrNull() }
