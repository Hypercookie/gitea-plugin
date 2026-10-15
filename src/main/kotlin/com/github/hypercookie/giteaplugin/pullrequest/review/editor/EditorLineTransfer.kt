package com.github.hypercookie.giteaplugin.pullrequest.review.editor

/**
 * Lines of the document a [GiteaReviewEditorModel] maps comments into (one
 * version of the file) vs. lines of the editor that shows its inlays and
 * gutter controls. They are the same everywhere except in the unified diff
 * viewer, whose one editor interleaves the old and new versions.
 *
 * Own replacement for the official plugins' unified-viewer line helpers,
 * which are internal.
 */
interface EditorLineTransfer {

    /** Editor line that shows [documentLine], or null if the editor doesn't show it. */
    fun toEditor(documentLine: Int): Int?

    /** Document line shown at [editorLine], or null if that editor line
     * shows no line of this document (in a unified diff: a line of the
     * other version). */
    fun fromEditor(editorLine: Int): Int?

    /** The editor shows the document itself. */
    object Identity : EditorLineTransfer {
        override fun toEditor(documentLine: Int): Int = documentLine
        override fun fromEditor(editorLine: Int): Int = editorLine
    }

    /** No line maps, e.g. before a unified viewer has laid out its document. */
    object None : EditorLineTransfer {
        override fun toEditor(documentLine: Int): Int? = null
        override fun fromEditor(editorLine: Int): Int? = null
    }

    companion object {
        /**
         * Transfer from [documentLineAt], the document line shown at each
         * editor line (`-1` where the editor line shows none), as returned
         * line by line by `UnifiedDiffViewer.transferLineFromOnesideStrict`.
         */
        fun of(documentLineAt: IntArray): EditorLineTransfer {
            val editorLineOf = IntArray((documentLineAt.maxOrNull() ?: -1) + 1) { -1 }
            documentLineAt.forEachIndexed { editorLine, documentLine ->
                if (documentLine >= 0 && editorLineOf[documentLine] < 0) editorLineOf[documentLine] = editorLine
            }
            return object : EditorLineTransfer {
                override fun toEditor(documentLine: Int): Int? = editorLineOf.getOrNull(documentLine)?.takeIf { it >= 0 }
                override fun fromEditor(editorLine: Int): Int? = documentLineAt.getOrNull(editorLine)?.takeIf { it >= 0 }
            }
        }
    }
}
