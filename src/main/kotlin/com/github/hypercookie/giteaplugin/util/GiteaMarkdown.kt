package com.github.hypercookie.giteaplugin.util

import com.intellij.collaboration.ui.SimpleHtmlPane
import com.intellij.collaboration.ui.setHtmlBody
import com.intellij.lang.Language
import com.intellij.markdown.utils.lang.HtmlSyntaxHighlighter
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
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
import java.net.URI
import javax.swing.JEditorPane

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
    fun toHtml(markdown: String): String {
        // Gitea bodies written in the web UI use CRLF line endings, which the
        // parser does not handle.
        val text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val flavour = GiteaFlavourDescriptor()
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(text)
        val providers = flavour.createHtmlGeneratingProviders(LinkMap.buildLinkMap(tree, text), null)
        // The generator wraps its output in <body>, which the panes add themselves.
        return HtmlGenerator(text, tree, providers, false).generateHtml()
            .removePrefix("<body>").removeSuffix("</body>")
            .replace(Regex("<img[^>]*>", RegexOption.IGNORE_CASE), "[image]")
    }

    /** A read-only pane showing [html] (output of [toHtml]) with code spans
     * and code blocks in the editor font. */
    fun createPane(html: String): JEditorPane =
        SimpleHtmlPane(additionalStyleSheet = StyleSheetUtil.loadStyleSheet(styleSheetRules())).apply { setHtmlBody(html) }

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
private class GiteaFlavourDescriptor : GFMFlavourDescriptor() {
    override fun createHtmlGeneratingProviders(linkMap: LinkMap, baseURI: URI?): Map<IElementType, GeneratingProvider> =
        super.createHtmlGeneratingProviders(linkMap, baseURI) + mapOf(
            GFMElementTypes.STRIKETHROUGH to SimpleInlineTagProvider("strike", 2, -2),
            MarkdownElementTypes.CODE_FENCE to GiteaCodeFenceGeneratingProvider(CodeFenceHighlighter),
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
        val lang = language?.let(::findLanguage)
            ?: return HtmlChunk.raw(StringUtil.escapeXmlEntities(rawContent))
        return HtmlSyntaxHighlighter.colorHtmlChunk(null, lang, rawContent)
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
