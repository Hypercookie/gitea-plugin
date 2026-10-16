package com.github.hypercookie.giteaplugin.accounts

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.GiteaApiException
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
import java.util.concurrent.ConcurrentHashMap

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

    /** Tokens read from the password safe, by account id. Every API request
     * asks for its token (see [clientFor]), and the password safe can be a
     * system keychain call. Dropped whenever accounts or tokens change. */
    private val tokenCache = ConcurrentHashMap<String, String>()

    /** The account's current token, or null when it has none or the account
     * was removed. */
    suspend fun getToken(account: GiteaAccount): String? {
        if (account !in accounts) return null
        tokenCache[account.id]?.let { return it }
        return findCredentials(account)?.takeIf { it.isNotBlank() }?.also { tokenCache[account.id] = it }
    }

    /**
     * A client for [account] that looks its token up on every request, so a
     * new token is used right away and a removed account's token is never
     * sent again, whoever still holds the client.
     */
    fun clientFor(account: GiteaAccount): GiteaApiClient = GiteaApiClient(account.serverUrl) {
        getToken(account) ?: throw GiteaApiException(401, "No token for Gitea account $account; add it again in Settings | Version Control | Gitea")
    }

    override suspend fun updateAccount(account: GiteaAccount, credentials: String) {
        tokenCache.remove(account.id)
        super.updateAccount(account, credentials)
        tokenCache.remove(account.id)
    }

    override suspend fun updateAccounts(accountsWithCredentials: Map<GiteaAccount, String?>) {
        tokenCache.clear()
        super.updateAccounts(accountsWithCredentials)
        tokenCache.clear()
    }

    override suspend fun removeAccount(account: GiteaAccount) {
        tokenCache.remove(account.id)
        super.removeAccount(account)
        tokenCache.remove(account.id)
    }

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
