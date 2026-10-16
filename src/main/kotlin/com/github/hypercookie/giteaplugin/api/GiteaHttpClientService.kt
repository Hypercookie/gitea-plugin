package com.github.hypercookie.giteaplugin.api

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.util.net.ssl.CertificateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *
 * It trusts the IDE's certificates (Settings | Tools | Server Certificates,
 * so a server with an internal CA works once its certificate is accepted
 * there) and goes through the IDE's proxy settings, which the platform
 * installs as the default proxy selector.
 *
 * It also counts the API requests sent since the IDE started (or since the
 * plugin was loaded), for the debug status bar widget.
 */
@Service(Service.Level.APP)
class GiteaHttpClientService : Disposable {

    val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .sslContext(CertificateManager.getInstance().sslContext)
        .build()

    private val _requestCount = MutableStateFlow(0L)

    /** Gitea API requests sent so far, whatever their outcome. */
    val requestCount: StateFlow<Long> = _requestCount.asStateFlow()

    fun countRequest() {
        _requestCount.update { it + 1 }
    }

    override fun dispose() {
        client.shutdownNow()
    }

    companion object {
        @JvmStatic
        fun getInstance(): GiteaHttpClientService =
            ApplicationManager.getApplication().getService(GiteaHttpClientService::class.java)
    }
}
