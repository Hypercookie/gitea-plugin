package com.github.hypercookie.giteaplugin.accounts

import com.intellij.collaboration.auth.AccountManagerBase
import com.intellij.collaboration.auth.AccountsRepository
import com.intellij.collaboration.auth.PasswordSafeCredentialsRepository
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.util.xmlb.annotations.Tag

/**
 * Gitea accounts and their tokens, on the collaboration tools'
 * [AccountManagerBase] (same shape as the GitLab plugin's
 * `PersistentGitLabAccountManager`). Tokens live in the IDE password safe
 * under `generateServiceName("Gitea", account.id)`, the same key the
 * plugin used before the migration, so stored tokens keep working.
 */
@Service(Service.Level.APP)
class GiteaAccountManager : AccountManagerBase<GiteaAccount, String>(logger<GiteaAccountManager>()) {

    override fun accountsRepository(): AccountsRepository<GiteaAccount> = service<GiteaPersistentAccounts>()

    override fun credentialsRepository() =
        PasswordSafeCredentialsRepository<GiteaAccount, String>(SERVICE_NAME, PasswordSafeCredentialsRepository.CredentialsMapper.Simple)

    val accounts: Set<GiteaAccount> get() = accountsState.value

    suspend fun getToken(account: GiteaAccount): String? = findCredentials(account)

    companion object {
        const val SERVICE_NAME = "Gitea"

        fun getInstance(): GiteaAccountManager = service()
    }
}

/**
 * Account list storage. Keeps the pre-migration file, state name and bean
 * layout (`<GiteaAccount id name serverUrl>`), so existing accounts load.
 */
@Service(Service.Level.APP)
@State(name = "GiteaAccounts", storages = [Storage("gitea-accounts.xml")])
class GiteaPersistentAccounts : AccountsRepository<GiteaAccount>, PersistentStateComponent<GiteaPersistentAccounts.AccountsState> {

    class AccountsState {
        var accounts: MutableList<AccountBean> = mutableListOf()
    }

    @Tag("GiteaAccount")
    class AccountBean {
        var id: String = ""
        var name: String = ""
        var serverUrl: String = ""
    }

    private var state = AccountsState()

    override var accounts: Set<GiteaAccount>
        get() = state.accounts.filter { it.id.isNotBlank() }.mapTo(LinkedHashSet()) { GiteaAccount(it.id, it.name, it.serverUrl) }
        set(value) {
            state = AccountsState().apply {
                accounts = value.mapTo(mutableListOf()) { account ->
                    AccountBean().apply {
                        id = account.id
                        name = account.name
                        serverUrl = account.serverUrl
                    }
                }
            }
        }

    override fun getState(): AccountsState = state

    override fun loadState(state: AccountsState) {
        this.state = state
    }
}
