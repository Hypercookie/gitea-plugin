package com.github.hypercookie.giteaplugin.integration

import com.github.hypercookie.giteaplugin.accounts.GiteaAccount
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStore
import com.github.hypercookie.giteaplugin.pullrequest.details.GiteaPullRequestChangesViewModel
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaListSearchHistoryModel
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaListSearchValue
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaPullRequestListViewModel
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaSearchPanelViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaEditorInlay
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaReviewEditorModel
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Runs the migrated view models against the local Gitea dev instance
 * (`plugindev/pr-test`, see docs/local-gitea-dev-instance.md). Skipped
 * unless `GITEA_DEV_TOKEN` is set:
 *
 *     GITEA_DEV_TOKEN=<token> ./gradlew test --tests '*GiteaLiveIntegrationTest*'
 */
class GiteaLiveIntegrationTest : BasePlatformTestCase() {

    private val token: String? = System.getenv("GITEA_DEV_TOKEN")
    private val serverUrl = System.getenv("GITEA_DEV_URL") ?: "http://localhost:3000"
    private lateinit var scope: CoroutineScope

    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun tearDown() {
        try {
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    private fun client() = GiteaApiClient(serverUrl, token!!)

    private fun repo() = GiteaRepoContext(GiteaAccount(name = "plugindev", serverUrl = serverUrl), OWNER, REPO, "$serverUrl/$OWNER/$REPO.git")

    private suspend fun data(number: Long): GiteaPullRequestReviewData {
        val pr = client().getPullRequest(OWNER, REPO, number)
        return GiteaPullRequestReviewData(project, scope, repo(), client(), pr, PendingReviewStore())
    }

    fun testEditorModelPlacesThreadsAndFollowsLocalEdits() {
        if (token == null) return println("GITEA_DEV_TOKEN not set, skipping")
        runBlocking {
            val data = data(1)
            withTimeout(TIMEOUT) { data.threads.first { it.isNotEmpty() } }
            val readmeThreads = data.threads.value.filter { it.path == "README.md" && it.side == ReviewSide.NEW }
            assertTrue("fixture has README threads", readmeThreads.isNotEmpty())
            val anchorCommits = readmeThreads.mapNotNull { it.commitId }.toSet()
            println("README threads: ${readmeThreads.size}, anchor commits: ${anchorCommits.size}")

            val head = data.fileContent("README.md", data.pullRequest.value.head.sha)!!
            val document = ApplicationManager.getApplication().runReadAction<com.intellij.openapi.editor.Document> {
                EditorFactory.getInstance().createDocument(head)
            }
            val model = GiteaReviewEditorModel(scope, data, "README.md", "README.md", ReviewSide.NEW, document, showChanges = true)

            val controls = withTimeout(TIMEOUT) { model.gutterControlsState.first { it != null } }!!
            val threadInlays = withTimeout(TIMEOUT) {
                model.inlays.first { inlays -> inlays.count { it is GiteaEditorInlay.Thread } == readmeThreads.size }
            }.filterIsInstance<GiteaEditorInlay.Thread>()
            val placed = threadInlays.filter { it.line.value != null }
            val outdated = threadInlays.size - placed.size
            println("placed: ${placed.size}, outdated (hidden): $outdated, lines: ${controls.linesWithComments.sorted()}")
            assertTrue("some threads placed", placed.isNotEmpty())
            // Threads anchored at the head commit map 1:1 onto the head content.
            readmeThreads.filter { it.commitId == data.pullRequest.value.head.sha }.forEach { thread ->
                val inlay = threadInlays.single { it.vm.id == thread.id }
                assertEquals(thread.line, inlay.line.value)
            }
            assertTrue("PR changes are marked", model.reviewRanges.value.isNotEmpty())

            // Insert two lines at the top: placed threads shift by 2, the new
            // lines can't take comments.
            val before = placed.associate { it.vm.id to it.line.value!! }
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "local 1\nlocal 2\n") }
            }
            val shifted = withTimeout(TIMEOUT) {
                model.gutterControlsState.first { state -> state != null && !state.isLineCommentable(0) }
            }!!
            assertFalse(shifted.isLineCommentable(1))
            assertTrue(shifted.isLineCommentable(2))
            withTimeout(TIMEOUT) {
                while (placed.any { it.line.value != before[it.vm.id]!! + 2 }) kotlinx.coroutines.delay(50)
            }
        }
    }

    fun testListViewModelFiltersAndPages() {
        if (token == null) return println("GITEA_DEV_TOKEN not set, skipping")
        runBlocking {
            val user = client().getCurrentUser()
            val searchVm = GiteaSearchPanelViewModel(scope, repo(), client(), GiteaListSearchHistoryModel(), user)
            val listVm = GiteaPullRequestListViewModel(scope, repo(), client(), searchVm)

            suspend fun loadWith(search: GiteaListSearchValue): List<Long> {
                searchVm.searchState.value = search
                kotlinx.coroutines.delay(200)
                withTimeout(TIMEOUT) { listVm.isLoading.first { !it } }
                assertNull(listVm.error.value)
                return listVm.items.value.map { it.number }
            }

            val all = loadWith(GiteaListSearchValue())
            assertTrue("all PRs listed", all.isNotEmpty())
            val open = loadWith(GiteaListSearchValue(state = GiteaListSearchValue.State.OPEN))
            assertTrue(open.all { it in all })
            val byAuthor = loadWith(GiteaListSearchValue(author = user.login))
            assertTrue(byAuthor.isNotEmpty())
            val sorted = loadWith(GiteaListSearchValue(sort = GiteaListSearchValue.Sort.OLDEST))
            assertEquals("same set via /pulls", all.toSet(), sorted.toSet())
            val merged = loadWith(GiteaListSearchValue(state = GiteaListSearchValue.State.MERGED))
            assertTrue(listVm.items.value.all { it.merged })
            println("all=$all open=$open author=$byAuthor merged=$merged")
        }
    }

    fun testChangesViewModelLoadsAllAndSingleCommit() {
        if (token == null) return println("GITEA_DEV_TOKEN not set, skipping")
        runBlocking {
            val data = data(1)
            val changesVm = GiteaPullRequestChangesViewModel(scope, data)
            val all = withTimeout(TIMEOUT) { changesVm.changeListVm.first { it != null } }!!.getOrThrow()
            assertTrue(all.changes.isNotEmpty())
            val commits = withTimeout(TIMEOUT) { changesVm.reviewCommits.first { it.isNotEmpty() } }
            changesVm.selectCommit(0)
            val single = withTimeout(TIMEOUT) {
                changesVm.changeListVm.first { it != null && it.getOrNull() !== all }
            }!!.getOrThrow()
            assertTrue(single.changes.isNotEmpty())
            assertEquals(commits[0].sha, single.changes.first().revisionNumberAfter.asString())
        }
    }

    private companion object {
        const val OWNER = "plugindev"
        const val REPO = "pr-test"
        const val TIMEOUT = 30_000L
    }
}
