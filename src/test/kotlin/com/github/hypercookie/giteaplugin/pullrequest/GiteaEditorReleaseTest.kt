package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PrBranchInfo
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaNewCommentViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaSubmitReviewPopup
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaEditorInlay
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaReviewEditorComponents
import com.github.hypercookie.giteaplugin.pullrequest.timeline.GiteaTimelineEditorProvider
import com.github.hypercookie.giteaplugin.pullrequest.timeline.GiteaTimelineVirtualFile
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Every editor the plugin creates must be released when the UI that owns it
 * goes away; otherwise the IDE reports "Memory leak detected" for the
 * editor's disposables on shutdown. Runs offline: the API client points at
 * a closed port, so all requests fail fast.
 */
class GiteaEditorReleaseTest : BasePlatformTestCase() {

    private lateinit var dataScope: CoroutineScope

    override fun setUp() {
        super.setUp()
        dataScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            dataScope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testTimelineEditorReleasesCommentEditorOnDispose() {
        val baseline = editors()
        val fileEditor = GiteaTimelineEditorProvider().createEditor(project, GiteaTimelineVirtualFile(data()))
        val created = createdSince(baseline)
        assertEquals("timeline creates its new-comment editor", 1, created.size)

        Disposer.dispose(fileEditor)
        assertEditorsReleased(baseline, created)
    }

    fun testInlayCommentFieldReleasedWithInlayScope() {
        myFixture.configureByText("README.md", "readme")
        val baseline = editors()
        // renderInlays gives each inlay a child scope of the controller's
        // Dispatchers.Main scope and cancels it when the inlay is removed.
        val inlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val data = data()
        val vm = GiteaNewCommentViewModel(inlayScope, data, "README.md", ReviewSide.NEW, 0) {}
        GiteaReviewEditorComponents.create(inlayScope, project, GiteaEditorInlay.NewComment(vm), myFixture.editor)
        val created = createdSince(baseline)
        assertEquals("new-comment inlay creates its editor", 1, created.size)

        inlayScope.cancel()
        assertEditorsReleased(baseline, created)
    }

    fun testSubmitPopupEditorReleasedOnClose() {
        val baseline = editors()
        val popup = GiteaSubmitReviewPopup.createPopup(data())
        val content = popup.content
        val field = UIUtil.findComponentOfType(content, EditorTextField::class.java)
        assertNotNull("popup has a review body field", field)

        // Showing the popup realizes its content, which creates the editor;
        // closing it removes the content again.
        content.addNotify()
        val created = createdSince(baseline)
        assertEquals("shown popup has its body editor", 1, created.size)
        popup.cancel()
        content.removeNotify()
        if (!popup.isDisposed) Disposer.dispose(popup)
        assertEditorsReleased(baseline, created)
    }

    private fun editors(): Set<Editor> = EditorFactory.getInstance().allEditors.toSet()

    private fun createdSince(baseline: Set<Editor>): List<Editor> = editors().filterNot { it in baseline }

    /**
     * Checks both that the editors are unregistered and that they are
     * disposed: EditorFactory unregisters an editor before disposing it, so
     * a release that fails halfway (e.g. off the EDT) still drops it from
     * `allEditors` but leaves its disposables alive.
     */
    private fun assertEditorsReleased(baseline: Set<Editor>, created: List<Editor>) {
        // Editors are released from coroutine finally blocks or invokeLater
        // calls dispatched to the EDT; let them run.
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (created.any { !it.isDisposed } && System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(10)
        }
        assertTrue("all editors disposed", created.all { it.isDisposed })
        assertEquals("no editors left registered", baseline, editors())
    }

    private fun data(): GiteaPullRequestReviewData {
        val account = GiteaAccount(name = "test", serverUrl = SERVER_URL)
        val repo = GiteaRepoContext(account, "owner", "repo", "$SERVER_URL/owner/repo.git")
        val pr = PullRequest(
            id = 1, number = 1, title = "Test", state = "open",
            base = PrBranchInfo(ref = "main", sha = "base"),
            head = PrBranchInfo(ref = "feature", sha = "head"),
        )
        return GiteaPullRequestReviewData(project, dataScope, repo, GiteaApiClient(SERVER_URL, "token"), pr, PendingReviewStore())
    }

    private companion object {
        const val SERVER_URL = "http://127.0.0.1:9"
        const val TIMEOUT_MS = 10_000L
    }
}
