package com.github.hypercookie.giteaplugin

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

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
}
