package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PendingReviewStoreServiceTest : BasePlatformTestCase() {

    private fun repo(server: String) = GiteaRepoContext(GiteaAccount(name = "t", serverUrl = server), "o", "r", "$server/o/r.git")

    fun testDraftsSurviveSaveAndLoad() {
        val comment = CreatePullReviewComment(body = "nit", path = "a.kt", newPosition = 3)
        val first = PendingReviewStoreService(project)
        first.storeFor(repo("https://a.example"), 1).addComment(comment)

        val second = PendingReviewStoreService(project)
        second.loadState(first.state)
        assertEquals(listOf(comment), second.storeFor(repo("https://a.example"), 1).pendingComments)
    }

    fun testDraftsAreKeyedByServer() {
        val service = PendingReviewStoreService(project)
        service.storeFor(repo("https://a.example"), 1).addComment(CreatePullReviewComment(body = "x", path = "a.kt", newPosition = 1))
        assertTrue(service.storeFor(repo("https://b.example"), 1).pendingComments.isEmpty())
        assertTrue(service.storeFor(repo("https://a.example"), 2).pendingComments.isEmpty())
    }

    fun testEmptyStoresAreNotSaved() {
        val service = PendingReviewStoreService(project)
        service.storeFor(repo("https://a.example"), 1)
        assertTrue(service.state.drafts.isEmpty())
    }
}
