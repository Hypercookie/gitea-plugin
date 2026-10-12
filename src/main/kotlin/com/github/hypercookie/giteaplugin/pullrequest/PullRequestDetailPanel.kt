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
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JList

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
    private val onViewTimeline: () -> Unit,
) {

    // Project-level, keyed by PR — shared with any working-copy editor
    // showing comments for this same PR (see PendingReviewStoreService).
    private val pendingReviewStore =
        PendingReviewStoreService.getInstance(project).storeFor(context.owner, context.repo, pr.number)

    private val filesModel = DefaultListModel<ChangedFile>()
    private val filesList = JBList(filesModel).apply {
        cellRenderer = ChangedFileCellRenderer()
        emptyText.text = "No changed files"
    }
    private val statusLabel = JBLabel("Loading changed files…").apply { border = JBUI.Borders.empty(4, 8) }

    private val reviewActionsPanel =
        ReviewActionsPanel(project, client, context.owner, context.repo, pr.number, pr.head.sha, pendingReviewStore)

    val component = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(buildOverviewPanel(), BorderLayout.NORTH)

        val filesPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(statusLabel, BorderLayout.NORTH)
            add(JBScrollPane(filesList), BorderLayout.CENTER)
        }
        add(filesPanel, BorderLayout.CENTER)
        add(reviewActionsPanel.component, BorderLayout.SOUTH)
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
        val title = JBLabel("#${pr.number}  ${pr.title}", if (pr.merged) AllIcons.Vcs.Merge else AllIcons.Vcs.Branch, JBLabel.LEFT).apply {
            font = JBFont.h2()
        }
        val stateColor = when {
            pr.merged -> JBColor(0x8250DF, 0xA371F7)
            pr.state == "closed" -> JBColor(0xCF222E, 0xF85149)
            pr.draft -> JBColor.GRAY
            else -> JBColor(0x1A7F37, 0x3FB950)
        }
        val stateText = when {
            pr.merged -> "merged"
            pr.state == "closed" -> "closed"
            pr.draft -> "draft"
            else -> "open"
        }
        val branches = JBLabel("<html><b style='color:#${ColorUtil.toHex(stateColor)}'>$stateText</b>&nbsp;&nbsp;${pr.head.ref} → ${pr.base.ref}</html>")
        val timelineLink = ActionLink("View Timeline") { onViewTimeline() }
        val description = JBTextArea(pr.body?.takeIf { it.isNotBlank() } ?: "(no description)").apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            preferredSize = Dimension(600, 100)
            background = UIUtil.getPanelBackground()
        }

        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)
        panel.add(title)
        panel.add(Box.createVerticalStrut(4))
        panel.add(branches)
        panel.add(Box.createVerticalStrut(4))
        panel.add(timelineLink)
        panel.add(Box.createVerticalStrut(8))
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
            GiteaDiffCommentContext(context.owner, context.repo, pr.number, client, file.filename, comments, pendingReviewStore),
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}

private class ChangedFileCellRenderer : ColoredListCellRenderer<ChangedFile>() {
    override fun customizeCellRenderer(
        list: JList<out ChangedFile>,
        value: ChangedFile?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        val file = value ?: return
        icon = when (file.status) {
            "added" -> AllIcons.General.Add
            "removed", "deleted" -> AllIcons.General.Remove
            "renamed" -> AllIcons.Actions.MoveTo2
            else -> AllIcons.Actions.Edit
        }
        ipad = JBUI.insets(2, 8)

        append(file.filename, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append("  ")
        append("+${file.additions}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ADD_COLOR))
        append(" ")
        append("-${file.deletions}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DEL_COLOR))
    }

    companion object {
        private val ADD_COLOR = JBColor(0x1A7F37, 0x3FB950)
        private val DEL_COLOR = JBColor(0xCF222E, 0xF85149)
    }
}
