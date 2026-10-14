package com.github.hypercookie.giteaplugin

import com.github.hypercookie.giteaplugin.api.GiteaHttpClientService
import com.github.hypercookie.giteaplugin.pullrequest.diff.GITEA_DIFF_CHAIN_KEY
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.time.Duration

/**
 * Guards dynamic unload. Unit-test mode loads the plugin through the test
 * class path rather than a `PluginClassLoader`, so the platform skips the
 * "class loader was garbage collected" check here; these tests cover the
 * static descriptor check and the pieces this plugin does on unload.
 */
class DynamicUnloadTest : BasePlatformTestCase() {

    fun testDescriptorAllowsUnloadWithoutRestart() {
        // DynamicPlugins is internal platform API; it is used only in tests,
        // which verifyPlugin does not check.
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("com.github.hypercookie.giteaplugin")) as IdeaPluginDescriptorImpl
        assertNull(DynamicPlugins.checkCanUnloadWithoutRestart(descriptor))
    }

    fun testHttpClientIsStoppedWhenServiceIsDisposed() {
        // A running client's selector thread is a GC root for the client
        // and the plugin class loader.
        val service = GiteaHttpClientService()
        service.dispose()
        assertTrue(service.client.awaitTermination(Duration.ofSeconds(5)))
    }

    fun testOnlyGiteaDiffTabsAreClosedOnUnload() {
        val ours = SimpleDiffRequestChain(MessageDiffRequest("ours")).apply { putUserData(GITEA_DIFF_CHAIN_KEY, true) }
        val other = SimpleDiffRequestChain(MessageDiffRequest("other"))
        assertTrue(GiteaPluginUnloadListener.isPluginFile(ChainDiffVirtualFile(ours, "ours")))
        assertFalse(GiteaPluginUnloadListener.isPluginFile(ChainDiffVirtualFile(other, "other")))
    }
}
