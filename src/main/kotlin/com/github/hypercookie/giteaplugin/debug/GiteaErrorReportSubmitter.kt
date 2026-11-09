package com.github.hypercookie.giteaplugin.debug

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.diagnostic.ErrorReportSubmitter
import com.intellij.openapi.diagnostic.IdeaLoggingEvent
import com.intellij.openapi.diagnostic.SubmittedReportInfo
import java.awt.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import com.intellij.util.Consumer

/**
 * Offers a "Report to plugin author" button in the IDE's error dialog for
 * exceptions thrown by this plugin. It opens a pre-filled GitHub issue in the
 * browser, so the user reviews and submits it; nothing is sent automatically
 * and no token is needed.
 */
class GiteaErrorReportSubmitter : ErrorReportSubmitter() {

    override fun getReportActionText(): String = "Report on GitHub"

    override fun submit(
        events: Array<out IdeaLoggingEvent>,
        additionalInfo: String?,
        parentComponent: Component,
        consumer: Consumer<in SubmittedReportInfo>,
    ): Boolean {
        val version = PluginManager.getPluginByClass(GiteaErrorReportSubmitter::class.java)?.version ?: "unknown"
        val ide = ApplicationInfo.getInstance().let { "${it.fullApplicationName} (${it.build.asString()})" }
        val url = buildIssueUrl(
            pluginVersion = version,
            ide = ide,
            additionalInfo = additionalInfo,
            stackTrace = events.firstOrNull()?.throwableText,
            message = events.firstOrNull()?.message,
        )
        BrowserUtil.browse(url)
        consumer.consume(SubmittedReportInfo(SubmittedReportInfo.SubmissionStatus.NEW_ISSUE))
        return true
    }

    companion object {
        private const val NEW_ISSUE_URL = "https://github.com/Hypercookie/gitea-plugin/issues/new"

        // Browsers and GitHub reject very long URLs, so the stack trace is cut.
        internal const val MAX_STACK_TRACE = 3000

        internal fun buildIssueUrl(
            pluginVersion: String,
            ide: String,
            additionalInfo: String?,
            stackTrace: String?,
            message: String?,
        ): String {
            val trace = stackTrace.orEmpty().let {
                if (it.length > MAX_STACK_TRACE) it.take(MAX_STACK_TRACE) + "\n... (truncated)" else it
            }
            val body = buildString {
                appendLine("**What were you doing?**")
                appendLine(additionalInfo?.takeIf { it.isNotBlank() } ?: "_Please describe._")
                appendLine()
                appendLine("**Environment**")
                appendLine("- Plugin: $pluginVersion")
                appendLine("- IDE: $ide")
                appendLine()
                appendLine("**Exception**")
                appendLine("```")
                appendLine(trace.ifBlank { message.orEmpty() })
                appendLine("```")
            }
            val title = "Exception: " + (message ?: trace.lineSequence().firstOrNull().orEmpty()).take(80)
            return "$NEW_ISSUE_URL?title=${encode(title)}&labels=bug&body=${encode(body)}"
        }

        private fun encode(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
    }
}
