package com.github.hypercookie.giteaplugin.mcp

import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.intellij.mcpserver.McpToolset

/**
 * How the Gitea tools present themselves in the IDE's MCP server settings
 * (Settings | Tools | MCP Server | Exposed Tools). IDE 2025.2 has no such
 * metadata on [McpToolset]; newer builds add these as interface methods
 * whose default is "experimental, hidden unless enabled". Declared here
 * without `override` so the plugin compiles against 2025.2 and, on newer
 * IDEs, the same JVM methods override those defaults. The 263 profile
 * declares them with `override`.
 */
abstract class GiteaMcpToolsetBase : McpToolset {
    fun isEnabled(): Boolean = GiteaSettings.getInstance().mcpEnabled
    fun isExperimental(): Boolean = false
    fun isUserConfigurable(): Boolean = true
    fun displayName(): String = "Gitea"
    fun displayDescription(): String = GITEA_MCP_DESCRIPTION
}
