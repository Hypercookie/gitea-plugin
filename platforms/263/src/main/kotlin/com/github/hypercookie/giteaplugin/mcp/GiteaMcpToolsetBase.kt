package com.github.hypercookie.giteaplugin.mcp

import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.intellij.mcpserver.McpToolset

/**
 * How the Gitea tools present themselves in Settings | Tools | MCP Server |
 * Exposed Tools. [McpToolset] defaults to experimental (hidden unless
 * experimental tools are allowed), so the toolset says it isn't, and is
 * user-configurable so each tool can be switched off there. The 252 profile
 * declares the same methods without `override`.
 */
abstract class GiteaMcpToolsetBase : McpToolset {
    override fun isEnabled(): Boolean = GiteaSettings.getInstance().mcpEnabled
    override fun isExperimental(): Boolean = false
    override fun isUserConfigurable(): Boolean = true
    override fun displayName(): String = "Gitea"
    override fun displayDescription(): String = GITEA_MCP_DESCRIPTION
}
