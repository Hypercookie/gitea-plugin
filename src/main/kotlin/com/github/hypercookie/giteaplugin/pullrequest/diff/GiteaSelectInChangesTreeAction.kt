package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager

/**
 * "Select in Changes Tree" for a pull request diff, like "Select Opened
 * File" for a regular editor: selects the file being shown in the pull
 * request's own changes tree and shows that tree. Shown in the diff toolbar
 * and context menu. Unavailable when the diff was opened without the tree,
 * e.g. from a review comment.
 */
class GiteaSelectInChangesTreeAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun target(e: AnActionEvent) =
        GiteaDiffCommentNavigation.of(e.getData(DiffDataKeys.DIFF_CONTEXT))?.selectInTree

    override fun update(e: AnActionEvent) {
        val isGiteaDiff = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) != null
        e.presentation.isVisible = isGiteaDiff
        e.presentation.isEnabled = isGiteaDiff && target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val request = e.getData(DiffDataKeys.DIFF_REQUEST) ?: return
        val select = target(e) ?: return
        select(request)
        e.project?.let { ToolWindowManager.getInstance(it).getToolWindow("Gitea Pull Requests")?.activate(null) }
    }
}
