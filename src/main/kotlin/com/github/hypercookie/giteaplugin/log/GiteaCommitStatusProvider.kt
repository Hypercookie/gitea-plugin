package com.github.hypercookie.giteaplugin.log

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.accounts.GiteaProjectDefaultAccountHolder
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.github.hypercookie.giteaplugin.util.GiteaBrowser
import com.intellij.collaboration.ui.icon.CIBuildStatusIcons
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.ui.EmptyIcon
import com.intellij.vcs.log.CommitId
import com.intellij.vcs.log.data.VcsCommitExternalStatus
import com.intellij.vcs.log.data.util.VcsCommitsDataLoader
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusPresentation
import com.intellij.vcs.log.ui.frame.VcsCommitExternalStatusProvider
import com.intellij.vcs.log.ui.table.column.util.VcsLogExternalStatusColumnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.InputEvent
import javax.swing.Icon

/**
 * "Gitea Commit Checks" column in the Git log: the commit statuses Gitea
 * reports for each commit, like the GitHub plugin's column in IntelliJ
 * Ultimate. Offered for log roots whose remote maps to a configured Gitea
 * account; only the rows the platform asks for are loaded, through
 * [GiteaCommitStatusCache]. [GiteaSettings.commitChecksInLog] turns the
 * column and its requests off.
 */
class GiteaCommitStatusProvider : VcsCommitExternalStatusProvider.WithColumn<GiteaCommitStatus>() {

    override val id: String = "Gitea.CommitStatus"

    override val columnName: String = "Gitea Commit Checks"

    override val isColumnEnabledByDefault: Boolean = true

    override fun isColumnAvailable(project: Project, roots: Collection<VirtualFile>): Boolean =
        GiteaSettings.getInstance().commitChecksInLog &&
            GiteaRepoLinkService.getInstance(project).findAllMatches().any { it.gitRoot in roots }

    override fun getExternalStatusColumnService(): VcsLogExternalStatusColumnService<GiteaCommitStatus> =
        service<GiteaCommitStatusService>()

    override fun getStubStatus(): GiteaCommitStatus = GiteaCommitStatus.NotLoaded

    override fun getPresentation(project: Project, status: GiteaCommitStatus): VcsCommitExternalStatusPresentation? =
        (status as? GiteaCommitStatus.Loaded)?.presentation
}

sealed interface GiteaCommitStatus : VcsCommitExternalStatus {
    data object NotLoaded : GiteaCommitStatus

    /** [commitUrl] is the commit's web page, which lists its checks. */
    class Loaded(val checks: GiteaCommitChecks, val commitUrl: String) : GiteaCommitStatus {
        // Asked for on every repaint of the row.
        val presentation: VcsCommitExternalStatusPresentation by lazy { GiteaCommitStatusPresentation(checks, commitUrl) }
    }
}

/**
 * Holds the session cache shared by every log table of every project, so a
 * commit seen in two tabs is fetched once. The platform cancels [scope] and
 * disposes this service (and with it the table loaders) on plugin unload.
 */
@Service(Service.Level.APP)
class GiteaCommitStatusService(override val scope: CoroutineScope) : VcsLogExternalStatusColumnService<GiteaCommitStatus>() {

    private val cache = GiteaCommitStatusCache(scope)

    override fun getDataLoader(project: Project): VcsCommitsDataLoader<GiteaCommitStatus> =
        GiteaCommitStatusLoader(project, cache, scope)
}

/**
 * Loader for one log table (or the commit details panel). The platform calls
 * [loadData] with the visible rows plus 15 on each side, 300 ms after
 * scrolling stops, and again when the log refreshes; each call replaces the
 * previous one.
 */
private class GiteaCommitStatusLoader(
    private val project: Project,
    private val cache: GiteaCommitStatusCache,
    parentScope: CoroutineScope,
) : VcsCommitsDataLoader<GiteaCommitStatus> {

    private val cs = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext.job))
    private var current: Job? = null

    /** A root's Gitea repository and the client to read it with. */
    private class Target(val context: GiteaRepoContext, val serverUrl: String, val client: GiteaApiClient)

    override fun loadData(commits: List<CommitId>, onChange: (Map<CommitId, GiteaCommitStatus>) -> Unit) {
        current?.cancel()
        if (commits.isEmpty() || !GiteaSettings.getInstance().commitChecksInLog) {
            // The column was hidden, or turned off in the settings.
            cache.release(this)
            return
        }
        current = cs.launch { load(commits, onChange) }
    }

    private suspend fun load(commits: List<CommitId>, onChange: (Map<CommitId, GiteaCommitStatus>) -> Unit) {
        val targets = resolveTargets(commits.mapTo(HashSet()) { it.root })
        if (targets.isEmpty()) return
        val commitsByKey = LinkedHashMap<GiteaCommitStatusKey, CommitId>()
        val targetByKey = HashMap<GiteaCommitStatusKey, Target>()
        for (commit in commits) {
            val target = targets[commit.root] ?: continue
            val key = GiteaCommitStatusKey(target.serverUrl, target.context.owner, target.context.repo, commit.hash.asString())
            commitsByKey[key] = commit
            targetByKey[key] = target
        }

        fun loaded(key: GiteaCommitStatusKey, checks: GiteaCommitChecks) =
            commitsByKey.getValue(key) to GiteaCommitStatus.Loaded(checks, commitUrl(targetByKey.getValue(key), key.sha))

        val known = commitsByKey.keys.mapNotNull { key -> cache.cached(key)?.let { loaded(key, it) } }.toMap()
        if (known.isNotEmpty()) deliver(known, onChange)

        val keys = commitsByKey.keys.toList()
        repeat(MAX_REFRESH_ROUNDS) {
            if (!GiteaSettings.getInstance().commitChecksInLog) return
            val next = cache.load(this, keys, { key -> targetByKey.getValue(key).client.getCombinedStatus(key.owner, key.repo, key.sha, STATUS_LIMIT) }) { key, checks ->
                cs.launch { deliver(mapOf(loaded(key, checks)), onChange) }
            } ?: return
            // Running checks, or a pause after server errors: look again once
            // they're due, while these rows are still the ones on screen.
            delay((next - System.currentTimeMillis()).coerceAtLeast(MIN_REFRESH_DELAY_MS))
        }
    }

    private suspend fun deliver(statuses: Map<CommitId, GiteaCommitStatus>, onChange: (Map<CommitId, GiteaCommitStatus>) -> Unit) {
        withContext(Dispatchers.EDT) { onChange(statuses) }
    }

    /**
     * One Gitea repository per log root. A root with several matching remotes
     * or accounts uses the project's default account if it matches one,
     * otherwise the first match.
     */
    private suspend fun resolveTargets(roots: Set<VirtualFile>): Map<VirtualFile, Target> {
        val matches = GiteaRepoLinkService.getInstance(project).findAllMatches().filter { it.gitRoot in roots }
        if (matches.isEmpty()) return emptyMap()
        val defaultAccountId = GiteaProjectDefaultAccountHolder.getInstance(project).account?.id
        val clients = HashMap<GiteaAccount, GiteaApiClient?>()
        val targets = HashMap<VirtualFile, Target>()
        for ((root, candidates) in matches.groupBy { it.gitRoot!! }) {
            val context = candidates.firstOrNull { it.account.id == defaultAccountId } ?: candidates.first()
            val client = clients.getOrPut(context.account) {
                val manager = GiteaAccountManager.getInstance()
                manager.getToken(context.account)?.let { manager.clientFor(context.account) }
            } ?: continue
            targets[root] = Target(context, context.account.serverUrl.trimEnd('/'), client)
        }
        return targets
    }

    private fun commitUrl(target: Target, sha: String): String =
        "${target.serverUrl}/${target.context.owner}/${target.context.repo}/commit/$sha"

    override fun dispose() {
        cs.cancel()
        cache.release(this)
    }

    private companion object {
        /** Gitea's page size cap; enough for every context of a commit. */
        const val STATUS_LIMIT = 50
        /** With the 60 s pending TTL, running checks are followed for 30 minutes
         * without scrolling; after that only scrolling or a log refresh does. */
        const val MAX_REFRESH_ROUNDS = 30
        const val MIN_REFRESH_DELAY_MS = 1_000L
    }
}

/** Icon and tooltip for a loaded commit; clicking opens the check's page. */
private class GiteaCommitStatusPresentation(
    private val checks: GiteaCommitChecks,
    private val commitUrl: String,
) : VcsCommitExternalStatusPresentation.Clickable {

    override val icon: Icon = when (checks.state) {
        GiteaCheckState.SUCCESS -> CIBuildStatusIcons.success
        GiteaCheckState.WARNING -> CIBuildStatusIcons.warning
        GiteaCheckState.SKIPPED -> CIBuildStatusIcons.skipped
        GiteaCheckState.FAILURE -> if (checks.running) CIBuildStatusIcons.failedInProgress else CIBuildStatusIcons.failed
        GiteaCheckState.PENDING -> CIBuildStatusIcons.inProgress
        // Muted, so a repository without CI doesn't fill the column with
        // something that reads like a result.
        GiteaCheckState.NONE -> AllIcons.RunConfigurations.TestNotRan
        // Unpushed commits: nothing to show yet; the tooltip says why.
        GiteaCheckState.NOT_ON_SERVER -> EmptyIcon.ICON_16
    }

    /** A single check's own page (e.g. the Actions run), otherwise the
     * commit page, which lists all checks. */
    private val target: String? = when {
        checks.statuses.isEmpty() -> null
        else -> checks.statuses.singleOrNull()?.targetUrl?.takeIf(GiteaBrowser::isSafe) ?: commitUrl
    }

    override val text: String = HtmlBuilder().apply {
        when (checks.state) {
            GiteaCheckState.NONE -> append("No checks reported for this commit")
            GiteaCheckState.NOT_ON_SERVER -> append("Commit not found on the Gitea server; it may not be pushed yet")
            else -> {
                append(HtmlChunk.text("Checks: ${stateText(checks.state, checks.running)}").bold())
                for (status in checks.statuses) {
                    append(HtmlChunk.br())
                    append(status.context ?: "status").append(": ").append(status.status ?: "unknown")
                    status.description?.takeIf { it.isNotBlank() }?.let { append(" - ").append(it) }
                }
            }
        }
    }.wrapWithHtmlBody().toString()

    override fun clickEnabled(e: InputEvent?): Boolean = target != null

    override fun onClick(e: InputEvent?): Boolean {
        val url = target ?: return false
        return GiteaBrowser.open(url)
    }

    private fun stateText(state: GiteaCheckState, running: Boolean): String = when (state) {
        GiteaCheckState.SUCCESS -> "passed"
        GiteaCheckState.WARNING -> "passed with warnings"
        GiteaCheckState.SKIPPED -> "skipped"
        GiteaCheckState.FAILURE -> if (running) "failed, others still running" else "failed"
        else -> "running"
    }
}
