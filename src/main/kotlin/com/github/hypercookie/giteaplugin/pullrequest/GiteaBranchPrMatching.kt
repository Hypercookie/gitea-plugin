package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.dto.PullRequest

/** The upstream of a local branch, as far as pull request matching needs it. */
data class GiteaTrackedBranch(
    /** Branch name on the remote, e.g. `alice/feature` for `origin/alice/feature`. */
    val nameOnRemote: String,
    /** Whether the remote points at the repository the pull requests target. */
    val isBaseRepoRemote: Boolean,
)

/**
 * What the current-branch session was resolved for. Any change (another
 * branch, another upstream, another git root) means the session no longer
 * applies and has to be resolved again.
 */
data class GiteaBranchKey(
    val rootPath: String,
    val owner: String,
    val repo: String,
    val localBranch: String,
    val tracked: GiteaTrackedBranch?,
)

/**
 * How the checked-out branch relates to the session's pull request head.
 * [ahead] and [behind] are null when the head commit isn't available
 * locally (not fetched yet).
 */
data class GiteaBranchSync(
    val rootPath: String,
    val localBranch: String,
    val prNumber: Long,
    val localHead: String?,
    val prHead: String,
    val ahead: Int?,
    val behind: Int?,
)

object GiteaBranchPrMatcher {

    /**
     * The open pull request [localBranch] works on, or null.
     *
     * A branch with an upstream matches only through it, like the GitHub
     * plugin: the upstream must be on a remote of the base repository and
     * have the pull request's head name. A branch without one falls back to
     * the same name. Fork pull requests never match (checkout doesn't
     * support them, and the fork's branch names say nothing about ours). If
     * several pull requests share the head, the most recently updated wins.
     */
    fun match(localBranch: String, tracked: GiteaTrackedBranch?, prs: List<PullRequest>): PullRequest? {
        if (tracked != null && !tracked.isBaseRepoRemote) return null
        val headRef = tracked?.nameOnRemote ?: localBranch
        return prs.filter { it.head.ref == headRef && isSameRepo(it) }.maxByOrNull { it.updatedAt ?: "" }
    }

    /** Whether [pr]'s head branch is in another repository than its base. */
    fun isFork(pr: PullRequest): Boolean = !isSameRepo(pr)

    /** Unknown repository ids (older servers) count as the same repository. */
    private fun isSameRepo(pr: PullRequest): Boolean {
        val head = pr.head.repoId ?: return true
        val base = pr.base.repoId ?: return true
        return head == base
    }
}

object GiteaOutOfSyncBanner {

    /** Whether a session resolved for [sessionBranch] still describes the
     * branch checked out now; false during a checkout until it re-resolves. */
    fun isSessionCurrent(sessionBranch: String?, liveBranch: String?): Boolean =
        liveBranch != null && liveBranch == sessionBranch

    /**
     * Out-of-sync banner text, or null when the checked-out branch is at the
     * pull request head (or isn't the session's branch at all). [sync] is
     * only used when it was computed for exactly these commits; otherwise
     * the text stays generic until the next refresh.
     */
    fun message(sync: GiteaBranchSync, liveBranch: String?, liveHead: String?, pr: PullRequest): String? {
        if (!isSessionCurrent(sync.localBranch, liveBranch) || sync.prNumber != pr.number) return null
        if (liveHead == null || liveHead == pr.head.sha) return null

        val branch = liveBranch!!
        val target = if (pr.head.ref == branch) "pull request #${pr.number}" else "pull request #${pr.number} (${pr.head.ref})"
        val counted = sync.localHead == liveHead && sync.prHead == pr.head.sha
        val ahead = sync.ahead
        val behind = sync.behind
        return when {
            !counted -> "Local branch $branch is out of sync with $target"
            ahead == null || behind == null -> "${target.replaceFirstChar(Char::uppercaseChar)} has commits not yet fetched into local branch $branch"
            ahead > 0 && behind > 0 -> "Local branch $branch has diverged from $target (${commits(ahead)} local, ${commits(behind)} remote)"
            ahead > 0 -> "Local branch $branch is ${commits(ahead)} ahead of $target: push to update it"
            behind > 0 -> "Local branch $branch is ${commits(behind)} behind $target: pull to update it"
            else -> "Local branch $branch is out of sync with $target"
        }
    }

    private fun commits(n: Int) = if (n == 1) "1 commit" else "$n commits"
}
