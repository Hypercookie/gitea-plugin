package com.github.hypercookie.giteaplugin.debug

import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.settings.GiteaSettingsListener
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager

/** Adds or removes the request counter widget in every open project when
 * the setting changes. */
class GiteaRequestCounterSettingsListener : GiteaSettingsListener {
    override fun settingsChanged(before: GiteaSettings.State, after: GiteaSettings.State) {
        if (before.showRequestCounter == after.showRequestCounter) return
        for (project in ProjectManager.getInstance().openProjects) {
            project.service<StatusBarWidgetsManager>().updateWidget(GiteaRequestCounterWidgetFactory::class.java)
        }
    }
}
