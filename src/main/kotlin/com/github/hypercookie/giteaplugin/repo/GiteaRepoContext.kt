package com.github.hypercookie.giteaplugin.repo

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount

/** A local git remote resolved to a specific Gitea account + owner/repo. */
data class GiteaRepoContext(
    val account: GiteaAccount,
    val owner: String,
    val repo: String,
    val remoteUrl: String,
)
