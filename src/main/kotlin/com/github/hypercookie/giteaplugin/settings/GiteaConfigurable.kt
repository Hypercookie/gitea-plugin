package com.github.hypercookie.giteaplugin.settings

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.util.GiteaAppCoroutineScope
import com.intellij.openapi.options.Configurable
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

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

    /** Set by any field edit / add / remove; cleared by [apply]. Lets the
     * Settings dialog's OK/Apply buttons reflect real pending state instead
     * of always being disabled — see [isModified]. */
    private var dirty = false

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

        val markDirty = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = mark()
            override fun removeUpdate(e: DocumentEvent) = mark()
            override fun changedUpdate(e: DocumentEvent) = mark()
            private fun mark() {
                dirty = true
            }
        }
        nameField.document.addDocumentListener(markDirty)
        serverUrlField.document.addDocumentListener(markDirty)
        tokenField.document.addDocumentListener(markDirty)

        val listPanel = ToolbarDecorator.createDecorator(accountList)
            .setAddAction {
                val account = GiteaAccount("New account", "https://")
                manager.addAccount(account, "")
                listModel.addElement(account)
                accountList.setSelectedValue(account, true)
                dirty = true
            }
            .setRemoveAction {
                val selected = accountList.selectedValue ?: return@setRemoveAction
                manager.removeAccount(selected)
                listModel.removeElement(selected)
                nameField.text = ""
                serverUrlField.text = ""
                tokenField.text = ""
                dirty = true
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
        dirty = false
    }

    private fun verifyConnection() {
        val serverUrl = serverUrlField.text.trimEnd('/')
        val token = String(tokenField.password)
        statusLabel.text = "Checking..."
        val client = GiteaApiClient(serverUrl, token)
        GiteaAppCoroutineScope.getInstance().coroutineScope.launch {
            val result = runCatching { client.getCurrentUser() }
            withContext(Dispatchers.Main) {
                statusLabel.text = result.fold(
                    onSuccess = { "OK — logged in as ${it.login}" },
                    onFailure = { "Failed: ${it.message}" },
                )
            }
        }
    }

    /** True if a pending field edit / add / remove hasn't been persisted yet
     * (via "Save selected account", or [apply] below) — without this,
     * Settings' OK/Apply buttons stay permanently disabled regardless of
     * actual state, and clicking OK silently drops unsaved field edits. */
    override fun isModified(): Boolean = dirty

    override fun apply() {
        // Persisting is normally immediate (Add/Remove/Save act right away,
        // since they touch PasswordSafe — a store outside this Configurable's
        // own state), but a field edit not yet committed via "Save selected
        // account" would otherwise be silently lost when the user just hits
        // OK/Apply in the Settings dialog. Commit it here too.
        if (dirty) saveSelected()
    }
}
