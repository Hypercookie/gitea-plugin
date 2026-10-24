package com.github.hypercookie.giteaplugin

import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.PluginMainDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId

// Test-only platform API that differs between IDE lines; each line in
// platforms/ has its own copy of this file.

/** Why the plugin can't be unloaded without a restart, or null if it can.
 * DynamicPlugins is internal platform API, used only in tests, which
 * verifyPlugin does not check. Since 263 it takes a PluginMainDescriptor
 * and logs the reason instead of returning it. */
internal fun unloadWithoutRestartBlocker(id: PluginId): String? =
    if (DynamicPlugins.checkCanUnloadWithoutRestart(PluginManagerCore.getPlugin(id) as PluginMainDescriptor)) null
    else "DynamicPlugins.checkCanUnloadWithoutRestart is false; idea.log has the reason"
