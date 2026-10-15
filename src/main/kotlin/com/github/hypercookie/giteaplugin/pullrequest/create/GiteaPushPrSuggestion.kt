package com.github.hypercookie.giteaplugin.pullrequest.create

import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrMatcher
import com.github.hypercookie.giteaplugin.pullrequest.GiteaTrackedBranch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** A pushed branch, as far as suggesting a pull request for it needs. */
data class GiteaPushedBranch(
    /** Local branch name, e.g. `feature`. */
    val localBranch: String,
    /** Branch name on the remote it was pushed to. */
    val remoteBranch: String,
)

/**
 * When to offer creating a pull request after a push, and where that
 * offer leads. Plain logic, no IDE or network access.
 */
object GiteaPushPrSuggestion {

    private const val REFS_HEADS = "refs/heads/"
    private const val REFS_REMOTES = "refs/remotes/"

    /**
     * The branch a push result describes, or null when it pushed no branch
     * (a tag, a detached HEAD). [sourceBranch] and [targetBranch] are
     * `GitPushRepoResult`'s: `refs/heads/feature` and
     * `refs/remotes/origin/feature`.
     */
    fun pushedBranch(sourceBranch: String, targetBranch: String, targetRemote: String): GiteaPushedBranch? {
        if (!sourceBranch.startsWith(REFS_HEADS)) return null
        val local = sourceBranch.removePrefix(REFS_HEADS)
        val remote = targetBranch.removePrefix(REFS_REMOTES).removePrefix("$targetRemote/")
        if (local.isEmpty() || remote.isEmpty()) return null
        return GiteaPushedBranch(local, remote)
    }

    /**
     * Whether to offer a pull request for [pushed]: not for the
     * repository's default branch (or whatever it can't be told apart
     * from), and not when a pull request is already open for it, matched
     * the same way the checked-out branch finds its pull request.
     */
    fun shouldSuggest(pushed: GiteaPushedBranch, defaultBranch: String?, openPrs: List<PullRequest>): Boolean {
        if (defaultBranch.isNullOrEmpty() || pushed.remoteBranch == defaultBranch) return false
        val tracked = GiteaTrackedBranch(pushed.remoteBranch, isBaseRepoRemote = true)
        return GiteaBranchPrMatcher.match(pushed.localBranch, tracked, openPrs) == null
    }

    /** Gitea's "new pull request" page for [head] into [base]. */
    fun compareUrl(serverUrl: String, owner: String, repo: String, base: String, head: String): String =
        "${serverUrl.trimEnd('/')}/${enc(owner)}/${enc(repo)}/compare/${encBranch(base)}...${encBranch(head)}"

    private fun enc(segment: String): String = URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")

    // Gitea takes branch names with slashes unescaped in the compare path.
    private fun encBranch(branch: String): String = branch.split('/').joinToString("/") { enc(it) }
}
