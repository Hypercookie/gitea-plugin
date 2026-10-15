package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.ChangedBlock
import com.github.hypercookie.giteaplugin.pullrequest.review.CommentPlacement
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaNewCommentViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaThreadViewModel
import com.github.hypercookie.giteaplugin.pullrequest.review.LineMapping
import com.github.hypercookie.giteaplugin.pullrequest.review.PullRequestBase
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
import kotlin.coroutines.cancellation.CancellationException

/**
 * Review state of one file in one editor: which threads, pending comments
 * and compose boxes are shown on which document line, which lines can take
 * a new comment, and which lines the pull request changed.
 *
 * Structure follows the GitHub plugin's `GHPRReviewFileEditorModel`. The
 * line shifting it gets from the internal `ReviewInEditorUtil` is done by
 * [LineMapping], and the changes model it delegates to
 * the internal `MutableCodeReviewEditorGutterChangesModel` is implemented
 * here directly.
 *
 * @param basePath the file's path at the merge base. When it equals
 *   [path], the PR's changed files decide (a renamed file's previous path,
 *   or empty for an added file); see [PullRequestBase].
 * @param side which comment side this editor shows: [ReviewSide.NEW] for the
 *   working copy and the diff viewer's head editor, [ReviewSide.OLD] for the
 *   diff viewer's base editor.
 * @param showChanges whether to compute PR change markers (working copy
 *   only; a diff viewer already shows the diff).
 * @param editorLines where lines of [document] are in the editor that shows
 *   this model; differs from identity only in the unified diff viewer,
 *   where [document] is one side's file and the editor shows both sides.
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
    private val editorLines: StateFlow<EditorLineTransfer> = MutableStateFlow(EditorLineTransfer.Identity),
) : CodeReviewEditorModel<GiteaEditorInlay>, CodeReviewEditorGutterActionableChangesModel {

    private class Mappings(
        val refToDoc: List<ChangedBlock>,
        val anchorToDoc: Map<String, List<ChangedBlock>>,
        val baseText: String?,
        val prChanges: List<ChangedBlock>,
        /** Last line of the document the mappings were computed for. */
        val lastDocLine: Int,
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
            combine(mappings, data.threads, data.pending, newComments, editorLines) { m, threads, pending, new, lines ->
                Snapshot(m, threads, pending, new, lines)
            }.collect(::applySnapshot)
        }
    }

    private class Snapshot(
        val mappings: Mappings?,
        val threads: List<ReviewThread>,
        val pending: List<com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment>,
        val newComments: Map<Int, GiteaEditorInlay.NewComment>,
        val lines: EditorLineTransfer,
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
            val baseText = if (showChanges) loadBaseText(pr.mergeBase ?: pr.base.sha) else null
            val prChanges = baseText?.let {
                LineMapping.prChangesInDocument(LineMapping.compare(it, refText), refToDoc)
            }.orEmpty()
            Mappings(refToDoc, anchors, baseText, prChanges, lastDocLine = docText.count { it == '\n' })
        }
    } catch (e: com.intellij.diff.comparison.DiffTooBigException) {
        thisLogger().info("File too big to map review comments: $path", e)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A file fetch failed (logged by the review data); retried on the
        // next recompute since failures aren't cached.
        thisLogger().info("Failed to map review comments: $path", e)
        null
    }

    /**
     * Merge-base text of this file for the PR change markers: empty when the
     * PR added the file, null when it can't be known (fetch failed, or the
     * base is missing although the PR doesn't list the file as added). Null
     * means no markers, which beats marking the whole file as changed.
     */
    private suspend fun loadBaseText(mergeBase: String): String? {
        val base = if (basePath != path) {
            PullRequestBase.At(basePath)
        } else {
            val file = try {
                data.changedFiles()[path]
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            PullRequestBase.of(file, path)
        }
        return when (base) {
            PullRequestBase.Added -> ""
            is PullRequestBase.At -> try {
                data.fileContent(base.path, mergeBase)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Document placement of a thread, outdated if its line was changed
     * since the comment's commit (Gitea has no outdated flag). */
    private fun Mappings.placement(thread: ReviewThread): CommentPlacement {
        val blocks = thread.commitId?.let { anchorToDoc[it] } ?: refToDoc
        return LineMapping.placeComment(blocks, thread.line, lastDocLine)
    }

    /** Shows [inlay] at [placement] in editor lines; outdated is decided in
     * this side's document lines, before the editor-line mapping. */
    private fun GiteaEditorInlay.place(placement: CommentPlacement?, lines: EditorLineTransfer, keepExpanded: Boolean = false) {
        lineState.value = placement?.line?.let(lines::toEditor)
        if (placement != null) updateOutdated(placement.isOutdated, keepExpanded)
    }

    private fun applySnapshot(snapshot: Snapshot) {
        val m = snapshot.mappings
        val lines = snapshot.lines
        val fileThreads = snapshot.threads.filter { it.path == path && it.side == side }

        val liveIds = fileThreads.mapTo(HashSet()) { it.id }
        threadVms.keys.retainAll(liveIds)
        for (thread in fileThreads) {
            val inlay = threadVms.getOrPut(thread.id) { GiteaEditorInlay.Thread(GiteaThreadViewModel(cs, data, thread)) }
            inlay.vm.update(thread)
            inlay.place(m?.placement(thread), lines, keepExpanded = inlay.vm.isWritingReply.value)
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
            inlay.place(m?.let { LineMapping.placeComment(it.refToDoc, refLine, it.lastDocLine) }, lines)
        }

        for (inlay in snapshot.newComments.values) {
            inlay.lineState.value = m?.let { LineMapping.transfer(it.refToDoc, inlay.vm.line) }?.let(lines::toEditor)
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
                lines = lines,
            )
        }
    }

    private class ControlsState(
        override val linesWithComments: Set<Int>,
        override val linesWithNewComments: Set<Int>,
        private val docToRef: List<ChangedBlock>,
        private val lines: EditorLineTransfer,
    ) : CodeReviewEditorGutterControlsModel.ControlsState {
        /** Lines the user edited locally have no counterpart in the PR, so
         * a comment there can't be anchored; neither can a unified diff
         * line of the other side. */
        override fun isLineCommentable(lineIdx: Int): Boolean =
            lines.fromEditor(lineIdx)?.let { LineMapping.transfer(docToRef, it) } != null
    }

    override fun requestNewComment(lineIdx: Int) {
        val m = mappings.value ?: return
        val docLine = editorLines.value.fromEditor(lineIdx) ?: return
        val refLine = LineMapping.transfer(m.docToRef, docLine) ?: return
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
        val docLine = editorLines.value.fromEditor(lineIdx) ?: return
        LineMapping.transfer(m.docToRef, docLine)?.let(::removeNewComment)
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
