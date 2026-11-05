package com.github.hypercookie.giteaplugin.pullrequest.details

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewThread
import com.intellij.collaboration.util.RefComparisonChange
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Filters of the changes tree in the pull request overview. All active ones must match (AND). */
enum class GiteaFileFilter(val title: String) {
    CHANGED_LOCALLY("Changed locally"),
    UNRESOLVED_COMMENTS("Has unresolved comments"),
    PENDING_COMMENTS("Has pending comments"),
    MY_COMMENTS("Has my comments"),
    ANY_COMMENTS("Has comments"),
    NO_COMMENTS("Has no comments"),
    ADDED("Added"),
    MODIFIED("Modified"),
    DELETED("Deleted"),
    RENAMED("Renamed"),
}

/** Repo-relative paths per property, the input of [GiteaChangeFilters.apply]. */
data class GiteaFilterContext(
    val localPaths: Set<String> = emptySet(),
    val unresolvedPaths: Set<String> = emptySet(),
    val pendingPaths: Set<String> = emptySet(),
    val myCommentPaths: Set<String> = emptySet(),
    val commentedPaths: Set<String> = emptySet(),
)

object GiteaChangeFilters {

    /** Repo-relative path of the change: the new one, the old one for a deleted file. */
    fun pathOf(data: GiteaPullRequestReviewData, change: RefComparisonChange): String? =
        (change.filePathAfter ?: change.filePathBefore)?.let { GiteaChangePaths.relative(data, it) }

    fun extensionOf(path: String): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    }

    fun context(
        data: GiteaPullRequestReviewData,
        threads: List<ReviewThread>,
        pending: List<CreatePullReviewComment>,
        me: GiteaUser?,
        local: Set<String>,
    ): GiteaFilterContext = GiteaFilterContext(
        localPaths = local,
        unresolvedPaths = threads.filter { !it.isResolved }.mapTo(HashSet()) { it.path },
        pendingPaths = pending.mapTo(HashSet()) { it.path },
        myCommentPaths = if (me == null) emptySet() else threads.filter { t -> t.comments.any { it.user?.id == me.id } }.mapTo(HashSet()) { it.path },
        commentedPaths = threads.mapTo(HashSet()) { it.path },
    )

    /** [pathOf] gives a change's repo-relative path (null: unknown, never matches a path filter). */
    fun apply(
        changes: List<RefComparisonChange>,
        pathOf: (RefComparisonChange) -> String?,
        filters: Set<GiteaFileFilter>,
        extensions: Set<String>,
        context: GiteaFilterContext,
    ): List<RefComparisonChange> {
        if (filters.isEmpty() && extensions.isEmpty()) return changes
        return changes.filter { change ->
            val path = pathOf(change)
            val before = change.filePathBefore?.path
            val after = change.filePathAfter?.path
            val commented = path in context.commentedPaths
            (extensions.isEmpty() || extensionOf(path.orEmpty()) in extensions) && filters.all { filter ->
                when (filter) {
                    GiteaFileFilter.CHANGED_LOCALLY -> path in context.localPaths
                    GiteaFileFilter.UNRESOLVED_COMMENTS -> path in context.unresolvedPaths
                    GiteaFileFilter.PENDING_COMMENTS -> path in context.pendingPaths
                    GiteaFileFilter.MY_COMMENTS -> path in context.myCommentPaths
                    GiteaFileFilter.ANY_COMMENTS -> commented
                    GiteaFileFilter.NO_COMMENTS -> !commented && path !in context.pendingPaths
                    GiteaFileFilter.ADDED -> before == null
                    GiteaFileFilter.DELETED -> after == null
                    GiteaFileFilter.RENAMED -> before != null && after != null && before != after
                    GiteaFileFilter.MODIFIED -> before != null && after != null && before == after
                }
            }
        }
    }

    /** Repo-relative paths of the files with local changes; emits again on every change list update. */
    fun localChanges(data: GiteaPullRequestReviewData): Flow<Set<String>> = callbackFlow {
        val manager = ChangeListManager.getInstance(data.project)
        fun current(): Set<String> = manager.allChanges.mapNotNullTo(HashSet()) { change ->
            (change.afterRevision?.file ?: change.beforeRevision?.file)?.let { GiteaChangePaths.relative(data, it) }
        }
        trySend(current())
        data.project.messageBus.connect(this).subscribe(
            ChangeListListener.TOPIC,
            object : ChangeListListener {
                override fun changeListUpdateDone() {
                    trySend(current())
                }
            },
        )
        awaitClose()
    }.distinctUntilChanged()
}
