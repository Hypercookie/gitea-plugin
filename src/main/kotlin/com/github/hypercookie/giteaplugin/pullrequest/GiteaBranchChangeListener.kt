package com.github.hypercookie.giteaplugin.pullrequest

import com.intellij.openapi.project.Project
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener

/**
 * Invalidates [GiteaBranchPrResolverService]'s per-branch cache whenever a
 * git repo changes (branch switch, checkout, etc.) so the next file opened
 * on a new branch re-resolves its PR instead of reusing a stale answer.
 * Registered via `<projectListeners>` against git4idea's own
 * [GitRepository.GIT_REPO_CHANGE] topic.
 *
 * Known v1 limitation: this does not refresh gutters already installed in
 * editors that were open *before* the branch switch — only newly opened
 * ones pick up the new branch's PR. Acceptable for now; revisit if it
 * proves confusing in practice.
 */
class GiteaBranchChangeListener(private val project: Project) : GitRepositoryChangeListener {
    override fun repositoryChanged(repository: GitRepository) {
        GiteaBranchPrResolverService.getInstance(project).invalidate()
    }
}
