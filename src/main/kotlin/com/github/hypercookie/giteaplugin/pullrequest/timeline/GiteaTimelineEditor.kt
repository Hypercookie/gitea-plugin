package com.github.hypercookie.giteaplugin.pullrequest.timeline

import com.github.hypercookie.giteaplugin.icons.GiteaIcons
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.github.hypercookie.giteaplugin.util.giteaChildScope
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import kotlinx.coroutines.cancel
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent

/**
 * Pull request timeline as an editor tab, like the GitHub plugin's
 * "Pull Request Timeline" editor. GitHub backs it with its own virtual file
 * system (`ghpr://`) so tabs survive an IDE restart; this uses a
 * [LightVirtualFile], so timeline tabs are not restored after a restart
 * (migration ledger I15).
 */
object GiteaTimelineEditor {
    private val files = ConcurrentHashMap<String, GiteaTimelineVirtualFile>()

    fun open(data: GiteaPullRequestReviewData) {
        val key = "${data.repo.account.serverUrl}/${data.owner}/${data.repoName}#${data.number}"
        val file = files.compute(key) { _, existing -> existing?.takeIf { it.data.cs.isActiveScope() } ?: GiteaTimelineVirtualFile(data) }!!
        FileEditorManager.getInstance(data.project).openFile(file, true)
    }

    private fun kotlinx.coroutines.CoroutineScope.isActiveScope() = coroutineContext[kotlinx.coroutines.Job]?.isActive != false
}

class GiteaTimelineVirtualFile(val data: GiteaPullRequestReviewData) :
    LightVirtualFile("#${data.number} Timeline", TimelineFileType, "") {
    init {
        isWritable = false
    }
}

private object TimelineFileType : FileType {
    override fun getName() = "GiteaPullRequestTimeline"
    override fun getDescription() = "Gitea pull request timeline"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = GiteaIcons.Gitea
    override fun isBinary() = true
    override fun isReadOnly() = true
}

class GiteaTimelineEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is GiteaTimelineVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = GiteaTimelineFileEditor(file as GiteaTimelineVirtualFile)

    override fun getEditorTypeId() = "GiteaPullRequestTimeline"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class GiteaTimelineFileEditor(private val file: GiteaTimelineVirtualFile) : UserDataHolderBase(), FileEditor {
    private val cs = GiteaProjectCoroutineScope.getInstance(file.data.project).coroutineScope.giteaChildScope("Gitea timeline")
    private val component = GiteaTimelineComponent.create(cs, file.data)

    override fun getComponent(): JComponent = component
    override fun getPreferredFocusedComponent(): JComponent? = null
    override fun getName() = "Timeline"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun dispose() {
        cs.cancel()
    }
}
