package com.github.hypercookie.giteaplugin.pullrequest

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * One [PendingReviewStore] per PR (owner/repo/number), project-scoped —
 * promoted from "one per opened detail tab" so working-copy editors (which
 * have no detail tab) and the detail tab itself share the same queue of
 * not-yet-submitted comments for a given PR. See
 * docs/plan/pr-review-feature-plan.md, "Architecture pivot," Phase 15.
 */
@Service(Service.Level.PROJECT)
class PendingReviewStoreService {

    private val stores = ConcurrentHashMap<String, PendingReviewStore>()

    fun storeFor(owner: String, repo: String, prNumber: Long): PendingReviewStore =
        stores.getOrPut("$owner/$repo#$prNumber") { PendingReviewStore() }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): PendingReviewStoreService =
            project.getService(PendingReviewStoreService::class.java)
    }
}
