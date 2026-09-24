package com.github.hypercookie.giteaplugin.accounts

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Per-project pick of which application-level [GiteaAccount] this project
 * uses by default, for workspaces with multiple configured accounts/servers.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaProjectAccountSettings", storages = [Storage("gitea-project-account.xml")])
class GiteaProjectAccountSettings : PersistentStateComponent<GiteaProjectAccountSettings.State> {

    class State {
        var defaultAccountId: String? = null
    }

    private var myState = State()

    var defaultAccountId: String?
        get() = myState.defaultAccountId
        set(value) {
            myState.defaultAccountId = value
        }

    fun defaultAccount(): GiteaAccount? =
        defaultAccountId?.let { id -> GiteaAccountManager.getInstance().accounts.find { it.id == id } }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): GiteaProjectAccountSettings =
            project.getService(GiteaProjectAccountSettings::class.java)
    }
}
