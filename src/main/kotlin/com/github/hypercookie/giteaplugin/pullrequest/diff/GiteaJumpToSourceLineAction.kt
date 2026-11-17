package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.GiteaBranchPrResolverService
import com.github.hypercookie.giteaplugin.pullrequest.list.GiteaPullRequestActions
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.simple.SimpleDiffViewer
import com.intellij.diff.tools.simple.SimpleOnesideDiffViewer
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.diff.util.Side
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Jump to Source Line" in the pull request diff's editor context menu:
 * opens the local file at the line under the caret. A line of the base
 * side goes to where that line is in the pull request's version, as the
 * diff aligns them.
 *
 * Without the pull request branch checked out, the action checks it out
 * first ("Check Out and Jump to Source Line"), the same way as the branch
 * chip of the pull request details does.
 */
class GiteaJumpToSourceLineAction : DumbAwareAction() {
    init {
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val ctx = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY)
        val project = e.project
        e.presentation.isVisible = ctx != null && project != null
        if (ctx == null || project == null) return
        e.presentation.isEnabled = headLine(e) != null
        e.presentation.text = if (GiteaBranchPrResolverService.getInstance(project).isCheckedOut(ctx.repo, ctx.pullRequest)) {
            "Jump to Source Line"
        } else {
            "Check Out and Jump to Source Line"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val ctx = e.getData(DiffDataKeys.DIFF_REQUEST)?.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        val line = headLine(e) ?: return
        val resolver = GiteaBranchPrResolverService.getInstance(project)
        if (resolver.isCheckedOut(ctx.repo, ctx.pullRequest)) {
            open(project, ctx, line)
            return
        }
        val repository = resolver.repositoryFor(ctx.repo) ?: return
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            GiteaPullRequestActions.fetchAndCheckout(project, repository, ctx.repo, ctx.pullRequest) {
                open(project, ctx, line)
            }
        }
    }

    private fun open(project: Project, ctx: GiteaDiffCommentContext, line: Int) {
        val root = GiteaBranchPrResolverService.getInstance(project).repositoryFor(ctx.repo)?.root ?: return
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            // Just checked out, the file may not be in the VFS yet.
            val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByPath("${root.path}/${ctx.path}") }
            withContext(Dispatchers.EDT) {
                if (file == null || file.isDirectory) {
                    Messages.showInfoMessage(project, "${ctx.path} isn't in the local repository.", "Jump to Source Line")
                    return@withContext
                }
                OpenFileDescriptor(project, file, line, 0).navigate(true)
            }
        }
    }

    /** Line of the pull request's version of the file at the caret, or
     * null where there is none (a deleted file). */
    private fun headLine(e: AnActionEvent): Int? =
        when (val viewer = e.getData(DiffDataKeys.DIFF_VIEWER)) {
            is SimpleDiffViewer -> {
                val side = viewer.currentSide
                val line = viewer.getEditor(side).caretModel.logicalPosition.line
                if (side == Side.RIGHT) {
                    line
                } else {
                    DiffLines.transfer(viewer.diffChanges.map { DiffLines.Change(it.getStartLine(Side.LEFT), it.getEndLine(Side.LEFT), it.getStartLine(Side.RIGHT), it.getEndLine(Side.RIGHT)) }, line)
                }
            }
            is SimpleOnesideDiffViewer -> viewer.editor.caretModel.logicalPosition.line.takeIf { viewer.side == Side.RIGHT }
            is UnifiedDiffViewer -> {
                val editorLine = viewer.editor.caretModel.logicalPosition.line
                val lineCount = viewer.editor.document.lineCount
                // A removed line shows no head line; the next one that does
                // is where the removal is, else the last before it.
                (editorLine until lineCount).asSequence().map { viewer.transferLineFromOnesideStrict(Side.RIGHT, it) }.firstOrNull { it >= 0 }
                    ?: (editorLine downTo 0).asSequence().map { viewer.transferLineFromOnesideStrict(Side.RIGHT, it) }.firstOrNull { it >= 0 }
            }
            else -> null
        }
}

/** Line transfer between the two sides of a diff, for lines of the base side. */
internal object DiffLines {

    /** A changed block: lines `[leftStart, leftEnd)` replaced by `[rightStart, rightEnd)`. */
    data class Change(val leftStart: Int, val leftEnd: Int, val rightStart: Int, val rightEnd: Int)

    /**
     * Right line of left [line] given the diff's [changes] in order: an
     * unchanged line moves by the changes before it; a changed or removed
     * line goes to the start of what replaced it.
     */
    fun transfer(changes: List<Change>, line: Int): Int {
        var shift = 0
        for (change in changes) {
            if (line < change.leftStart) break
            if (line < change.leftEnd) return change.rightStart
            shift = change.rightEnd - change.leftEnd
        }
        return line + shift
    }
}
