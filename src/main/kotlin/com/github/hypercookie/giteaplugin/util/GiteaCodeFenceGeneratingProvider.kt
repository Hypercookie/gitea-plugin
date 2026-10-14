// Mirrors CodeFenceSyntaxHighlighterGeneratingProvider from IntelliJ IDEA
// Community Edition (platform/markdown-utils, build 252.28539.54), which is
// @ApiStatus.Internal. Copyright 2000-2021 JetBrains s.r.o. and contributors,
// licensed under the Apache License, Version 2.0. Modified: tolerates an
// unterminated fence at the end of the input and uses only the first word of
// the info string as the language.
package com.github.hypercookie.giteaplugin.util

import com.intellij.markdown.utils.lang.HtmlSyntaxHighlighter
import com.intellij.openapi.util.text.StringUtil
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator

/** Renders a fenced code block as `<pre><code>` with the content colored by
 * [highlighter] for the fence's language. */
internal class GiteaCodeFenceGeneratingProvider(
    private val highlighter: HtmlSyntaxHighlighter,
) : GeneratingProvider {

    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        var language: String? = null
        val content = StringBuilder()

        // The line break that ends the opening fence line is not content.
        var skipNextEol = false
        for (child in node.children) {
            when (child.type) {
                MarkdownTokenTypes.CODE_FENCE_START -> skipNextEol = true
                MarkdownTokenTypes.FENCE_LANG ->
                    language = HtmlGenerator.leafText(text, child).toString().trim()
                        .split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() }
                MarkdownTokenTypes.EOL ->
                    if (skipNextEol) skipNextEol = false
                    else content.append(child.getTextInNode(text))
                MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                    skipNextEol = false
                    content.append(HtmlGenerator.trimIndents(child.getTextInNode(text), 0))
                }
                MarkdownTokenTypes.CODE_FENCE_END -> break
            }
        }

        val colored = highlighter.color(language, content.toString().removeSuffix("\n"))
        // Built by hand rather than with HtmlChunk.wrapWith so an empty block
        // stays `<code></code>`; a self-closing `<code/>` confuses Swing's parser.
        val classAttribute = language?.let { " class=\"language-${StringUtil.escapeXmlEntities(it)}\"" }.orEmpty()
        visitor.consumeHtml("<pre><code$classAttribute>$colored</code></pre>")
    }
}
