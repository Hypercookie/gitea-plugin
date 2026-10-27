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
 * plugin was loaded), for the debug status bar widget, and limits how fast
 * they go to each server ([limiterFor]).
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

    private val byEndpoint = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>()

    fun countRequest(method: String, path: String) {
        byEndpoint.computeIfAbsent("$method ${endpointOf(path)}") { java.util.concurrent.atomic.AtomicLong() }.incrementAndGet()
        _requestCount.update { it + 1 }
    }

    /** Requests sent so far per endpoint (`GET /repos/{owner}/{repo}/pulls/{n}`),
     * most frequent first, so the debug widget shows where they go. */
    fun requestsByEndpoint(): List<Pair<String, Long>> =
        byEndpoint.map { (endpoint, count) -> endpoint to count.get() }.sortedByDescending { it.second }

    private val limiters = java.util.concurrent.ConcurrentHashMap<String, GiteaRequestLimiter>()

    /** The request rate limit of the server at [authority] (`host:port`),
     * shared by all accounts and projects that talk to it. */
    internal fun limiterFor(authority: String): GiteaRequestLimiter =
        limiters.computeIfAbsent(authority.lowercase()) { GiteaRequestLimiter() }

    override fun dispose() {
        client.shutdownNow()
    }

    companion object {
        private val ID_SEGMENT = Regex("\\d+|[0-9a-fA-F]{7,64}")

        /** [path] with owner, repository, numbers and commit ids replaced by
         * placeholders, and a file path under `contents/` dropped. */
        internal fun endpointOf(path: String): String {
            val segments = path.removePrefix("/api/v1").trim('/').split('/')
            val out = ArrayList<String>(segments.size)
            var i = 0
            while (i < segments.size) {
                val segment = segments[i]
                when {
                    segment == "repos" && i + 2 < segments.size && segments[i + 1] != "issues" -> {
                        out += "repos"; out += "{owner}"; out += "{repo}"; i += 3; continue
                    }
                    segment == "contents" -> { out += "contents"; out += "{path}"; break }
                    ID_SEGMENT.matches(segment) -> out += "{id}"
                    else -> out += segment
                }
                i++
            }
            return "/" + out.joinToString("/")
        }

        @JvmStatic
        fun getInstance(): GiteaHttpClientService =
            ApplicationManager.getApplication().getService(GiteaHttpClientService::class.java)
    }
}
