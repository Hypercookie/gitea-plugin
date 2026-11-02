package com.github.hypercookie.giteaplugin.pullrequest.review.editor

import com.github.hypercookie.giteaplugin.pullrequest.review.MultiLineRange
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.ActiveGutterRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent

/**
 * Marks the lines a multi-line comment applies to: a faint background on
 * the range and a bar in the gutter, like a selection that stays. Covers
 * posted threads, pending comments and a new comment being written on a
 * selection. Single-line comments get no highlight.
 */
internal object GiteaRangeHighlights {

    private val BACKGROUND = JBColor(Color(0x3574F0, false).let { Color(it.red, it.green, it.blue, 28) }, Color(0x548AF7, false).let { Color(it.red, it.green, it.blue, 36) })
    private val BAR = JBColor(Color(0x3574F0), Color(0x548AF7))

    /** Lines above its anchor that [inlay]'s comment covers; 0 for a single line. */
    fun extraLines(inlay: GiteaEditorInlay): Int = when (inlay) {
        is GiteaEditorInlay.Thread -> MultiLineRange.parse(inlay.vm.thread.value.comments.first().body).range?.let { it.end - it.start } ?: 0
        is GiteaEditorInlay.Pending -> MultiLineRange.parse(inlay.comment.body).range?.let { it.end - it.start } ?: 0
        is GiteaEditorInlay.NewComment -> inlay.vm.startLine?.let { inlay.vm.line - it }?.coerceAtLeast(0) ?: 0
    }

    /** Keeps the highlights of [inlays] in [editor] up to date until [cs] ends; call on the EDT. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun install(cs: CoroutineScope, editor: Editor, inlays: StateFlow<Collection<GiteaEditorInlay>>) {
        val shown = mutableListOf<RangeHighlighter>()
        fun clear() {
            shown.forEach { it.dispose() }
            shown.clear()
        }
        cs.launch {
            try {
                inlays.flatMapLatest { all ->
                    val multi = all.filter { extraLines(it) > 0 }
                    if (multi.isEmpty()) flowOf(emptyList())
                    else combine(multi.map { inlay -> combine(inlay.line, inlay.isVisible) { line, visible -> Triple(inlay, line, visible) } }) { it.toList() }
                }.collect { states ->
                    clear()
                    val document = editor.document
                    for ((inlay, line, visible) in states) {
                        if (line == null || !visible || line >= document.lineCount) continue
                        val first = (line - extraLines(inlay)).coerceAtLeast(0)
                        shown += editor.markupModel.addRangeHighlighter(
                            document.getLineStartOffset(first),
                            document.getLineEndOffset(line),
                            HighlighterLayer.SELECTION - 1,
                            TextAttributes().apply { backgroundColor = BACKGROUND },
                            HighlighterTargetArea.LINES_IN_RANGE,
                        ).apply { lineMarkerRenderer = Bar }
                    }
                }
            } finally {
                clear()
            }
        }
    }

    private object Bar : LineMarkerRendererEx, ActiveGutterRenderer {
        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = BAR
            g.fillRect(r.x, r.y, JBUI.scale(3), r.height)
        }

        override fun getPosition(): LineMarkerRendererEx.Position = LineMarkerRendererEx.Position.LEFT
        override fun canDoAction(e: MouseEvent): Boolean = false
        override fun doAction(editor: Editor, e: MouseEvent) = Unit
    }
}
