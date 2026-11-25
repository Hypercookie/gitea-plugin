package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.GiteaViewedFilesService
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/** "Viewed" checkbox of a pull request file in the diff toolbar, shared with the changes tree. Local to the IDE. */
class GiteaToggleViewedAction : ToggleAction("Viewed", "Mark this file as viewed (kept in the IDE only, not sent to Gitea)", AllIcons.Actions.Checked) {

    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    private fun context(e: AnActionEvent) =
        e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY)?.takeIf { it.reviewData != null }

    override fun update(e: AnActionEvent) {
        super.update(e)
        val visible = context(e) != null
        e.presentation.isVisible = visible
        e.presentation.isEnabled = visible
    }

    override fun isSelected(e: AnActionEvent): Boolean {
        val ctx = context(e) ?: return false
        val data = ctx.reviewData ?: return false
        return GiteaViewedFilesService.getInstance(data.project).isViewed(GiteaViewedFilesService.keyOf(data), ctx.path)
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        val ctx = context(e) ?: return
        val data = ctx.reviewData ?: return
        GiteaViewedFilesService.getInstance(data.project).setViewed(GiteaViewedFilesService.keyOf(data), listOf(ctx.path), state)
    }
}
