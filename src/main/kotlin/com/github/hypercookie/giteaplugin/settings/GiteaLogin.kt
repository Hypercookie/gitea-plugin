package com.github.hypercookie.giteaplugin.settings

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.util.GiteaAppCoroutineScope
import com.intellij.collaboration.auth.ui.login.LoginModel
import com.intellij.collaboration.auth.ui.login.LoginPanelModelBase
import com.intellij.collaboration.auth.ui.login.TokenLoginDialog
import com.intellij.collaboration.auth.ui.login.TokenLoginInputPanelFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import javax.swing.JComponent

/**
 * Token login, like the GitLab plugin's `GitLabTokenLoginPanelModel`: the
 * token is checked against `GET /api/v1/user` before anything is saved.
 */
class GiteaTokenLoginPanelModel(
    private val requiredLogin: String? = null,
    private val isUnique: (serverUrl: String, login: String) -> Boolean,
) : LoginPanelModelBase() {

    override suspend fun checkToken(): String {
        val server = normalizedServer()
        val user = withContext(Dispatchers.IO) { GiteaApiClient(server, token).getCurrentUser() }
        if (requiredLogin != null && requiredLogin != user.login) {
            throw IllegalStateException("Token belongs to ${user.login}, not $requiredLogin")
        }
        if (!isUnique(server, user.login)) throw IllegalStateException("Account ${user.login} is already added")
        return user.login
    }

    fun normalizedServer(): String {
        val trimmed = serverUri.trim().trimEnd('/')
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        URI(withScheme).toURL()
        return withScheme
    }
}

data class GiteaLoginResult(val account: GiteaAccount, val token: String)

object GiteaLogin {

    fun logIn(
        project: Project?,
        parent: JComponent?,
        isUnique: (String, String) -> Boolean,
    ): GiteaLoginResult? {
        val model = GiteaTokenLoginPanelModel(null, isUnique).apply { serverUri = "https://" }
        if (!show(project, parent, model, "Add Gitea Account", serverFieldDisabled = false)) return null
        val login = (model.loginState.value as? LoginModel.LoginState.Connected)?.username ?: return null
        val server = model.normalizedServer()
        if (!confirmInsecure(project, server)) return null
        return GiteaLoginResult(GiteaAccount(name = login, serverUrl = server), model.token)
    }

    fun updateToken(
        project: Project?,
        parent: JComponent?,
        account: GiteaAccount,
        isUnique: (String, String) -> Boolean,
    ): GiteaLoginResult? {
        val withoutSelf: (String, String) -> Boolean = { server, login ->
            (server == account.serverUrl && login == account.name) || isUnique(server, login)
        }
        val model = GiteaTokenLoginPanelModel(null, withoutSelf).apply { serverUri = account.serverUrl }
        if (!show(project, parent, model, "Update Gitea Token", serverFieldDisabled = true)) return null
        val login = (model.loginState.value as? LoginModel.LoginState.Connected)?.username ?: return null
        return GiteaLoginResult(GiteaAccount(id = account.id, name = login, serverUrl = model.normalizedServer()), model.token)
    }

    /**
     * Over plain `http://` the token travels unencrypted with every request.
     * That's fine for a local dev instance; anywhere else the user confirms
     * it. True when the account may be added.
     */
    private fun confirmInsecure(project: Project?, server: String): Boolean {
        if (!isInsecure(server)) return true
        return Messages.showOkCancelDialog(
            project,
            "$server uses plain HTTP, so your access token is sent unencrypted with every request. Use an https:// address if the server supports it.",
            "Unencrypted Connection",
            "Add Anyway",
            Messages.getCancelButton(),
            Messages.getWarningIcon(),
        ) == Messages.OK
    }

    /** Plain HTTP to a host other than this machine. */
    internal fun isInsecure(server: String): Boolean {
        val uri = runCatching { URI(server) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.lowercase()?.removeSurrounding("[", "]") ?: return true
        return !(host == "localhost" || host.endsWith(".localhost") || host == "::1" || host.startsWith("127."))
    }

    private fun show(project: Project?, parent: JComponent?, model: GiteaTokenLoginPanelModel, title: String, serverFieldDisabled: Boolean): Boolean {
        val cs = GiteaAppCoroutineScope.getInstance().coroutineScope
        val dialog = TokenLoginDialog(project, cs, parent, model, title, null) {
            TokenLoginInputPanelFactory(model).createIn(
                this,
                serverFieldDisabled,
                "Create an access token in Gitea under Settings | Applications with read and write access to repositories and issues.",
                null,
            )
        }
        return dialog.showAndGet() && dialog.exitCode == DialogWrapper.OK_EXIT_CODE
    }
}
