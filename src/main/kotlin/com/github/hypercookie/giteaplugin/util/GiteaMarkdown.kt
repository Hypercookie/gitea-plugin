package com.github.hypercookie.giteaplugin.util

import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** Gitea comment and description bodies (Markdown) to HTML for Swing panes. */
object GiteaMarkdown {
    private val parser = Parser.builder().build()
    private val renderer = HtmlRenderer.builder().escapeHtml(true).build()

    /** `escapeHtml(true)` escapes any literal HTML a commenter typed, so a
     * comment body can't inject markup. `<img>` tags are stripped from the
     * output afterward regardless — Swing's HTML view fetches `<img src>`
     * eagerly and unconditionally, so a comment's Markdown image syntax
     * would otherwise make the IDE fetch an arbitrary attacker-chosen URL
     * just from a reviewer opening the comment. */
    fun toHtml(markdown: String): String =
        renderer.render(parser.parse(markdown)).replace(Regex("<img[^>]*>"), "[image]")
}
