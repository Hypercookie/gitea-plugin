package com.github.hypercookie.giteaplugin.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import java.net.http.HttpClient
import java.time.Duration

/**
 * Owner of the plugin's one [HttpClient].
 *
 * Shared across all [GiteaApiClient]s: each java.net.http.HttpClient owns a
 * selector thread + executor, and a fresh GiteaApiClient is constructed on
 * every refresh/action, so a per-instance client would leak one of each per
 * call site.
 *
 * It lives in an application service rather than a static field so the
 * plugin can be unloaded without a restart: the selector thread is a GC
 * root that keeps its client, and through it the plugin class loader,
 * reachable. [dispose] (on plugin unload or IDE shutdown) stops the client,
 * and dropping the service instance drops the last reference to it.
 */
@Service(Service.Level.APP)
class GiteaHttpClientService : Disposable {

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun dispose() {
        client.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun getInstance(): GiteaHttpClientService =
            ApplicationManager.getApplication().getService(GiteaHttpClientService::class.java)
    }
}
