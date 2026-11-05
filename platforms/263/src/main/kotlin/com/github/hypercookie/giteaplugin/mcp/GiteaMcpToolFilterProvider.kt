package com.github.hypercookie.giteaplugin.mcp

import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.settings.GiteaSettingsListener
import com.intellij.mcpserver.McpToolFilterProvider
import com.intellij.mcpserver.McpToolFilterProvider.McpToolFilterContext
import com.intellij.mcpserver.McpToolInvocationMode
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.application.ApplicationManager
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Takes the Gitea tools out of the MCP server while "Offer Gitea tools" is off.
 * The IDE computes its tool list once, so [McpToolset.isEnabled] can't follow a
 * setting; a filter provider can, because it re-applies when [getUpdates] emits.
 */
class GiteaMcpToolFilterProvider : McpToolFilterProvider {
    override fun applyFilters(
        context: McpToolFilterContext,
        clientInfo: Implementation?,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ) {
        if (GiteaSettings.getInstance().mcpEnabled) return
        context.updateState(enabled = false) { it.descriptor.category.fullyQualifiedName == GiteaMcpToolset::class.qualifiedName }
    }

    override fun getUpdates(
        clientInfo: Implementation?,
        scope: CoroutineScope,
        sessionOptions: McpServerService.McpSessionOptions?,
        invocationMode: McpToolInvocationMode,
    ): Flow<Unit> = callbackFlow {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(GiteaSettingsListener.TOPIC, GiteaSettingsListener { before, after ->
            if (before.mcpEnabled != after.mcpEnabled) trySend(Unit)
        })
        awaitClose { connection.disconnect() }
    }
}
