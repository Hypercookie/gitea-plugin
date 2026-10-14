package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.ChangedBlock
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaNewCommentViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaThreadViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.LineMapping
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewThread
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterActionableChangesModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorGutterControlsModel
import com.intellij.collaboration.ui.codereview.editor.CodeReviewEditorModel
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.util.LineRange
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vcs.ex.Range
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Review state of one file in one editor: which threads, pending comments
 * and compose boxes are shown on which document line, which lines can take
 * a new comment, and which lines the pull request changed.
 *
 * Structure follows the GitHub plugin's `GHPRReviewFileEditorModel`. The
 * line shifting it gets from the internal `ReviewInEditorUtil` is done by
 * [LineMapping] (migration ledger I6), and the changes model it delegates to
 * the internal `MutableCodeReviewEditorGutterChangesModel` is implemented
 * here directly (I7).
 *
 * @param side which comment side this editor shows: [ReviewSide.NEW] for the
 *   working copy and the diff viewer's head editor, [ReviewSide.OLD] for the
 *   diff viewer's base editor.
 * @param showChanges whether to compute PR change markers (working copy
 *   only; a diff viewer already shows the diff).
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class GiteaReviewEditorModel(
    private val cs: CoroutineScope,
    private val data: GiteaPullRequestReviewData,
    private val path: String,
    private val basePath: String,
    private val side: ReviewSide,
    private val document: Document,
    private val showChanges: Boolean,
) : CodeReviewEditorModel<GiteaEditorInlay>, CodeReviewEditorGutterActionableChangesModel {

    private class Mappings(
        val refToDoc: List<ChangedBlock>,
        val anchorToDoc: Map<String, List<ChangedBlock>>,
        val baseText: String?,
        val prChanges: List<ChangedBlock>,
    ) {
        val docToRef: List<ChangedBlock> = LineMapping.inverse(refToDoc)
    }

    private val documentStamp = MutableStateFlow(0L)
    private val mappings = MutableStateFlow<Mappings?>(null)

    private val threadVms = LinkedHashMap<Long, GiteaEditorInlay.Thread>()
    private val pendingInlays = LinkedHashMap<Any, GiteaEditorInlay.Pending>()
    private val newComments = MutableStateFlow<Map<Int, GiteaEditorInlay.NewComment>>(emptyMap())

    private val _inlays = MutableStateFlow<Collection<GiteaEditorInlay>>(emptyList())
    override val inlays: StateFlow<Collection<GiteaEditorInlay>> = _inlays.asStateFlow()

    private val _controls = MutableStateFlow<CodeReviewEditorGutterControlsModel.ControlsState?>(null)
    override val gutterControlsState: StateFlow<CodeReviewEditorGutterControlsModel.ControlsState?> = _controls.asStateFlow()

    override val reviewRanges: StateFlow<List<Range>> = mappings.map { m ->
        m?.prChanges.orEmpty().map { Range(it.start2, it.end2, it.start1, it.end1) }
    }.stateIn(cs, SharingStarted.Eagerly, emptyList())

    private val highlightListeners = mutableListOf<() -> Unit>()
    override var shouldHighlightDiffRanges: Boolean = false
        set(value) {
            field = value
            highlightListeners.toList().forEach { it() }
        }

    init {
        val listenerDisposable = Disposer.newDisposable("Gitea review document listener")
        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                documentStamp.value = document.modificationStamp
            }
        }, listenerDisposable)
        cs.launch {
            try {
                awaitCancellation()
            } finally {
                Disposer.dispose(listenerDisposable)
            }
        }

        // Off the EDT: the caller's scope is Main, but the mapping work
        // (read action, file fetches, line diffs) belongs in the background.
        cs.launch(Dispatchers.Default) {
            combine(documentStamp.debounce(DOCUMENT_DEBOUNCE_MS), data.threads, data.pullRequest) { _, threads, _ -> threads }
                .mapLatest { threads -> computeMappings(threads) }
                .collect { mappings.value = it }
        }

        cs.launch(Dispatchers.Main) {
            combine(mappings, data.threads, data.pending, newComments) { m, threads, pending, new ->
                Snapshot(m, threads, pending, new)
            }.collect(::applySnapshot)
        }
    }

    private class Snapshot(
        val mappings: Mappings?,
        val threads: List<ReviewThread>,
        val pending: List<com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment>,
        val newComments: Map<Int, GiteaEditorInlay.NewComment>,
    )

    private suspend fun computeMappings(threads: List<ReviewThread>): Mappings? = try {
        val docText = readAction { document.immutableCharSequence }
        // Commit whose file version comment lines of [side] refer to by
        // default: PR head for NEW, merge base for OLD. Read per run so a
        // pushed head commit is picked up.
        val pr = data.pullRequest.value
        val referenceRef = if (side == ReviewSide.NEW) pr.head.sha else pr.mergeBase ?: pr.base.sha
        withContext(Dispatchers.Default) {
            val refText = data.fileContent(path, referenceRef) ?: return@withContext null
            val refToDoc = LineMapping.compare(refText, docText)
            val anchors = if (side == ReviewSide.NEW) {
                threads
                    .filter { it.path == path && it.side == side }
                    .mapNotNull { it.commitId }
                    .filter { it != referenceRef }
                    .distinct()
                    .mapNotNull { commit -> data.fileContent(path, commit)?.let { commit to LineMapping.compare(it, docText) } }
                    .toMap()
            } else {
                emptyMap()
            }
            val baseText = if (showChanges) data.fileContent(basePath, pr.mergeBase ?: pr.base.sha) else null
            val prChanges = if (showChanges) {
                LineMapping.prChangesInDocument(LineMapping.compare(baseText.orEmpty(), refText), refToDoc)
            } else {
                emptyList()
            }
            Mappings(refToDoc, anchors, baseText, prChanges)
        }
    } catch (e: com.intellij.diff.comparison.DiffTooBigException) {
        thisLogger().info("File too big to map review comments: $path", e)
        null
    }

    /** Document line for a thread, or null if its line was changed since
     * the comment's commit (Gitea has no outdated flag; ledger G10). */
    private fun Mappings.docLine(thread: ReviewThread): Int? {
        val blocks = thread.commitId?.let { anchorToDoc[it] } ?: refToDoc
        return LineMapping.transfer(blocks, thread.line)
    }

    private fun applySnapshot(snapshot: Snapshot) {
        val m = snapshot.mappings
        val fileThreads = snapshot.threads.filter { it.path == path && it.side == side }

        val liveIds = fileThreads.mapTo(HashSet()) { it.id }
        threadVms.keys.retainAll(liveIds)
        for (thread in fileThreads) {
            val inlay = threadVms.getOrPut(thread.id) { GiteaEditorInlay.Thread(GiteaThreadViewModel(cs, data, thread)) }
            inlay.vm.update(thread)
            inlay.lineState.value = m?.docLine(thread)
        }

        val filePending = snapshot.pending.filter {
            it.path == path && (if (side == ReviewSide.NEW) it.newPosition > 0 else it.oldPosition > 0)
        }
        val livePendingKeys = filePending.mapTo(HashSet()) { System.identityHashCode(it) }
        pendingInlays.keys.retainAll(livePendingKeys)
        for (comment in filePending) {
            val inlay = pendingInlays.getOrPut(System.identityHashCode(comment)) {
                GiteaEditorInlay.Pending(comment, data.currentUser) { data.pendingStore.removeComment(comment) }
            }
            val refLine = ((if (side == ReviewSide.NEW) comment.newPosition else comment.oldPosition) - 1).toInt()
            inlay.lineState.value = m?.let { LineMapping.transfer(it.refToDoc, refLine) }
        }

        for (inlay in snapshot.newComments.values) {
            inlay.lineState.value = m?.let { LineMapping.transfer(it.refToDoc, inlay.vm.line) }
        }

        (threadVms.values + pendingInlays.values + snapshot.newComments.values).forEach {
            it.visibleState.value = !it.hiddenState.value
        }
        // Back to front, like the GitHub plugin: most initial work happens off-screen.
        _inlays.value = threadVms.values.sortedByDescending { it.lineState.value ?: -1 } +
            pendingInlays.values + snapshot.newComments.values

        _controls.value = m?.let {
            ControlsState(
                linesWithComments = (threadVms.values + pendingInlays.values).mapNotNullTo(HashSet()) { inlay -> inlay.lineState.value },
                linesWithNewComments = snapshot.newComments.values.mapNotNullTo(HashSet()) { inlay -> inlay.lineState.value },
                docToRef = it.docToRef,
            )
        }
    }

    private class ControlsState(
        override val linesWithComments: Set<Int>,
        override val linesWithNewComments: Set<Int>,
        private val docToRef: List<ChangedBlock>,
    ) : CodeReviewEditorGutterControlsModel.ControlsState {
        /** Lines the user edited locally have no counterpart in the PR, so
         * a comment there can't be anchored. */
        override fun isLineCommentable(lineIdx: Int): Boolean = LineMapping.transfer(docToRef, lineIdx) != null
    }

    override fun requestNewComment(lineIdx: Int) {
        val m = mappings.value ?: return
        val refLine = LineMapping.transfer(m.docToRef, lineIdx) ?: return
        val current = newComments.value
        current[refLine]?.let {
            it.vm.requestFocus()
            return
        }
        val vm = GiteaNewCommentViewModel(cs, data, path, side, refLine) { removeNewComment(refLine) }
        newComments.value = current + (refLine to GiteaEditorInlay.NewComment(vm))
        vm.requestFocus()
    }

    override fun cancelNewComment(lineIdx: Int) {
        val m = mappings.value ?: return
        LineMapping.transfer(m.docToRef, lineIdx)?.let(::removeNewComment)
    }

    private fun removeNewComment(refLine: Int) {
        newComments.value = newComments.value - refLine
    }

    override fun toggleComments(lineIdx: Int) {
        val atLine = (threadVms.values + pendingInlays.values).filter { it.lineState.value == lineIdx }
        if (atLine.isEmpty()) return
        // Same rule as the platform's Hideable.syncOrToggleAll: if any is
        // hidden, show all; otherwise hide all.
        val hide = atLine.none { it.hiddenState.value }
        atLine.forEach {
            it.hiddenState.value = hide
            it.visibleState.value = !hide
        }
    }

    // --- CodeReviewEditorGutterActionableChangesModel ----------------------

    override fun isValid(): Boolean = mappings.value != null

    override fun getRanges(): List<Range> = reviewRanges.value

    override fun findRange(range: Range): Range? = reviewRanges.value.find {
        it.line1 == range.line1 && it.line2 == range.line2 && it.vcsLine1 == range.vcsLine1 && it.vcsLine2 == range.vcsLine2
    }

    override fun getBaseContent(lines: LineRange): String? {
        val base = mappings.value?.baseText ?: return null
        val all = base.lines()
        if (lines.start < 0 || lines.end > all.size || lines.start > lines.end) return null
        return all.subList(lines.start, lines.end).joinToString("\n")
    }

    override fun showDiff(lineIdx: Int?) {
        val base = mappings.value?.baseText ?: return
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "$path (pull request #${data.number})",
            factory.create(base),
            factory.create(data.project, document),
            "Merge base",
            "Local",
        )
        DiffManager.getInstance().showDiff(data.project, request)
    }

    override fun addDiffHighlightListener(disposable: Disposable, listener: () -> Unit) {
        highlightListeners += listener
        Disposer.register(disposable) { highlightListeners -= listener }
    }

    companion object {
        private const val DOCUMENT_DEBOUNCE_MS = 300L
    }
}
