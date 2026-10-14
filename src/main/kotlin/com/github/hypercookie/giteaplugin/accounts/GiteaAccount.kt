package com.github.hypercookie.giteaplugin.accounts

import com.intellij.collaboration.api.ServerPath
import com.intellij.collaboration.auth.ServerAccount
import java.net.URI

/** A Gitea server, by its base URL (may include a sub-path). */
data class GiteaServerPath(val url: String) : ServerPath {
    override fun toURI(): URI = URI(url)
    override fun toString(): String = url
}

/**
 * A configured Gitea account. Built on the collaboration tools'
 * [ServerAccount], like the GitLab plugin's `GitLabAccount`; equality is by
 * [id] (from [com.intellij.collaboration.auth.Account]).
 */
class GiteaAccount(
    override val id: String = generateId(),
    override val name: String = "",
    serverUrl: String = "",
) : ServerAccount() {
    val serverUrl: String = serverUrl.trimEnd('/')
    override val server: GiteaServerPath = GiteaServerPath(this.serverUrl)

    override fun toString(): String = if (name.isNotBlank()) "$name@$serverUrl" else serverUrl
}
