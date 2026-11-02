package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.intellij.diff.editor.ChainDiffVirtualFile
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Names the editor tab of a pull request diff after the file it shows, and
 * keeps the name current while the user steps through the changes, instead
 * of the tab keeping the title of the first file.
 */
class GiteaDiffTabTitleProvider : EditorTabTitleProvider {

    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? {
        if (file !is ChainDiffVirtualFile) return null
        val path = file.chain.getUserData(GiteaDiffCommentNavigation.KEY)?.currentPath ?: return null
        return path.substringAfterLast('/')
    }

    companion object {
        /** Records [path] as the file [navigation]'s chain shows and refreshes its tab. */
        fun show(project: Project, navigation: GiteaDiffCommentNavigation, path: String) {
            if (navigation.currentPath == path) return
            navigation.currentPath = path
            val manager = FileEditorManager.getInstance(project) as? FileEditorManagerEx ?: return
            manager.openFiles
                .filter { it is ChainDiffVirtualFile && it.chain.getUserData(GiteaDiffCommentNavigation.KEY) === navigation }
                .forEach(manager::updateFilePresentation)
        }
    }
}
