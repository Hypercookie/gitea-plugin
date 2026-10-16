package com.github.hypercookie.giteaplugin.util

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Runs inside the platform so fence highlighting can resolve languages and
 * the editor color scheme. XML and HTML are the languages available in the
 * test runtime. */
class GiteaMarkdownTest : BasePlatformTestCase() {

    fun testHeadingsListsAndQuotes() {
        val html = GiteaMarkdown.toHtml("# Title\n\n## Sub\n\n- a\n- b\n\n1. x\n\n> quote")
        assertTrue(html, html.contains("<h1>Title</h1>"))
        assertTrue(html, html.contains("<h2>Sub</h2>"))
        assertTrue(html, html.contains("<ul><li>a</li><li>b</li></ul>"))
        assertTrue(html, html.contains("<ol><li>x</li></ol>"))
        assertTrue(html, html.contains("<blockquote><p>quote</p></blockquote>"))
        assertFalse(html, html.contains("<body>"))
    }

    fun testInlineFormatting() {
        val html = GiteaMarkdown.toHtml("`a <b>` **bold** ~~gone~~ [link](https://example.com)")
        assertTrue(html, html.contains("<code>a &lt;b&gt;</code>"))
        assertTrue(html, html.contains("<strong>bold</strong>"))
        assertTrue(html, html.contains("<strike>gone</strike>"))
        assertTrue(html, html.contains("<a href=\"https://example.com\">link</a>"))
    }

    fun testFencedCodeWithLanguageIsHighlighted() {
        val html = GiteaMarkdown.toHtml("```xml\n<a href=\"x\">t</a>\n```")
        assertTrue(html, html.startsWith("<pre><code class=\"language-xml\">"))
        assertTrue(html, html.contains("<span style=\"color:"))
        assertTrue(html, html.endsWith("</code></pre>"))
        assertFalse(html, html.contains("<a href"))
    }

    fun testFencedCodeLanguageResolvedByExtension() {
        val html = GiteaMarkdown.toHtml("```htm\n<p class=\"c\">x</p>\n```")
        assertTrue(html, html.contains("<span style=\"color:"))
    }

    fun testFencedCodeWithoutOrWithUnknownLanguageIsEscapedPlainText() {
        assertEquals("<pre><code>plain &lt;b&gt;</code></pre>", GiteaMarkdown.toHtml("```\nplain <b>\n```"))
        assertEquals(
            "<pre><code class=\"language-nosuchlang\">x &lt; y</code></pre>",
            GiteaMarkdown.toHtml("```nosuchlang\nx < y\n```"),
        )
    }

    fun testUnterminatedFenceAtEndOfInput() {
        assertEquals("<pre><code class=\"language-xml\"></code></pre>", GiteaMarkdown.toHtml("```xml"))
    }

    fun testFenceLanguageCannotInjectMarkup() {
        val html = GiteaMarkdown.toHtml("```\"><script>\nx\n```")
        assertFalse(html, html.contains("<script>"))
        assertFalse(html, html.contains("\"><"))
    }

    fun testCrlfLineEndings() {
        val html = GiteaMarkdown.toHtml("# T\r\n\r\n```xml\r\n<a/>\r\n```\r\n")
        assertFalse(html, html.contains("\r"))
        assertTrue(html, html.contains("<h1>T</h1>"))
        assertTrue(html, html.contains("<pre><code class=\"language-xml\">"))
    }

    fun testRawHtmlIsEscaped() {
        assertEquals("<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>", GiteaMarkdown.toHtml("<script>alert(1)</script>"))
        assertEquals("<p>a &lt;b onmouseover=x&gt;b&lt;/b&gt;</p>", GiteaMarkdown.toHtml("a <b onmouseover=x>b</b>"))
        val block = GiteaMarkdown.toHtml("<div>\nblock\n</div>")
        assertFalse(block, block.contains("<div"))
        assertTrue(block, block.contains("&lt;div&gt;"))
    }

    fun testImagesAreNotLoaded() {
        assertEquals("<p>[image: x]</p>", GiteaMarkdown.toHtml("![x](http://evil.example/a.png)"))
        assertEquals("<p>[image: y]</p>", GiteaMarkdown.toHtml("![y][r]\n\n[r]: http://evil.example/b.png"))
        assertFalse(GiteaMarkdown.toHtml("![](http://evil.example/c.png)").contains("evil"))
    }

    fun testUnsafeLinkSchemesAreNeutralized() {
        assertFalse(GiteaMarkdown.toHtml("[js](javascript:alert(1))").contains("javascript:"))
    }

    fun testCreatePane() {
        val pane = GiteaMarkdown.createPane(GiteaMarkdown.toHtml("`x`"))
        assertTrue(pane.text, pane.text.contains("<code>"))
    }

    fun testToSegmentsCutsOutTopLevelCodeBlocks() {
        val segments = GiteaMarkdown.toSegments("Before\n\n```xml\n<a/>\n```\n\n- item\n\n  ```\n  nested\n  ```\n\nAfter")
        assertEquals(3, segments.size)
        assertEquals("<p>Before</p>", (segments[0] as GiteaMarkdown.Segment.Html).html)
        val block = (segments[1] as GiteaMarkdown.Segment.Code).block
        assertEquals("xml", block.language)
        assertEquals("<a/>", block.code)
        val rest = (segments[2] as GiteaMarkdown.Segment.Html).html
        assertTrue(rest, rest.contains("nested") && rest.contains("<pre>") && rest.endsWith("<p>After</p>"))
    }

    fun testCreateBodyWithoutCodeIsOnePane() {
        assertTrue(GiteaMarkdown.createBody("just *text*") is javax.swing.JEditorPane)
        val body = GiteaMarkdown.createBody("text\n\n```\ncode\n```")
        assertNotNull(com.intellij.util.ui.UIUtil.findComponentOfType(body, GiteaCodeBlockComponent::class.java))
    }
}
