package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import com.intellij.util.xmlb.annotations.XMap
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * One [PendingReviewStore] per PR (server, owner/repo and number),
 * project-scoped, so the editor, the diff viewers and the pull request tab
 * share the same queue of not-yet-submitted comments for a given PR.
 *
 * Drafts are saved in the project's workspace file (not shared through
 * version control), so they survive closing the project or the IDE.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaPendingReviews", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PendingReviewStoreService(private val project: Project) : PersistentStateComponent<PendingReviewStoreService.PendingState> {

    class PendingState {
        /** Store key to the JSON list of its pending comments. */
        @get:XMap
        var drafts: MutableMap<String, String> = mutableMapOf()
    }

    private val stores = ConcurrentHashMap<String, PendingReviewStore>()

    /** Drafts loaded from disk whose store hasn't been asked for yet. */
    private val saved = ConcurrentHashMap<String, List<CreatePullReviewComment>>()

    fun storeFor(repo: GiteaRepoContext, prNumber: Long): PendingReviewStore =
        storeFor(key(repo, prNumber))

    internal fun storeFor(key: String): PendingReviewStore =
        stores.computeIfAbsent(key) {
            PendingReviewStore(saved.remove(key).orEmpty()).also { store ->
                // Drives PrReviewEditorNotificationProvider's top bar —
                // appears/disappears live as comments are queued/submitted,
                // no need to reopen the file.
                store.addListener { EditorNotifications.getInstance(project).updateAllNotifications() }
            }
        }

    override fun getState(): PendingState = PendingState().apply {
        saved.forEach { (key, comments) -> if (comments.isNotEmpty()) drafts[key] = json.encodeToString(serializer, comments) }
        stores.forEach { (key, store) ->
            val comments = store.pendingComments
            if (comments.isEmpty()) drafts.remove(key) else drafts[key] = json.encodeToString(serializer, comments)
        }
    }

    override fun loadState(state: PendingState) {
        saved.clear()
        state.drafts.forEach { (key, text) ->
            runCatching { json.decodeFromString(serializer, text) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { saved[key] = it }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(CreatePullReviewComment.serializer())

        /** Server, repository and number, so two servers' `owner/repo#1`
         * never share drafts. */
        internal fun key(repo: GiteaRepoContext, prNumber: Long): String =
            "${repo.account.serverUrl.trimEnd('/')}/${repo.owner}/${repo.repo}#$prNumber"

        @JvmStatic
        fun getInstance(project: Project): PendingReviewStoreService =
            project.getService(PendingReviewStoreService::class.java)
    }
}
