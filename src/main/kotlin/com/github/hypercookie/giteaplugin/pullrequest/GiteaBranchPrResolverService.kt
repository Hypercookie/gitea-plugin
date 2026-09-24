package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves "the open PR for whatever branch is currently checked out," so
 * comments can show up in a regular editor no matter how the user got onto
 * that branch (checked it out from our PR list, or via plain git) — see
 * docs/plan/pr-review-feature-plan.md, "Architecture pivot."
 *
 * Cached per branch name; callers should [invalidate] on branch change
 * ([git4idea.repo.GitRepository.GIT_REPO_CHANGE]) rather than re-fetching on
 * every editor open.
 */
@Service(Service.Level.PROJECT)
class GiteaBranchPrResolverService(private val project: Project) {

    private val cacheByBranch = ConcurrentHashMap<String, PullRequest?>()

    fun currentBranchName(): String? =
        GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.currentBranch?.name

    /** Non-suspending, no network/credential access — only returns a PR if
     * [resolveForCurrentBranch] has already cached one for this branch.
     * Used by [PrReviewEditorNotificationProvider], which must not touch
     * `PasswordSafe`/the network from inside its read-action context. */
    fun peekCachedPr(branch: String): PullRequest? = cacheByBranch[branch]

    /**
     * The open PR whose head ref matches the current branch, or null if
     * there isn't one. If more than one open PR shares the same head ref
     * (rare — e.g. two PRs from the same branch to different targets), picks
     * the most recently updated one rather than blocking.
     */
    suspend fun resolveForCurrentBranch(context: GiteaRepoContext, client: GiteaApiClient): PullRequest? {
        val branch = currentBranchName() ?: return null
        cacheByBranch[branch]?.let { return it }
        if (cacheByBranch.containsKey(branch)) return null // cached "no match"

        val prs = client.listPullRequests(context.owner, context.repo)
        val match = prs.filter { it.head.ref == branch }.maxByOrNull { it.updatedAt ?: "" }
        cacheByBranch[branch] = match
        return match
    }

    fun invalidate() {
        cacheByBranch.clear()
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): GiteaBranchPrResolverService =
            project.getService(GiteaBranchPrResolverService::class.java)
    }
}
