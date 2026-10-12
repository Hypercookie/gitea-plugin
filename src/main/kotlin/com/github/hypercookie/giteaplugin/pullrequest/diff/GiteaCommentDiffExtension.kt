package com.github.hypercookie.giteaplugin.pullrequest.diff

import com.github.hypercookie.giteaplugin.pullrequest.comments.CommentGutterController
import com.github.hypercookie.giteaplugin.util.GiteaProjectCoroutineScope
import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.simple.SimpleDiffViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders Gitea PR review comments as gutter icons in the diff viewer, on
 * both the base and head editors — one icon per line, representing either
 * an existing comment thread, a locally-queued pending comment (not yet
 * submitted), or an "add comment" affordance (every line is addable now,
 * not just changed ones — see docs/plan/pr-review-feature-plan.md,
 * "Architecture pivot"). Also handles replying to an existing thread.
 * Rendering itself lives in [CommentGutterController], shared with the
 * working-copy-editor path.
 *
 * Registered as `<diff.DiffExtension>` — a real, public, `dynamic="true"`
 * platform extension point (confirmed by inspecting the bundled platform
 * jar's own `PlatformExtensionPoints.xml`; see
 * docs/plan/pr-review-feature-plan.md Phase 5/6 hardening-pass notes).
 *
 * Deliberately built on stable primitives (`RangeHighlighter` +
 * `GutterIconRenderer` + a plain popup) rather than the internal
 * `com.intellij.collaboration.*` module — confirmed to exist and be what a
 * more mature third-party Gitea plugin
 * (github.com/JpMand/gitea-idea-integration) uses for a richer inlay-based
 * UI, at the cost of `@Suppress("UnstableApiUsage")` and IDE-version
 * fragility. That tradeoff isn't worth it here.
 *
 * Position mapping: Gitea's `position`/`original_position` are confirmed
 * (empirically, and independently corroborated by the same third-party
 * plugin's code) to be 1-indexed real file line numbers, not diff-hunk
 * offsets — so `line - 1` is the entire conversion to a 0-indexed editor line.
 */
class GiteaCommentDiffExtension : DiffExtension() {

    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, diffContext: DiffContext, request: DiffRequest) {
        val commentContext = request.getUserData(GITEA_DIFF_COMMENT_CONTEXT_KEY) ?: return
        if (viewer !is SimpleDiffViewer) return
        val project = diffContext.project ?: return

        val baseController = CommentGutterController(project, viewer.getEditor1(), commentContext, useOriginalPosition = true)
        val headController = CommentGutterController(project, viewer.getEditor2(), commentContext, useOriginalPosition = false)
        baseController.updateComments(commentContext.comments)
        headController.updateComments(commentContext.comments)

        // Render immediately (synchronous, local-only — closes a just-used
        // "new comment" box right away instead of leaving it open until a
        // network round trip finishes, which read as "stays open" during
        // manual testing even though it did eventually close). Then also
        // re-fetch from the server in the background: needed for the case
        // where the change was "a review got submitted elsewhere" (submit
        // clears the store) — the just-submitted comments are now real
        // server-side comments, and only a fresh fetch turns their pending
        // markers into real thread markers instead of them just vanishing
        // back to "+".
        commentContext.pendingStore.addListener {
            baseController.render()
            headController.render()
            GiteaProjectCoroutineScope.getInstance(project).coroutineScope.launch {
                val fresh = runCatching { commentContext.fetchFreshComments() }.getOrNull()
                withContext(Dispatchers.Main) {
                    if (fresh != null) {
                        baseController.updateComments(fresh)
                        headController.updateComments(fresh)
                    }
                }
            }
        }
    }
}
