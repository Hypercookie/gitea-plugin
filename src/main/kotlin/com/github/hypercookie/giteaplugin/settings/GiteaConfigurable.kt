package com.github.hypercookie.giteaplugin.settings

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.accounts.GiteaProjectDefaultAccountHolder
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.collaboration.auth.AccountDetails
import com.intellij.collaboration.auth.ui.AccountsListModel
import com.intellij.collaboration.auth.ui.AccountsPanelActionsController
import com.intellij.collaboration.auth.ui.AccountsPanelFactory
import com.intellij.collaboration.auth.ui.LazyLoadingAccountsDetailsProvider
import com.intellij.collaboration.auth.ui.MutableAccountsListModel
import com.intellij.collaboration.auth.ui.cancelOnRemoval
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import icons.CollaborationToolsIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.awt.Image
import java.net.URI
import javax.imageio.ImageIO
import javax.swing.JComponent

/**
 * Settings | Version Control | Gitea: the platform's accounts list (add via
 * token login dialog, edit token, remove, set project default), the same
 * page shape as the GitLab plugin's `GitLabSettingsConfigurable`, followed by
 * the IDE-wide feature switches of [GiteaSettings].
 */
class GiteaConfigurable(private val project: Project) : BoundConfigurable("Gitea") {

    override fun createPanel(): DialogPanel {
        val accountManager = GiteaAccountManager.getInstance()
        val defaultAccountHolder = GiteaProjectDefaultAccountHolder.getInstance(project)
        val scope = GiteaProjectCoroutineScope.getInstance(project).coroutineScope.giteaChildScope("Gitea settings")
        Disposer.register(disposable!!) { scope.cancel() }

        val accountsModel = GiteaAccountsListModel()
        val detailsProvider = GiteaAccountsDetailsProvider(scope) { account ->
            accountsModel.newCredentials[account] ?: accountManager.findCredentials(account)
        }.apply { cancelOnRemoval(accountsModel.accountsListModel) }
        val actionsController = GiteaAccountsPanelActionsController(project, accountsModel)
        val panelFactory = AccountsPanelFactory(scope, accountManager, defaultAccountHolder, accountsModel)

        val settings = GiteaSettings.getInstance()
        return panel {
            row {
                panelFactory.accountsPanelCell(this, detailsProvider, actionsController).align(Align.FILL)
            }.resizableRow()
            group("Features") {
                row {
                    checkBox("Show commit checks in the Git log")
                        .bindSelected(settings::commitChecksInLog)
                        .comment("Adds the Gitea Commit Checks column. When off, no commit statuses are fetched.")
                }
                row {
                    checkBox("Show review comments in the editor for the checked-out pull request branch")
                        .bindSelected(settings::reviewInEditor)
                        .comment("Also the editor banner for unsubmitted comments and an out-of-date branch. When off, the plugin no longer looks up the current branch's pull request on git changes.")
                }
                row {
                    checkBox("Show reactions on comments")
                        .bindSelected(settings::showReactions)
                        .comment("Each comment needs one request for its reactions. Applies to pull requests opened afterwards.")
                }
                row {
                    checkBox("Suggest creating a pull request after pushing a branch")
                        .bindSelected(settings::suggestPullRequestAfterPush)
                }
                row {
                    checkBox("Reload open pull requests when the IDE window regains focus")
                        .bindSelected(settings::refreshOnFrameActivation)
                }
            }
            group("Debug") {
                row {
                    checkBox("Show Gitea API request counter in the status bar")
                        .bindSelected(settings::showRequestCounter)
                        .comment("Counts every request sent to a Gitea server since the IDE started.")
                }
            }
        }
    }

    override fun apply() {
        val settings = GiteaSettings.getInstance()
        val before = settings.state
        super.apply()
        settings.notifyChanged(before)
    }
}

class GiteaAccountsListModel : MutableAccountsListModel<GiteaAccount, String>(), AccountsListModel.WithDefault<GiteaAccount, String> {
    override var defaultAccount: GiteaAccount? = null
}

class GiteaAccountDetails(override val name: String, override val avatarUrl: String?) : AccountDetails

class GiteaAccountsDetailsProvider(
    scope: kotlinx.coroutines.CoroutineScope,
    private val tokenSupplier: suspend (GiteaAccount) -> String?,
) : LazyLoadingAccountsDetailsProvider<GiteaAccount, GiteaAccountDetails>(scope, CollaborationToolsIcons.Review.DefaultAvatar) {

    override suspend fun loadDetails(account: GiteaAccount): Result<GiteaAccountDetails> {
        val token = tokenSupplier(account) ?: return Result.Error("Token is missing", true)
        return try {
            val user = withContext(Dispatchers.IO) { GiteaApiClient(account.serverUrl, token).getCurrentUser() }
            Result.Success(GiteaAccountDetails(user.fullName?.takeIf { it.isNotBlank() } ?: user.login, user.avatarUrl))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: com.github.hypercookie.giteaplugin.api.GiteaApiException) {
            Result.Error(if (e.statusCode == 401) "Token is invalid" else e.message, e.statusCode == 401)
        } catch (e: Exception) {
            Result.Error(e.message, false)
        }
    }

    override suspend fun loadAvatar(account: GiteaAccount, url: String): Image? =
        withContext(Dispatchers.IO) { runCatching { ImageIO.read(URI(url).toURL()) }.getOrNull() }
}

class GiteaAccountsPanelActionsController(
    private val project: Project,
    private val model: GiteaAccountsListModel,
) : AccountsPanelActionsController<GiteaAccount> {

    override val isAddActionWithPopup: Boolean = false

    override fun addAccount(parentComponent: JComponent, point: RelativePoint?) {
        val result = GiteaLogin.logIn(project, parentComponent, ::isUnique) ?: return
        model.add(result.account, result.token)
    }

    override fun editAccount(parentComponent: JComponent, account: GiteaAccount) {
        val result = GiteaLogin.updateToken(project, parentComponent, account, ::isUnique) ?: return
        model.update(account, result.token)
    }

    private fun isUnique(serverUrl: String, login: String) =
        model.accounts.none { it.serverUrl == serverUrl && it.name == login }
}
