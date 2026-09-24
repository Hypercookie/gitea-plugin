package com.github.hypercookie.giteaplugin.settings

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Settings -> Version Control -> Gitea. Add/remove accounts (server URL +
 * token), verify a connection. Deliberately plain Swing + ToolbarDecorator
 * rather than a fully data-bound DSL form — this is Phase 1 scaffolding,
 * expect it to grow (multi-server grouping, per-project default picker UI)
 * once Phase 3 repo-linking needs richer account selection.
 */
class GiteaConfigurable : Configurable {

    private val manager = GiteaAccountManager.getInstance()

    private val listModel = DefaultListModel<GiteaAccount>()
    private val accountList = JBList(listModel)

    private val nameField = JTextField()
    private val serverUrlField = JTextField()
    private val tokenField = JPasswordField()
    private val statusLabel = JLabel(" ")

    override fun getDisplayName(): String = "Gitea"

    override fun createComponent(): JComponent {
        listModel.clear()
        manager.accounts.forEach { listModel.addElement(it) }

        accountList.addListSelectionListener {
            val selected = accountList.selectedValue
            if (selected != null) {
                nameField.text = selected.name
                serverUrlField.text = selected.serverUrl
                tokenField.text = manager.getToken(selected) ?: ""
                statusLabel.text = " "
            }
        }

        val listPanel = ToolbarDecorator.createDecorator(accountList)
            .setAddAction {
                val account = GiteaAccount("New account", "https://")
                manager.addAccount(account, "")
                listModel.addElement(account)
                accountList.setSelectedValue(account, true)
            }
            .setRemoveAction {
                val selected = accountList.selectedValue ?: return@setRemoveAction
                manager.removeAccount(selected)
                listModel.removeElement(selected)
                nameField.text = ""
                serverUrlField.text = ""
                tokenField.text = ""
            }
            .createPanel()

        val detailsPanel = panel {
            row("Name:") { cell(nameField).resizableColumn() }
            row("Server URL:") { cell(serverUrlField).resizableColumn() }
            row("Token:") { cell(tokenField).resizableColumn() }
            row {
                button("Verify connection") { verifyConnection() }
                cell(statusLabel)
            }
            row {
                button("Save selected account") { saveSelected() }
            }
        }

        val root = JPanel(BorderLayout())
        root.add(listPanel, BorderLayout.WEST)
        root.add(detailsPanel, BorderLayout.CENTER)
        return root
    }

    private fun saveSelected() {
        val selected = accountList.selectedValue ?: return
        selected.name = nameField.text
        selected.serverUrl = serverUrlField.text.trimEnd('/')
        manager.setToken(selected, String(tokenField.password))
        accountList.repaint()
        statusLabel.text = "Saved."
    }

    private fun verifyConnection() {
        val serverUrl = serverUrlField.text.trimEnd('/')
        val token = String(tokenField.password)
        statusLabel.text = "Checking..."
        val client = GiteaApiClient(serverUrl, token)
        Thread({
            val result = runCatching { runBlocking { client.getCurrentUser() } }
            SwingUtilities.invokeLater {
                statusLabel.text = result.fold(
                    onSuccess = { "OK — logged in as ${it.login}" },
                    onFailure = { "Failed: ${it.message}" },
                )
            }
        }, "GiteaVerifyConnection").start()
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        // Account/token changes are saved immediately by the Add/Remove/Save
        // actions above rather than batched into apply(), since they touch
        // PasswordSafe (a separate store from the Configurable's own state).
    }
}
