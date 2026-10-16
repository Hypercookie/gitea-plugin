package com.github.hypercookie.giteaplugin.util

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.logger
import java.net.URI
import javax.swing.event.HyperlinkEvent
import javax.swing.event.HyperlinkListener

/**
 * Opens URLs that came from a Gitea server (pull request pages, CI
 * `target_url`s, links in comments) in the browser. Only absolute `http`
 * and `https` URLs are opened: [BrowserUtil.browse] hands any other scheme
 * (`file:`, `smb:`, an app's own protocol) to the operating system, which a
 * server or anyone who can post a commit status could use to launch a local
 * handler from a reviewer's click.
 */
object GiteaBrowser {

    private val LOG = logger<GiteaBrowser>()

    /** Whether [url] is an absolute http(s) URL with a host. */
    fun isSafe(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()
    }

    /** Opens [url] if [isSafe]; otherwise does nothing. Returns whether it opened. */
    fun open(url: String?): Boolean {
        if (!isSafe(url)) {
            if (!url.isNullOrBlank()) LOG.info("Not opening a non-http(s) URL from the server: $url")
            return false
        }
        BrowserUtil.browse(url!!.trim())
        return true
    }

    /** Hyperlink listener for panes showing server content. */
    val hyperlinkListener: HyperlinkListener = HyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) open(e.url?.toExternalForm() ?: e.description)
    }
}
