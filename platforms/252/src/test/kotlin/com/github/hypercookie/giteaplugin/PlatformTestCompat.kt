package com.github.hypercookie.giteaplugin

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Test-only platform API that differs between IDE lines; each line in
// platforms/ has its own copy of this file.

/** Why the plugin can't be unloaded without a restart, or null if it can.
 * DynamicPlugins is internal platform API, used only in tests, which
 * verifyPlugin does not check. */
internal fun unloadWithoutRestartBlocker(id: PluginId): String? =
    DynamicPlugins.checkCanUnloadWithoutRestart(PluginManagerCore.getPlugin(id) as IdeaPluginDescriptorImpl)
