package com.github.hypercookie.giteaplugin.accounts

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level store of configured Gitea accounts (server URL + display
 * name). Non-secret fields persist to XML via [PersistentStateComponent];
 * tokens never touch that file and instead go through the platform's
 * [PasswordSafe], keyed per-account by id.
 */
@Service(Service.Level.APP)
@State(name = "GiteaAccounts", storages = [Storage("gitea-accounts.xml")])
class GiteaAccountManager : PersistentStateComponent<GiteaAccountManager.State> {

    class State {
        var accounts: MutableList<GiteaAccount> = mutableListOf()
    }

    private var myState = State()

    val accounts: List<GiteaAccount>
        get() = myState.accounts.toList()

    fun addAccount(account: GiteaAccount, token: String) {
        myState.accounts.add(account)
        setToken(account, token)
    }

    fun removeAccount(account: GiteaAccount) {
        myState.accounts.removeAll { it.id == account.id }
        setToken(account, null)
    }

    fun getToken(account: GiteaAccount): String? =
        PasswordSafe.instance.getPassword(credentialAttributes(account))

    fun setToken(account: GiteaAccount, token: String?) {
        PasswordSafe.instance.set(
            credentialAttributes(account),
            token?.let { Credentials(account.id, it) },
        )
    }

    private fun credentialAttributes(account: GiteaAccount): CredentialAttributes =
        CredentialAttributes(generateServiceName("Gitea", account.id))

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        @JvmStatic
        fun getInstance(): GiteaAccountManager =
            ApplicationManager.getApplication().getService(GiteaAccountManager::class.java)
    }
}
