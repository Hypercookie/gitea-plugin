package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.pullrequest.diff.GITEA_DIFF_COMMENT_CONTEXT_KEY
import com.github.hypercookie.giteaplugin.pullrequest.diff.GiteaDiffCommentContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListCellRenderer

/**
 * One opened PR's detail tab: overview (title/branches/description) +
 * changed-files list. Selecting a file opens it in the Platform's own diff
 * viewer (`DiffManager.showDiff`) rather than a custom view — see
 * docs/plan/pr-review-feature-plan.md Phase 5. Comments/review UI land on
 * top of that same diff viewer in Phase 6+ via `com.intellij.diff.DiffExtension`.
 */
class PullRequestDetailPanel(
    private val project: Project,
    private val context: GiteaRepoContext,
    private val client: GiteaApiClient,
    private val pr: PullRequest,
) {

    private val filesModel = DefaultListModel<ChangedFile>()
    private val filesList = JBList(filesModel).apply {
        cellRenderer = ChangedFileCellRenderer()
    }
    private val statusLabel = JBLabel("Loading changed files…")

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(buildOverviewPanel(), BorderLayout.NORTH)

        val filesPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JBScrollPane(filesList), BorderLayout.CENTER)
        }
        add(filesPanel, BorderLayout.CENTER)
    }

    init {
        filesList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    filesList.selectedValue?.let { openDiff(it) }
                }
            }
        })
        loadFiles()
    }

    private fun buildOverviewPanel(): Component {
        val title = JBLabel("#${pr.number}  ${pr.title}").apply {
            font = font.deriveFont(font.size2D + 2f)
        }
        val branches = JBLabel("${pr.head.ref} → ${pr.base.ref}  (${pr.state}${if (pr.draft) ", draft" else ""})")
        val description = JBTextArea(pr.body?.takeIf { it.isNotBlank() } ?: "(no description)").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            preferredSize = Dimension(600, 100)
        }

        val panel = JBPanel<JBPanel<*>>()
        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        panel.add(title)
        panel.add(branches)
        panel.add(JBScrollPane(description))
        return panel
    }

    private fun loadFiles() {
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching { client.getPullRequestFiles(context.owner, context.repo, pr.number) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { files ->
                        filesModel.clear()
                        files.forEach(filesModel::addElement)
                        statusLabel.text = "${files.size} changed file(s) — double-click to view diff"
                    },
                    onFailure = { error ->
                        statusLabel.text = "Failed to load changed files: ${error.message}"
                    },
                )
            }
        }
    }

    private fun openDiff(file: ChangedFile) {
        statusLabel.text = "Loading diff for ${file.filename}…"
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching {
                val baseDeferred = async {
                    if (file.status == "added") null
                    else client.getFileContentOrNull(context.owner, context.repo, file.previousFilename ?: file.filename, pr.base.sha)
                }
                val headDeferred = async {
                    if (file.status == "removed") null
                    else client.getFileContentOrNull(context.owner, context.repo, file.filename, pr.head.sha)
                }
                // Comments fetched alongside content — Phase 6 read-only
                // display. Fetches every review's comments on every open;
                // fine at this scale, revisit if PRs with many reviews make
                // this slow.
                val commentsDeferred = async {
                    client.getAllReviewComments(context.owner, context.repo, pr.number)
                        .filter { it.path == file.filename || it.path == file.previousFilename }
                }
                Triple(baseDeferred.await(), headDeferred.await(), commentsDeferred.await())
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { (baseText, headText, comments) ->
                        statusLabel.text = "${filesModel.size()} changed file(s) — double-click to view diff"
                        showDiff(file, baseText, headText, comments)
                    },
                    onFailure = { error ->
                        statusLabel.text = "Failed to load diff for ${file.filename}: ${error.message}"
                    },
                )
            }
        }
    }

    private fun showDiff(file: ChangedFile, baseText: String?, headText: String?, comments: List<PullReviewComment>) {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(file.filename)
        val contentFactory = DiffContentFactory.getInstance()
        val baseContent = baseText?.let { contentFactory.create(project, it, fileType) } ?: contentFactory.createEmpty()
        val headContent = headText?.let { contentFactory.create(project, it, fileType) } ?: contentFactory.createEmpty()

        val request = SimpleDiffRequest(
            "#${pr.number}: ${file.filename}",
            baseContent,
            headContent,
            pr.base.ref,
            pr.head.ref,
        )
        request.putUserData(
            GITEA_DIFF_COMMENT_CONTEXT_KEY,
            GiteaDiffCommentContext(context.owner, context.repo, pr.number, client, file.filename, comments),
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}

private class ChangedFileCellRenderer : ListCellRenderer<ChangedFile> {
    private val label = JBLabel()

    override fun getListCellRendererComponent(
        list: JList<out ChangedFile>,
        value: ChangedFile?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        label.text = value?.let { "[${it.status}]  ${it.filename}  (+${it.additions}/-${it.deletions})" } ?: ""
        label.isOpaque = true
        label.background = if (isSelected) list.selectionBackground else list.background
        label.foreground = if (isSelected) list.selectionForeground else list.foreground
        return label
    }
}
