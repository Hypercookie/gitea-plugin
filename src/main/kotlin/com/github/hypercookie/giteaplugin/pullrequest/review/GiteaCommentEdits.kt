package com.github.hypercookie.giteaplugin.pullrequest.review

import java.util.concurrent.ConcurrentHashMap

/**
 * The comments being edited and the text typed into them so far.
 *
 * Comments are drawn again whenever the pull request or its comments
 * change, e.g. when a colleague comments meanwhile; the editor of a comment
 * is then built anew, and takes up its [draft] where the old one left off.
 */
internal class GiteaCommentEdits {

    private val drafts = ConcurrentHashMap<Long, String>()

    /** The unsaved text of the comment [commentId], or null if it isn't being edited. */
    fun draft(commentId: Long): String? = drafts[commentId]

    /** Records that [commentId] is being edited, with [text] in its editor. */
    fun update(commentId: Long, text: String) {
        drafts[commentId] = text
    }

    /** [commentId] is no longer being edited: saved or given up. */
    fun finish(commentId: Long) {
        drafts.remove(commentId)
    }
}
