package com.github.hypercookie.giteaplugin.util

import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.setHtmlBody
import com.intellij.lang.Language
import com.intellij.markdown.utils.lang.HtmlSyntaxHighlighter
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.components.panels.ListLayout
import com.intellij.util.ui.StyleSheetUtil
import com.intellij.util.ui.UIUtil
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.GeneratingProvider
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.html.SimpleInlineTagProvider
import org.intellij.markdown.parser.LinkMap
import org.intellij.markdown.parser.MarkdownParser
import java.awt.Color
import java.awt.Font
import java.net.URI
import java.util.UUID
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel

/**
 * Gitea comment and description bodies (GitHub-flavored Markdown) to HTML for
 * Swing panes, rendered the way the bundled GitHub plugin renders comments:
 * the platform's bundled `org.intellij.markdown` parser with the GFM flavour,
 * and fenced code blocks colored with the IDE color scheme for their language.
 */
object GiteaMarkdown {

    /**
     * Literal HTML a commenter typed is escaped, so a comment body can't
     * inject markup. Images are rendered as their alt text and any `<img>`
     * left in the output is stripped as a safety net: Swing's HTML view
     * fetches `<img src>` eagerly and unconditionally, so a comment's
     * Markdown image syntax would otherwise make the IDE fetch an arbitrary
     * attacker-chosen URL just from a reviewer opening the comment.
     */
    fun toHtml(markdown: String): String = render(markdown, null)

    /** A fenced code block: its [code] and that code colored as `<pre><code>` [html]. */
    class CodeBlock(val language: String?, val code: String, val html: String)

    /** A run of [toHtml] output, or a top-level code block cut out of it. */
    sealed interface Segment {
        data class Html(val html: String) : Segment
        class Code(val block: CodeBlock) : Segment
    }

    /** [toHtml] split at its top-level fenced code blocks, so they can be
     * shown as their own components. Blocks inside lists or quotes stay in
     * the HTML. */
    fun toSegments(markdown: String): List<Segment> {
        val blocks = mutableListOf<CodeBlock>()
        // A marker per render that comment text can't contain.
        val marker = "<!--gitea-code-block-${UUID.randomUUID()}-->"
        val parts = render(markdown) { blocks.add(it); marker }.split(marker)
        return buildList {
            parts.forEachIndexed { index, html ->
                if (html.isNotBlank()) add(Segment.Html(html.trim()))
                blocks.getOrNull(index)?.let { add(Segment.Code(it)) }
            }
        }
    }

    private fun render(markdown: String, cutOutCodeBlock: ((CodeBlock) -> String)?): String {
        // Gitea bodies written in the web UI use CRLF line endings, which the
        // parser does not handle.
        val text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val flavour = GiteaFlavourDescriptor(cutOutCodeBlock)
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, text), null)
        // The generator wraps its output in <body>, which the panes add themselves.
        return HtmlGenerator(text, tree, providers, false).generateHtml()
            .removePrefix("<body>").removeSuffix("</body>")
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "[image]")
    }

    /** A read-only pane showing [html] (output of [toHtml]) with code spans
     * and code blocks in the editor font. Links open only when they are
     * http(s) ([GiteaBrowser]). */
    fun createPane(html: String): JEditorPane =
        SimpleHtmlPane(additionalStyleSheet = StyleSheetUtil.loadStyleSheet(styleSheetRules()), addBrowserListener = false).apply {
            addHyperlinkListener(GiteaBrowser.hyperlinkListener)
            setHtmlBody(html)
        }

    /** [markdown] as a column of panes, with each top-level code block a
     * [GiteaCodeBlockComponent] that keeps its lines unwrapped and offers
     * Copy, and [apply] when given. */
    fun createBody(markdown: String, apply: GiteaCodeBlockApply? = null): JComponent {
        val segments = toSegments(markdown)
        if (segments.none { it is Segment.Code }) return createPane(toHtml(markdown))
        return JPanel(ListLayout.vertical(0)).apply {
            isOpaque = false
            for (segment in segments) {
                when (segment) {
                    is Segment.Html -> add(createPane(segment.html))
                    is Segment.Code -> add(GiteaCodeBlockComponent(segment.block, apply))
                }
            }
        }
    }

    /** A pane for a code block's `<pre>` [html]: no margin, as the block's
     * own component spaces and colors it. */
    internal fun createCodePane(html: String): JEditorPane =
        SimpleHtmlPane(
            additionalStyleSheet = StyleSheetUtil.loadStyleSheet(styleSheetRules() + "\npre { margin: 0; padding: 6px; }"),
            addBrowserListener = false,
        ).apply { setHtmlBody(html) }

    internal fun codeBackground(): Color = EditorColorsManager.getInstance().globalScheme.defaultBackground

    private fun styleSheetRules(): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val codeBackground = scheme.defaultBackground
        val quoteForeground = UIUtil.getContextHelpForeground()
        // A custom style sheet replaces the default paragraph rule of
        // HTMLEditorKitBuilder, so it is restated here.
        return """
            p { margin-top: 0; }
            code { font-family: ${scheme.editorFontName}, monospace; }
            pre { font-family: ${scheme.editorFontName}, monospace; margin-top: 4px; margin-bottom: 4px;
                  padding: 6px; background-color: ${toHtmlColor(codeBackground)}; }
            blockquote { margin-left: 0; padding-left: 8px; color: ${toHtmlColor(quoteForeground)}; }
            h1, h2, h3, h4, h5, h6 { margin-top: 6px; margin-bottom: 4px; }
        """.trimIndent()
    }

    private fun toHtmlColor(color: Color): String = ColorUtil.toHtmlColor(color)
}

/** GFM with raw HTML escaped, images reduced to their alt text, strikethrough
 * rendered with a tag Swing understands, and highlighted code fences. */
private class GiteaFlavourDescriptor(
    private val cutOutCodeBlock: ((GiteaMarkdown.CodeBlock) -> String)?,
) : GFMFlavourDescriptor() {
    override fun createHtmlGeneratingProviders(linkMap: LinkMap, baseURI: URI?): Map<IElementType, GeneratingProvider> =
        super.createHtmlGeneratingProviders(linkMap, baseURI) + mapOf(
            GFMElementTypes.STRIKETHROUGH to SimpleInlineTagProvider("strike", 2, -2),
            MarkdownElementTypes.CODE_FENCE to GiteaCodeFenceGeneratingProvider(CodeFenceHighlighter, cutOutCodeBlock),
            MarkdownElementTypes.HTML_BLOCK to EscapedHtmlBlockProvider,
            MarkdownTokenTypes.HTML_TAG to EscapedTextProvider,
            MarkdownElementTypes.IMAGE to ImageAltTextProvider,
        )
}

private object EscapedTextProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        visitor.consumeHtml(StringUtil.escapeXmlEntities(node.getTextInNode(text).toString()))
    }
}

private object EscapedHtmlBlockProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val escaped = StringUtil.escapeXmlEntities(node.getTextInNode(text).toString().trimEnd())
        visitor.consumeHtml("<p>${escaped.replace("\n", "<br>")}</p>")
    }
}

/** Renders `![alt](url)` (and the reference forms) as `[image: alt]`, never
 * as an `<img>` that would load the URL. */
private object ImageAltTextProvider : GeneratingProvider {
    override fun processNode(visitor: HtmlGenerator.HtmlGeneratingVisitor, text: String, node: ASTNode) {
        val link = node.children.firstOrNull { it.type != MarkdownTokenTypes.EXCLAMATION_MARK }
        val alt = link?.findChildOfType(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(text)?.toString()
            ?.removeSurrounding("[", "]")?.trim().orEmpty()
        val label = if (alt.isEmpty()) "[image]" else "[image: $alt]"
        visitor.consumeHtml(StringUtil.escapeXmlEntities(label))
    }
}

/**
 * Colors a fence by the language named in its info string. Matches a
 * registered language id first (as the platform's CodeBlockHtmlSyntaxHighlighter
 * does), then falls back to a file extension so the common short forms
 * (`js`, `py`, `ts`, `sh`, `rs`, `kt`) are highlighted too.
 */
private object CodeFenceHighlighter : HtmlSyntaxHighlighter {
    override fun color(language: String?, rawContent: String): HtmlChunk {
        val escaped = HtmlChunk.raw(StringUtil.escapeXmlEntities(rawContent))
        val lang = language?.let(::findLanguage) ?: return escaped
        val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(lang, null, null) ?: return escaped
        return HtmlChunk.raw(colorWithLexer(highlighter, rawContent))
    }

    /**
     * Colors [rawContent] token by token with the language's highlighting
     * lexer and the global color scheme. The platform's
     * HtmlSyntaxHighlighter.colorHtmlChunk runs a synchronous non-blocking
     * read action, which throws when called on the EDT, and comment panes
     * are built on the EDT. A lexer needs no read action, so this is safe
     * on any thread.
     */
    private fun colorWithLexer(highlighter: SyntaxHighlighter, rawContent: String): String {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val lexer = highlighter.highlightingLexer
        lexer.start(rawContent)
        val html = StringBuilder()
        while (true) {
            val tokenType = lexer.tokenType ?: break
            val token = StringUtil.escapeXmlEntities(rawContent.substring(lexer.tokenStart, lexer.tokenEnd))
            var foreground: Color? = null
            var fontType = Font.PLAIN
            for (key in highlighter.getTokenHighlights(tokenType)) {
                val attributes = scheme.getAttributes(key) ?: continue
                attributes.foregroundColor?.let { foreground = it }
                if (attributes.fontType != Font.PLAIN) fontType = attributes.fontType
            }
            var styled = token
            if (fontType and Font.BOLD != 0) styled = "<b>$styled</b>"
            if (fontType and Font.ITALIC != 0) styled = "<i>$styled</i>"
            foreground?.let { styled = "<span style=\"color: ${ColorUtil.toHtmlColor(it)}\">$styled</span>" }
            html.append(styled)
            lexer.advance()
        }
        return html.toString()
    }

    private fun findLanguage(name: String): Language? {
        val lower = name.lowercase()
        Language.getRegisteredLanguages().firstOrNull { it.id.lowercase() == lower }
            ?.takeIf { it != PlainTextLanguage.INSTANCE }
            ?.let { return it }
        val fileType = FileTypeManager.getInstance().getFileTypeByExtension(lower) as? LanguageFileType ?: return null
        return fileType.language.takeIf { it != PlainTextLanguage.INSTANCE }
    }
}
