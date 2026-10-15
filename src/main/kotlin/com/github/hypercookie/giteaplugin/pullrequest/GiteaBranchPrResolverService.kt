package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaUrlUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves "the open PR for whatever branch is currently checked out," so
 * comments can show up in a regular editor no matter how the user got onto
 * that branch (checked it out from our PR list, or via plain git) — see
 * docs/plan/pr-review-feature-plan.md, "Architecture pivot."
 *
 * Works on the git repository the [GiteaRepoContext] came from, not the
 * project's first one, and matches through the branch's upstream (see
 * [GiteaBranchPrMatcher]). "No pull request" is cached per [GiteaBranchKey]
 * for [NO_MATCH_TTL_MS]: git reports repository changes on every fetch,
 * commit and index update, and listing pages through every open pull
 * request. A match isn't cached; once there is a session, callers re-fetch
 * that one pull request instead of listing.
 */
@Service(Service.Level.PROJECT)
class GiteaBranchPrResolverService(private val project: Project) {

    /** When each key last resolved to no pull request. */
    private val noMatchAt = ConcurrentHashMap<GiteaBranchKey, Long>()

    fun repositoryFor(context: GiteaRepoContext): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val root = context.gitRoot ?: return manager.repositories.firstOrNull()
        return manager.getRepositoryForRootQuick(root)
    }

    /** What is checked out in [context]'s repository right now; null on a
     * detached HEAD. No network or credential access. */
    fun currentBranchKey(context: GiteaRepoContext): GiteaBranchKey? {
        val repository = repositoryFor(context) ?: return null
        val branch = repository.currentBranch?.name ?: return null
        val tracked = repository.getBranchTrackInfo(branch)?.let { info ->
            GiteaTrackedBranch(info.remoteBranch.nameForRemoteOperations, isRemoteOf(info.remote, context))
        }
        return GiteaBranchKey(repository.root.path, context.owner, context.repo, branch, tracked)
    }

    /** Whether any of [remote]'s URLs points at [context]'s repository. */
    private fun isRemoteOf(remote: GitRemote, context: GiteaRepoContext): Boolean {
        val server = GiteaUrlUtil.parseServerUrl(context.account.serverUrl) ?: return false
        return remote.urls.any { url ->
            val parsed = GiteaUrlUtil.parseRemoteUrl(url) ?: return@any false
            if (!GiteaUrlUtil.isUnderServer(parsed, server)) return@any false
            val (owner, repo) = GiteaUrlUtil.ownerAndRepo(parsed, server) ?: return@any false
            owner.equals(context.owner, ignoreCase = true) && repo.equals(context.repo, ignoreCase = true)
        }
    }

    /**
     * The open PR for [key], or null if there isn't one. Skips the request
     * while [key] resolved to nothing less than [NO_MATCH_TTL_MS] ago.
     */
    suspend fun resolve(context: GiteaRepoContext, client: GiteaApiClient, key: GiteaBranchKey): PullRequest? {
        noMatchAt[key]?.let { if (System.currentTimeMillis() - it < NO_MATCH_TTL_MS) return null }
        val prs = client.listPullRequests(context.owner, context.repo)
        val match = GiteaBranchPrMatcher.match(key.localBranch, key.tracked, prs)
        if (match == null) noMatchAt[key] = System.currentTimeMillis() else noMatchAt.remove(key)
        return match
    }

    fun invalidate() {
        noMatchAt.clear()
    }

    companion object {
        private const val NO_MATCH_TTL_MS = 60_000L

        @JvmStatic
        fun getInstance(project: Project): GiteaBranchPrResolverService =
            project.getService(GiteaBranchPrResolverService::class.java)
    }
}
