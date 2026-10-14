package com.github.hypercookie.giteaplugin.repo

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.accounts.GiteaProjectAccountSettings
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import git4idea.repo.GitRepositoryManager

/**
 * Resolves this project's local git remotes to configured Gitea accounts.
 * Account-driven matching (URL host + path prefix), not hostname inference —
 * see [GiteaUrlUtil] for why.
 */
@Service(Service.Level.PROJECT)
class GiteaRepoLinkService(private val project: Project) {

    /** Every (account, owner, repo) match across all local git repos/remotes
     * and all configured accounts. More than one match can mean either
     * multiple remotes point at Gitea, or one remote matches multiple
     * configured accounts on the same server — callers should prompt to
     * disambiguate rather than silently picking one. */
    fun findAllMatches(): List<GiteaRepoContext> {
        val accounts = GiteaAccountManager.getInstance().accounts
        if (accounts.isEmpty()) return emptyList()

        val serverByAccount = accounts.mapNotNull { account ->
            GiteaUrlUtil.parseServerUrl(account.serverUrl)?.let { account to it }
        }

        val matches = mutableListOf<GiteaRepoContext>()
        for (repo in GitRepositoryManager.getInstance(project).repositories) {
            for (remote in repo.remotes) {
                for (url in remote.urls) {
                    val parsedRemote = GiteaUrlUtil.parseRemoteUrl(url) ?: continue
                    for ((account, server) in serverByAccount) {
                        if (!GiteaUrlUtil.isUnderServer(parsedRemote, server)) continue
                        val (owner, repoName) = GiteaUrlUtil.ownerAndRepo(parsedRemote, server) ?: continue
                        matches += GiteaRepoContext(account, owner, repoName, url, repo.root)
                    }
                }
            }
        }
        return matches.distinct()
    }

    /**
     * A single best match for this project, or null if there's none or the
     * match is ambiguous (multiple candidates) without a project default
     * account configured to break the tie.
     */
    fun resolveDefault(): GiteaRepoContext? {
        val all = findAllMatches()
        if (all.size <= 1) return all.firstOrNull()

        val defaultAccountId = GiteaProjectAccountSettings.getInstance(project).defaultAccountId
            ?: return null
        return all.singleOrNull { it.account.id == defaultAccountId }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): GiteaRepoLinkService =
            project.getService(GiteaRepoLinkService::class.java)
    }
}
