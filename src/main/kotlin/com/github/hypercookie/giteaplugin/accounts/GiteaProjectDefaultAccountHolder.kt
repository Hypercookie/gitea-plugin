package com.github.hypercookie.giteaplugin.accounts

import com.intellij.collaboration.auth.AccountManager
import com.intellij.collaboration.auth.PersistentDefaultAccountHolder
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * The project's default Gitea account, used when several accounts match the
 * project's remotes. Same state name, file and `defaultAccountId` field as
 * the pre-migration `GiteaProjectAccountSettings`.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaProjectAccountSettings", storages = [Storage("gitea-project-account.xml")])
class GiteaProjectDefaultAccountHolder(project: Project, cs: CoroutineScope) :
    PersistentDefaultAccountHolder<GiteaAccount>(project, cs) {

    override fun accountManager(): AccountManager<GiteaAccount, *> = GiteaAccountManager.getInstance()

    override fun notifyDefaultAccountMissing() = Unit

    companion object {
        fun getInstance(project: Project): GiteaProjectDefaultAccountHolder = project.service()
    }
}
