package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.review.GiteaPullRequestReviewData
import com.github.hypercookie.giteaplugin.pullrequest.review.editor.GiteaEditorInlay
import com.intellij.codeInsight.hint.HintManager
import com.intellij.diff.DiffContext
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.impl.CacheDiffRequestChainProcessor
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.DiffDataKeys
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.IdeFocusManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Previous / Next Comment" state of one pull request diff chain (see
 * [GiteaPullRequestDiff][com.github.hypercookie.giteaplugin.pullrequest.details.GiteaPullRequestDiff]),
 * stored on the chain under [KEY]. The diff context falls back to the
 * chain's user data, so actions and [GiteaCommentDiffExtension] find it
 * there.
 *
 * Crossing files switches the chain's current request with the public
 * `CacheDiffRequestChainProcessor.setCurrentRequest`, which the platform's
 * "Go to Change" popup uses too. The processor is reachable only from the
 * diff editor tab (`DiffEditorViewerFileEditor`); in a separate diff
 * window, navigation stays within the shown file, because the platform's
 * own file navigator is internal API.
 *
 * @param requests the chain's requests, in chain order.
 * @param files the same files as [requests], for [CommentNavigation].
 */
class GiteaDiffCommentNavigation(
    private val requests: List<DiffRequest>,
    private val files: List<CommentNavigation.File>,
) {
    /** Thread last moved to, so the next step continues from it. */
    private var currentThreadId: Long? = null

    /** What a step from the current request of [e] goes to, or null. */
    private fun plan(e: AnActionEvent, next: Boolean): Plan? {
        val request = e.getData(DiffDataKeys.DIFF_REQUEST) ?: return null
        val viewer = request.getUserData(VIEWER_KEY) ?: return null
        val fileIndex = requests.indexOfFirst { it === request }.takeIf { it >= 0 } ?: return null
        val threads = viewer.data.threads.value
        val canCross = processor(e) != null
        val navigable = if (canCross) files else files.mapIndexed { i, f -> if (i == fileIndex) f else NO_FILE }
        val entries = CommentNavigation.entries(navigable, threads, includeResolved)
        val current = CommentNavigation.locate(files, threads, currentThreadId)
        val target = if (next) CommentNavigation.next(entries, current, fileIndex) else CommentNavigation.previous(entries, current, fileIndex)
        return target?.let { Plan(viewer, fileIndex, it, positionText(entries, it)) }
    }

    private class Plan(val viewer: GiteaDiffCommentViewer, val fileIndex: Int, val target: CommentNavigation.Entry, val text: String)

    private fun positionText(entries: List<CommentNavigation.Entry>, target: CommentNavigation.Entry): String {
        val text = "Comment ${entries.indexOf(target) + 1} of ${entries.size}"
        return if (includeResolved) text else "$text (unresolved)"
    }

    fun canStep(e: AnActionEvent, next: Boolean): Boolean = plan(e, next) != null

    fun step(e: AnActionEvent, next: Boolean) {
        val plan = plan(e, next) ?: return
        currentThreadId = plan.target.threadId
        val reveal = PendingReveal(plan.target.threadId, plan.text)
        if (plan.target.fileIndex == plan.fileIndex) {
            plan.viewer.reveal(reveal)
            return
        }
        val targetRequest = requests[plan.target.fileIndex]
        // Picked up by the extension once the target file's viewer exists.
        targetRequest.putUserData(PENDING_REVEAL_KEY, reveal)
        processor(e)?.setCurrentRequest(plan.target.fileIndex) ?: targetRequest.putUserData(PENDING_REVEAL_KEY, null)
    }

    /** The processor showing this chain in an editor tab. */
    private fun processor(e: AnActionEvent): CacheDiffRequestChainProcessor? {
        fun CacheDiffRequestChainProcessor.isOurs() = requestChain.getUserData(KEY) === this@GiteaDiffCommentNavigation
        val fromContext = (e.getData(PlatformCoreDataKeys.FILE_EDITOR) as? DiffEditorViewerFileEditor)?.editorViewer as? CacheDiffRequestChainProcessor
        if (fromContext?.isOurs() == true) return fromContext
        val project = e.project ?: return null
        return FileEditorManager.getInstance(project).allEditors.asSequence()
            .mapNotNull { (it as? DiffEditorViewerFileEditor)?.editorViewer as? CacheDiffRequestChainProcessor }
            .firstOrNull { it.isOurs() }
    }

    companion object {
        val KEY: Key<GiteaDiffCommentNavigation> = Key.create("Gitea.DiffCommentNavigation")

        /** The viewer currently showing a request, set by [GiteaCommentDiffExtension]. */
        internal val VIEWER_KEY: Key<GiteaDiffCommentViewer> = Key.create("Gitea.DiffCommentViewer")

        /** Thread to reveal once the request's viewer is created. */
        internal val PENDING_REVEAL_KEY: Key<PendingReveal> = Key.create("Gitea.DiffCommentPendingReveal")

        private val NO_FILE = CommentNavigation.File("", "", hasNew = false, hasOld = false)

        private const val INCLUDE_RESOLVED_PROPERTY = "Gitea.Diff.CommentNavigation.IncludeResolved"

        var includeResolved: Boolean
            get() = PropertiesComponent.getInstance().getBoolean(INCLUDE_RESOLVED_PROPERTY, false)
            set(value) = PropertiesComponent.getInstance().setValue(INCLUDE_RESOLVED_PROPERTY, value, false)

        fun of(context: DiffContext?): GiteaDiffCommentNavigation? = context?.getUserData(KEY)
    }
}

internal class PendingReveal(val threadId: Long, val text: String)

/**
 * Review threads of one diff viewer: each editor of the viewer with the
 * inlays of its editor model, whose lines are already editor lines (also
 * in the unified viewer, see `EditorLineTransfer`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class GiteaDiffCommentViewer(
    private val cs: CoroutineScope,
    val data: GiteaPullRequestReviewData,
    private val editors: List<Pair<EditorEx, StateFlow<Collection<GiteaEditorInlay>>>>,
) {
    fun reveal(reveal: PendingReveal) {
        cs.launch(Dispatchers.Main) {
            // Lines are known once the editor model has mapped the file,
            // which takes file fetches right after the viewer opened.
            val found = withTimeoutOrNull(REVEAL_TIMEOUT_MS) {
                combine(editors.map { (editor, inlays) -> threadLine(inlays, reveal.threadId).map { it?.let { (inlay, line) -> Triple(editor, inlay, line) } } }) {
                    it.firstNotNullOfOrNull { found -> found }
                }.filterNotNull().first()
            }
            if (found == null) return@launch
            val (editor, inlay, line) = found
            val wasShown = !inlay.hiddenState.value && !inlay.collapsedState.value
            inlay.hiddenState.value = false
            inlay.visibleState.value = true
            inlay.collapsedState.value = false
            // The line is known before the inlay is in the editor (it is
            // added in a later EDT event); scrolling then would measure
            // without it and the comment would appear after the jump.
            val wasInEditor = inlay.shownState.value
            if (!wasInEditor) withTimeoutOrNull(RENDER_TIMEOUT_MS) { inlay.shownState.first { it } }
            // Let an expanded or just added inlay lay out before measuring it.
            if (!wasShown || !wasInEditor) delay(LAYOUT_DELAY_MS)
            scrollTo(editor, line)
            IdeFocusManager.getInstance(data.project).requestFocus(editor.contentComponent, true)
            HintManager.getInstance().showInformationHint(editor, reveal.text)
        }
    }

    private fun threadLine(inlays: StateFlow<Collection<GiteaEditorInlay>>, threadId: Long) =
        inlays.flatMapLatest { all ->
            val inlay = all.firstOrNull { it is GiteaEditorInlay.Thread && it.vm.id == threadId }
            inlay?.lineState?.map { line -> line?.let { inlay to it } } ?: flowOf(null)
        }

    /** Caret to [line], and the line with the inlays below it centered; a
     * block taller than the editor starts at the top. */
    private fun scrollTo(editor: EditorEx, line: Int) {
        if (line >= editor.document.lineCount) return
        val position = LogicalPosition(line, 0)
        editor.caretModel.moveToLogicalPosition(position)
        val top = editor.logicalPositionToXY(position).y
        val visualLine = editor.logicalToVisualPosition(position).line
        val bottom = editor.inlayModel.getBlockElementsForVisualLine(visualLine, false)
            .mapNotNull { it.bounds?.maxY?.toInt() }
            .maxOrNull() ?: (top + editor.lineHeight)
        val visible = editor.scrollingModel.visibleArea.height
        val y = if (bottom - top < visible) top - (visible - (bottom - top)) / 2 else top
        editor.scrollingModel.disableAnimation()
        try {
            editor.scrollingModel.scrollVertically(maxOf(y, 0))
        } finally {
            editor.scrollingModel.enableAnimation()
        }
    }

    private companion object {
        const val REVEAL_TIMEOUT_MS = 15_000L
        const val LAYOUT_DELAY_MS = 50L
        const val RENDER_TIMEOUT_MS = 1_000L
    }
}

/**
 * Previous / next review thread of a pull request diff. Shown in the diff
 * toolbar through `DiffUserDataKeys.CONTEXT_ACTIONS` on the chain; also
 * registered in plugin.xml so a shortcut can be assigned in the keymap.
 */
abstract class GiteaCommentNavigationAction(private val next: Boolean) : DumbAwareAction() {
    init {
        isEnabledInModalContext = true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val navigation = GiteaDiffCommentNavigation.of(e.getData(DiffDataKeys.DIFF_CONTEXT))
        e.presentation.isVisible = navigation != null
        e.presentation.isEnabled = navigation?.canStep(e, next) == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        GiteaDiffCommentNavigation.of(e.getData(DiffDataKeys.DIFF_CONTEXT))?.step(e, next)
    }
}

class GiteaPreviousCommentAction : GiteaCommentNavigationAction(next = false)

class GiteaNextCommentAction : GiteaCommentNavigationAction(next = true)

/** Whether comment navigation includes resolved threads; in the diff
 * editor's context menu, to keep the toolbar to the two arrows. */
class GiteaIncludeResolvedCommentsAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabledAndVisible = GiteaDiffCommentNavigation.of(e.getData(DiffDataKeys.DIFF_CONTEXT)) != null
    }

    override fun isSelected(e: AnActionEvent): Boolean = GiteaDiffCommentNavigation.includeResolved

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        GiteaDiffCommentNavigation.includeResolved = state
    }
}
