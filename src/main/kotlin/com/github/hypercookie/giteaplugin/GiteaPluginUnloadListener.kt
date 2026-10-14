package com.github.hypercookie.giteaplugin

import com.github.hypercookie.giteaplugin.pullrequest.diff.GITEA_DIFF_CHAIN_KEY
import com.github.hypercookie.giteaplugin.pullrequest.timeline.GiteaTimelineVirtualFile
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile

/**
 * Closes the editor tabs that keep plugin objects reachable from
 * platform-owned state, so the plugin can be unloaded without a restart
 * (https://plugins.jetbrains.com/docs/intellij/dynamic-plugins.html):
 *
 * - pull request diff tabs: the platform's [ChainDiffVirtualFile] holds
 *   requests carrying this plugin's diff comment context, and its file
 *   editor provider belongs to the platform, so nothing else closes them;
 * - timeline tabs: the platform closes these itself when the file editor
 *   provider is unregistered, but closing them first keeps the teardown in
 *   one place and before any extension is removed.
 *
 * Everything else (coroutines, inlays, gutter renderers, message bus
 * connections) is bound to service coroutine scopes, which the platform
 * cancels on unload.
 */
class GiteaPluginUnloadListener : DynamicPluginListener {

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId != PLUGIN_ID) return
        for (project in ProjectManager.getInstance().openProjects) {
            val manager = FileEditorManager.getInstance(project)
            manager.openFiles.filter(::isPluginFile).forEach(manager::closeFile)
        }
    }

    companion object {
        private val PLUGIN_ID = PluginId.getId("com.github.hypercookie.giteaplugin")

        fun isPluginFile(file: VirtualFile): Boolean =
            file is GiteaTimelineVirtualFile || (file is ChainDiffVirtualFile && file.chain.getUserData(GITEA_DIFF_CHAIN_KEY) == true)
    }
}
