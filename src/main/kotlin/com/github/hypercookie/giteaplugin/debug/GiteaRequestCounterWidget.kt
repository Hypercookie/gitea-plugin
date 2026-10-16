package com.github.hypercookie.giteaplugin.debug

import com.github.hypercookie.giteaplugin.api.GiteaHttpClientService
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component

/**
 * Debug widget in the status bar: how many Gitea API requests the IDE has
 * sent since it started, across all projects and accounts. Only available
 * while Settings | Version Control | Gitea | Debug turns it on.
 */
class GiteaRequestCounterWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Gitea API Request Counter"

    override fun isAvailable(project: Project): Boolean = GiteaSettings.getInstance().showRequestCounter

    override fun createWidget(project: Project): StatusBarWidget = GiteaRequestCounterWidget(project)

    companion object {
        const val ID = "Gitea.RequestCounter"
    }
}

private class GiteaRequestCounterWidget(project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private val cs = GiteaProjectCoroutineScope.getInstance(project).coroutineScope.giteaChildScope("Gitea request counter")
    private val count = GiteaHttpClientService.getInstance().requestCount

    override fun ID(): String = GiteaRequestCounterWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        cs.launch {
            // A StateFlow drops values the EDT can't keep up with, so a burst
            // of requests repaints the widget once, not once per request.
            count.collect { withContext(Dispatchers.EDT) { statusBar.updateWidget(ID()) } }
        }
    }

    override fun getText(): String = "Gitea API: ${count.value}"

    override fun getTooltipText(): String {
        val top = GiteaHttpClientService.getInstance().requestsByEndpoint().take(TOP_ENDPOINTS)
        val rows = top.joinToString("") { (endpoint, n) -> "<tr><td align=right>$n</td><td>${StringUtil.escapeXmlEntities(endpoint)}</td></tr>" }
        return "<html>Gitea API requests sent since the IDE started, in all projects. Avatar and emoji images are not counted." +
            (if (rows.isEmpty()) "" else "<br><br>Most requested:<table>$rows</table>") + "</html>"
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun dispose() {
        cs.cancel()
    }

    private companion object {
        const val TOP_ENDPOINTS = 10
    }
}
