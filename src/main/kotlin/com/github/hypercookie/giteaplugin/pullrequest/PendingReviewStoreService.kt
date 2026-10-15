package com.github.hypercookie.giteaplugin.pullrequest

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorNotifications
import java.util.concurrent.ConcurrentHashMap

/**
 * One [PendingReviewStore] per PR (owner/repo/number), project-scoped,
 * so the editor, the diff viewers and the pull request tab share the same
 * queue of not-yet-submitted comments for a given PR.
 */
@Service(Service.Level.PROJECT)
class PendingReviewStoreService(private val project: Project) {

    private val stores = ConcurrentHashMap<String, PendingReviewStore>()

    fun storeFor(owner: String, repo: String, prNumber: Long): PendingReviewStore =
        stores.getOrPut("$owner/$repo#$prNumber") {
            PendingReviewStore().also { store ->
                // Drives PrReviewEditorNotificationProvider's top bar —
                // appears/disappears live as comments are queued/submitted,
                // no need to reopen the file.
                store.addListener { EditorNotifications.getInstance(project).updateAllNotifications() }
            }
        }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PendingReviewStoreService =
            project.getService(PendingReviewStoreService::class.java)
    }
}
