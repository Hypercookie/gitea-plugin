package com.github.hypercookie.giteaplugin.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope

/**
 * Application-level coroutine scope for background work with no project
 * context (e.g. the account Settings UI, which is an application-level
 * Configurable). The Platform injects and manages [coroutineScope]'s
 * lifecycle via the light-service constructor convention — it's cancelled
 * automatically on plugin unload, no manual [com.intellij.openapi.Disposable]
 * bookkeeping needed.
 */
@Service(Service.Level.APP)
class GiteaAppCoroutineScope(val coroutineScope: CoroutineScope) {
    companion object {
        @JvmStatic
        fun getInstance(): GiteaAppCoroutineScope =
            ApplicationManager.getApplication().getService(GiteaAppCoroutineScope::class.java)
    }
}

/** Project-level equivalent, for UI (tool window, diff editors) that should
 * stop its in-flight requests when the project closes. */
@Service(Service.Level.PROJECT)
class GiteaProjectCoroutineScope(val coroutineScope: CoroutineScope) {
    companion object {
        @JvmStatic
        fun getInstance(project: Project): GiteaProjectCoroutineScope =
            project.getService(GiteaProjectCoroutineScope::class.java)
    }
}
