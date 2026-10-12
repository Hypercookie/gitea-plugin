package com.github.hypercookie.giteaplugin.pullrequest

import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import com.github.hypercookie.giteaplugin.api.dto.PullRequest
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.pullrequest.comments.markdownLabel
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
import com.intellij.openapi.vcs.FileStatus
import com.intellij.ui.ColorUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
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
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * One opened PR's detail tab: overview (title/branches/markdown-rendered
 * description) + changed-files list on the left, the PR's [TimelinePanel]
 * embedded directly on the right (an `OnePixelSplitter`, not a separate tab
 * — matches the reference GitHub-plugin layout, see
 * docs/plan/pr-review-feature-plan.md "UI pass 3" follow-up round 3).
 * Selecting a file opens it in the Platform's own diff viewer
 * (`DiffManager.showDiff`) rather than a custom view — see Phase 5.
 * Comments/review UI land on top of that same diff viewer via
 * `com.intellij.diff.DiffExtension`.
 */
class PullRequestDetailPanel(
    private val project: Project,
    private val context: GiteaRepoContext,
    private val client: GiteaApiClient,
    private val pr: PullRequest,
) {

    // Project-level, keyed by PR — shared with any working-copy editor
    // showing comments for this same PR (see PendingReviewStoreService).
    private val pendingReviewStore =
        PendingReviewStoreService.getInstance(project).storeFor(context.owner, context.repo, pr.number)

    // Nested-folder tree instead of a flat path-per-row list — matches the
    // native Project/Changes tree look the user pointed at (folders with a
    // "N files" count, single-child directory chains collapsed onto one
    // row, file-type icons, FileStatus-colored names), not a flat list of
    // full paths.
    private var allFiles: List<ChangedFile> = emptyList()
    private var filesRoot = DefaultMutableTreeNode(DirEntry(context.repo, 0))
    private val filesTree = Tree(DefaultTreeModel(filesRoot)).apply {
        isRootVisible = true
        cellRenderer = FileTreeCellRenderer()
        emptyText.text = "No changed files"
    }
    private val statusLabel = JBLabel("Loading changed files…").apply { border = JBUI.Borders.empty(4, 8) }

    private val reviewActionsPanel =
        ReviewActionsPanel(project, client, context.owner, context.repo, pr.number, pr.head.sha, pendingReviewStore, pr.user?.login)

    private val timelinePanel = TimelinePanel(project, context, client, pr)

    val component = OnePixelSplitter(false, 0.45f).apply {
        firstComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(buildOverviewPanel(), BorderLayout.NORTH)

            val filesPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(statusLabel, BorderLayout.NORTH)
                add(JBScrollPane(filesTree), BorderLayout.CENTER)
            }
            add(filesPanel, BorderLayout.CENTER)
            add(reviewActionsPanel.component, BorderLayout.SOUTH)
        }
        secondComponent = timelinePanel.component
    }

    init {
        filesTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val path = filesTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                (node.userObject as? ChangedFile)?.let { openDiff(it) }
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
        // Same Markdown rendering as everywhere else a Gitea body shows up
        // (CommentBoxPanels.kt's shared markdownLabel/markdownView) — the PR
        // description is Markdown source too, not plain text.
        val description = JBScrollPane(markdownLabel(pr.body?.takeIf { it.isNotBlank() } ?: "_(no description)_", widthPx = 460)).apply {
            preferredSize = Dimension(600, 120)
        }

        val panel = JBPanel<JBPanel<*>>()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)
        panel.add(title)
        panel.add(Box.createVerticalStrut(4))
        panel.add(branches)
        panel.add(Box.createVerticalStrut(8))
        panel.add(description)
        return panel
    }

    private fun loadFiles() {
        GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
            val result = runCatching { client.getPullRequestFiles(context.owner, context.repo, pr.number) }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { files ->
                        allFiles = files
                        filesRoot = buildFileTree(context.repo, files)
                        filesTree.model = DefaultTreeModel(filesRoot)
                        TreeUtil.expandAll(filesTree)
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
                        statusLabel.text = "${allFiles.size} changed file(s) — double-click to view diff"
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

/** A tree-node payload for a directory row: display name (post-compaction,
 * see [buildFileTree]) and total file count underneath it, matching the
 * native Project/Changes tree's "name  N files" row. `var` because
 * [buildFileTree] fills in the count/compacts the name after the tree
 * shape is already built. */
private class DirEntry(var name: String, var fileCount: Int)

/** Builds a nested directory tree from Gitea's flat changed-file paths,
 * matching the native Project/Changes tree's look (not a flat path-per-row
 * list): [repoName] as the root, directories grouped by path segment, and
 * — same as "Compact Middle Packages" in the real Project view — a chain of
 * directories that each have exactly one child directory and no files of
 * their own collapsed onto a single "a/b/c" row. */
private fun buildFileTree(repoName: String, files: List<ChangedFile>): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode(DirEntry(repoName, 0))
    val dirNodes = mutableMapOf<String, DefaultMutableTreeNode>()
    dirNodes[""] = root

    fun dirNode(path: String): DefaultMutableTreeNode =
        dirNodes.getOrPut(path) {
            val lastSlash = path.lastIndexOf('/')
            val parentPath = if (lastSlash < 0) "" else path.substring(0, lastSlash)
            val name = if (lastSlash < 0) path else path.substring(lastSlash + 1)
            val node = DefaultMutableTreeNode(DirEntry(name, 0))
            dirNode(parentPath).add(node)
            node
        }

    files.forEach { file ->
        val lastSlash = file.filename.lastIndexOf('/')
        val dirPath = if (lastSlash < 0) "" else file.filename.substring(0, lastSlash)
        dirNode(dirPath).add(DefaultMutableTreeNode(file))
    }

    fun countFiles(node: DefaultMutableTreeNode): Int {
        val entry = node.userObject as? DirEntry
        if (entry == null) return 1 // a ChangedFile leaf
        var count = 0
        for (i in 0 until node.childCount) count += countFiles(node.getChildAt(i) as DefaultMutableTreeNode)
        entry.fileCount = count
        return count
    }
    countFiles(root)

    fun compact(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) compact(node.getChildAt(i) as DefaultMutableTreeNode)
        if (node === root) return
        if (node.childCount == 1) {
            val onlyChild = node.getChildAt(0) as DefaultMutableTreeNode
            val childEntry = onlyChild.userObject as? DirEntry ?: return
            val entry = node.userObject as DirEntry
            childEntry.name = "${entry.name}/${childEntry.name}"
            val parent = node.parent as DefaultMutableTreeNode
            val index = parent.getIndex(node)
            parent.remove(node)
            parent.insert(onlyChild, index)
        }
    }
    for (i in 0 until root.childCount) compact(root.getChildAt(i) as DefaultMutableTreeNode)

    return root
}

private class FileTreeCellRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val payload = node.userObject) {
            is DirEntry -> {
                icon = if (node === tree.model.root) AllIcons.Nodes.Module else AllIcons.Nodes.Folder
                append(payload.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append("  ${payload.fileCount} file(s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ChangedFile -> {
                val baseName = payload.filename.substringAfterLast('/')
                icon = FileTypeManager.getInstance().getFileTypeByFileName(baseName).icon
                // Same file-status colors as the Platform's own Changes/Commit
                // tool windows (com.intellij.openapi.vcs.FileStatus) — "looks
                // like the native git diff view" per the user's ask, not a
                // one-off palette.
                val color = when (payload.status) {
                    "added" -> FileStatus.ADDED.color
                    "removed", "deleted" -> FileStatus.DELETED.color
                    else -> FileStatus.MODIFIED.color
                }
                append(baseName, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
                append("  +${payload.additions}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, ADD_COLOR))
                append(" -${payload.deletions}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, DEL_COLOR))
            }
        }
    }

    companion object {
        private val ADD_COLOR = JBColor(0x1A7F37, 0x3FB950)
        private val DEL_COLOR = JBColor(0xCF222E, 0xF85149)
    }
}
