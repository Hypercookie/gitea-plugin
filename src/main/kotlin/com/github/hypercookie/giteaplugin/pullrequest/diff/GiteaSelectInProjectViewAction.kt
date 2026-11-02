package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.VirtualFile

/**
 * "Select in Project View" for the file of a pull request diff, like
 * "Select Opened File" in a regular editor: the diff tab's own virtual file
 * isn't a project file, so the platform's action has nothing to select.
 * Selects the file in the local repository, while it exists there. Shown in
 * the diff toolbar and context menu.
 */
class GiteaSelectInProjectViewAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun localFile(e: AnActionEvent): VirtualFile? {
        val ctx = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return null
        val root = ctx.repo.gitRoot ?: return null
        // A deleted file has only its base path.
        return listOf(ctx.path, ctx.basePath).firstNotNullOfOrNull { root.findFileByRelativePath(it) }
    }

    override fun update(e: AnActionEvent) {
        val isGiteaDiff = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) != null
        e.presentation.isVisible = isGiteaDiff && e.project != null
        e.presentation.isEnabled = isGiteaDiff && localFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = localFile(e) ?: return
        ProjectView.getInstance(project).select(null, file, true)
    }
}
