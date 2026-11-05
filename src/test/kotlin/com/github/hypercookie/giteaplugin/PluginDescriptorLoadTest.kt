package com.github.hypercookie.giteaplugin

import com.github.hypercookie.giteaplugin.log.GiteaCommitStatusProvider
import com.github.hypercookie.giteaplugin.mcp.GiteaMcpService
import com.github.hypercookie.giteaplugin.mcp.GiteaToolException
import com.github.hypercookie.giteaplugin.mcp.McpInlineComment
import com.github.hypercookie.giteaplugin.mcp.GiteaMcpToolset
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusProvider

/**
 * Guards the plugin descriptor: a broken `<dependencies>` block (for example
 * an unresolvable `intellij.platform.collaborationTools` module dependency)
 * compiles fine but makes the platform skip the whole plugin at load time.
 */
class PluginDescriptorLoadTest : BasePlatformTestCase() {

    fun testPluginLoadsWithCollaborationToolsDependency() {
        val id = PluginId.getId("com.github.hypercookie.giteaplugin")
        val descriptor = PluginManagerCore.getPlugin(id)
        assertNotNull("plugin descriptor not loaded", descriptor)
        assertTrue("plugin disabled at load time", PluginManagerCore.isLoaded(id))
        // Resolvable through the plugin's own class loader, not just the
        // test classpath.
        Class.forName(
            "com.intellij.collaboration.ui.codereview.list.ReviewListViewModel",
            false,
            descriptor!!.pluginClassLoader ?: javaClass.classLoader,
        )
    }

    fun testLogCommitStatusColumnIsRegistered() {
        // Needs the intellij.platform.vcs.log.impl module dependency.
        assertTrue(VcsCommitExternalStatusProvider.getExtensionsWithColumns().any { it is GiteaCommitStatusProvider })
    }

    fun testGiteaMcpToolsetIsRegisteredWithMcpServer() {
        // The optional dependency on the MCP Server plugin must resolve.
        assertTrue(McpToolset.EP.extensionList.any { it is GiteaMcpToolset })
    }

    fun testMcpToolNamesAreUniqueAndPrefixed() {
        val names = GiteaMcpToolset::class.java.methods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name }
        assertTrue("no tools found", names.size >= 15)
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("gitea_") })
    }

    fun testMcpToolsetIsNotExperimentalAndUserConfigurable() {
        // Newer IDEs default toolsets to experimental, which hides them from
        // the MCP server's exposed tools; looked up by JVM name because the
        // methods don't exist on the oldest supported line.
        val toolset = GiteaMcpToolset()
        assertEquals(false, toolset.javaClass.getMethod("isExperimental").invoke(toolset))
        assertEquals(true, toolset.javaClass.getMethod("isUserConfigurable").invoke(toolset))
        assertEquals("Gitea", toolset.javaClass.getMethod("displayName").invoke(toolset))
    }

    fun testGiteaToolsStayListedWhileTheSettingIsOff() {
        // Regression: the IDE computes its tool list once, so gating the toolset
        // with isEnabled() on the setting (off by default) removed the tools for
        // good. They must be listed whatever the setting says; the setting
        // filters them (2026.3) and makes calls fail.
        val settings = GiteaSettings.getInstance()
        val before = settings.mcpEnabled
        try {
            settings.mcpEnabled = false
            assertTrue(McpToolset.enabledToolsets.any { it is GiteaMcpToolset })
            checkMcpServerListsGiteaTools()
            assertThrows(Exception::class.java) { kotlinx.coroutines.runBlocking { GiteaMcpToolset().gitea_list_repositories() } }
        } finally {
            settings.mcpEnabled = before
        }
    }

    fun testPendingReviewWriteToolsNeedBothSwitches() {
        val settings = GiteaSettings.getInstance()
        val (enabled, write) = settings.mcpEnabled to settings.mcpWriteTools
        try {
            val service = GiteaMcpService(project)
            for ((e, w) in listOf(false to false, true to false, false to true)) {
                settings.mcpEnabled = e
                settings.mcpWriteTools = w
                assertThrows(GiteaToolException::class.java) { service.addPendingReviewComment(null, 1, McpInlineComment("a", 1, "x")) }
                assertThrows(GiteaToolException::class.java) { service.discardPendingReview(null, 1, null) }
            }
        } finally {
            settings.mcpEnabled = enabled
            settings.mcpWriteTools = write
        }
    }

    fun testMcpServerListsGiteaTools() = checkMcpServerListsGiteaTools()

    private fun checkMcpServerListsGiteaTools() {
        // What the MCP server itself offers to clients: every provider's
        // tools, built by reflection from the toolsets (schemas included).
        val names = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }.map { it.descriptor.name }
        val expected = GiteaMcpToolset::class.java.methods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name }
        assertTrue("missing: ${expected - names.toSet()}; all: $names", names.containsAll(expected))
    }
}
