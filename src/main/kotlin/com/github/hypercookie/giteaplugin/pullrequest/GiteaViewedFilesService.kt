package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.annotations.XMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Which files of a pull request the user has marked as viewed, like GitHub's
 * "Viewed" checkbox. Kept per PR (server, owner/repo and number) in the
 * project's workspace file, so it survives restarts but is neither shared
 * through version control nor synced to Gitea, which has no such API.
 */
@Service(Service.Level.PROJECT)
@State(name = "GiteaViewedFiles", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class GiteaViewedFilesService : PersistentStateComponent<GiteaViewedFilesService.ViewedState> {

    class ViewedState {
        /** PR key to the JSON list of its viewed repo-relative paths. */
        @get:XMap
        var viewed: MutableMap<String, String> = mutableMapOf()
    }

    private val all = MutableStateFlow<Map<String, Set<String>>>(emptyMap())

    fun viewedPaths(key: String): Flow<Set<String>> = all.map { it[key].orEmpty() }.distinctUntilChanged()

    fun isViewed(key: String, path: String): Boolean = path in all.value[key].orEmpty()

    /** Marks [paths] as viewed or not viewed. */
    fun setViewed(key: String, paths: Collection<String>, viewed: Boolean) {
        all.update { current ->
            val updated = if (viewed) current[key].orEmpty() + paths else current[key].orEmpty() - paths.toSet()
            if (updated.isEmpty()) current - key else current + (key to updated)
        }
    }

    override fun getState(): ViewedState = ViewedState().apply {
        all.value.forEach { (key, paths) -> if (paths.isNotEmpty()) viewed[key] = json.encodeToString(serializer, paths.sorted()) }
    }

    override fun loadState(state: ViewedState) {
        all.value = state.viewed.mapNotNull { (key, text) ->
            runCatching { json.decodeFromString(serializer, text) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { key to it.toSet() }
        }.toMap()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(String.serializer())

        /** Same key as the pending review drafts: two servers' `owner/repo#1` never share state. */
        fun keyOf(data: GiteaPullRequestReviewData): String =
            PendingReviewStoreService.key(data.repo, data.pullRequest.value.number)

        @JvmStatic
        fun getInstance(project: Project): GiteaViewedFilesService =
            project.getService(GiteaViewedFilesService::class.java)
    }
}
