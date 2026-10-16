package com.github.hypercookie.giteaplugin.pullrequest.timeline

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.PrBranchInfo
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.util.GiteaCodeBlockComponent
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.Component
import java.awt.Container
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/** Timeline content is laid out at the view's width: comments wrap and code
 * blocks scroll instead of being cut off on the right. Runs offline. */
class GiteaTimelineWidthTest : BasePlatformTestCase() {

    private lateinit var cs: CoroutineScope

    override fun setUp() {
        super.setUp()
        cs = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    override fun tearDown() {
        try {
            cs.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testCommentsFitANarrowTimeline() {
        val body = "A long description that has to wrap to fit a narrow tool window. ".repeat(10) +
            "\n\n```\n" + "val x = 1; ".repeat(40) + "\n```"
        val timeline = GiteaTimelineComponent.create(cs, data(body)) as JScrollPane
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        val width = 400
        timeline.setSize(width, 800)
        repeat(3) { layoutAll(timeline) }

        val viewport = timeline.viewport
        assertTrue("content ${viewport.view.width} fits $width", viewport.view.width <= viewport.width)
        val block = UIUtil.findComponentOfType(timeline, GiteaCodeBlockComponent::class.java)!!
        assertTrue("code is wider than the view", block.naturalWidth > width)
        val description = UIUtil.findComponentsOfType(timeline, JEditorPane::class.java).single { it.text.contains("long description") }
        for (c in listOf(block, description)) {
            val right = SwingUtilities.convertPoint(c, c.width, 0, viewport).x
            assertTrue("${c.javaClass.simpleName} ends at $right, inside $width", right <= viewport.width)
        }
    }

    private fun layoutAll(c: Component) {
        if (c is Container) {
            c.doLayout()
            c.components.forEach(::layoutAll)
        }
    }

    private fun data(body: String): GiteaPullRequestReviewData {
        val account = GiteaAccount(name = "test", serverUrl = SERVER_URL)
        val repo = GiteaRepoContext(account, "owner", "repo", "$SERVER_URL/owner/repo.git")
        val pr = PullRequest(
            id = 1, number = 1, title = "Test", state = "open", body = body,
            base = PrBranchInfo(ref = "main", sha = "base"),
            head = PrBranchInfo(ref = "feature", sha = "head"),
        )
        return GiteaPullRequestReviewData(project, cs, repo, GiteaApiClient(SERVER_URL, "token"), pr, PendingReviewStore())
    }

    private companion object {
        const val SERVER_URL = "http://127.0.0.1:9"
    }
}
