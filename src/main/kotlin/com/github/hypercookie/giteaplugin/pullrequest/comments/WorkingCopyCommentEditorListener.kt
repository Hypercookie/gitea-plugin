package com.github.hypercookie.giteaplugin.pullrequest.comments

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrResolverService
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStoreService
import com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaDiffCommentContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows PR review-comment gutter icons directly in the regular source
 * editor — not only in the explicit diff-viewer flow
 * ([com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaCommentDiffExtension]) —
 * whenever the currently checked-out branch has a matching open PR. Fires
 * automatically on every file open (no explicit "start review" step) per
 * the "Architecture pivot" decision in
 * docs/plan/pr-review-feature-plan.md.
 *
 * Registered via `<projectListeners>` (constructed per-project, one
 * instance, no manual `MessageBusConnection` bookkeeping needed).
 *
 * Line-drift caveat: the local file may differ from `pr.head.sha` (dirty
 * edits, local commits ahead) — v1 does not compute a head-blob↔working-copy
 * line mapping, it just anchors at the position Gitea reports and accepts
 * that comments can point at the wrong line when the two diverge. See the
 * plan doc for the (deliberate) reasoning.
 */
class WorkingCopyCommentEditorListener(private val project: Project) : FileEditorManagerListener {

    private val installed = mutableMapOf<Editor, CommentGutterController>()

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        val textEditor = source.getSelectedEditor(file) as? TextEditor ?: return
        val editor = textEditor.editor as? EditorEx ?: return
        installed.keys.removeAll { it.isDisposed }
        if (installed.containsKey(editor)) return

        // `fileOpened` fires from a write-intent read action on the EDT —
        // PasswordSafe access (inside getToken) is a "slow operation" the
        // platform explicitly disallows there (confirmed via a SEVERE
        // SlowOperations log during manual testing). Do every bit of
        // resolution, not just the network calls, inside the coroutine on
        // a background dispatcher.
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch(Dispatchers.IO) {
            val repoContext = GiteaRepoLinkService.getInstance(project).resolveDefault() ?: return@launch
            val token = GiteaAccountManager.getInstance().getToken(repoContext.account)
            if (token.isNullOrBlank()) return@launch
            val repoRoot = GitRepositoryManager.getInstance(project).repositories.firstOrNull()?.root ?: return@launch
            val relativePath = VfsUtilCore.getRelativePath(file, repoRoot) ?: return@launch
            val client = GiteaApiClient(repoContext.account.serverUrl, token)

            val pr = runCatching {
                GiteaBranchPrResolverService.getInstance(project).resolveForCurrentBranch(repoContext, client)
            }.getOrNull() ?: return@launch

            val comments = runCatching {
                client.getAllReviewComments(repoContext.owner, repoContext.repo, pr.number)
                    .filter { it.path == relativePath }
            }.getOrDefault(emptyList())

            withContext(Dispatchers.Main) {
                if (editor.isDisposed) return@withContext

                val store = PendingReviewStoreService.getInstance(project)
                    .storeFor(repoContext.owner, repoContext.repo, pr.number)
                val commentContext = GiteaDiffCommentContext(
                    repoContext.owner, repoContext.repo, pr.number, client, relativePath, comments, store,
                )

                // The working copy is the head side of the PR (we're on its
                // branch), so comments/new comments always use the
                // head-side `position` field, never `originalPosition`.
                val controller = CommentGutterController(project, editor, commentContext, useOriginalPosition = false)
                controller.updateComments(comments)
                installed[editor] = controller

                store.addListener {
                    GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
                        val fresh = runCatching { commentContext.fetchFreshComments() }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (editor.isDisposed) return@withContext
                            if (fresh != null) controller.updateComments(fresh) else controller.render()
                        }
                    }
                }
            }
        }
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        installed.keys.removeAll { it.isDisposed }
    }
}
