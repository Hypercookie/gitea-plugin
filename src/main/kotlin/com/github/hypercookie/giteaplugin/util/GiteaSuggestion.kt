package com.github.hypercookie.giteaplugin.util

import java.security.MessageDigest

/**
 * A code suggestion in a review comment. Gitea has no API for suggestions,
 * so everything the plugin needs travels in the comment body itself: the
 * suggested code is a visible ```` ```suggestion ```` fence (readable in the
 * Gitea web UI), and a hidden marker at the very end of the body says how
 * many lines it replaces and which code it was written against.
 *
 * The marker is a closed HTML comment with the same `gitea-plugin:` prefix
 * as the multi-line range marker, `<!--gitea-plugin:suggest=2,0a1b2c3d4e5f-->`
 * (lines replaced, hash of the original). Gitea's Markdown renderer drops
 * it, so colleagues without the plugin just see the suggestion block. An
 * unclosed tag would be shown as literal text. Parsing ignores where in the body it sits.
 *
 * @property lines how many lines, ending at the commented line, [code] replaces.
 *   A multi-line comment is anchored at its last line, so its whole range.
 *   0 when unknown (no marker): the comment's own line or range.
 * @property originalHash [hashOf] the lines it replaces, to refuse applying
 *   it once they have changed; empty when unknown (no marker).
 */
data class GiteaSuggestion(val code: String, val lines: Int, val originalHash: String) {

    /** The marker to append to a comment body. */
    fun marker(): String = "<!--gitea-plugin:suggest=$lines,$originalHash-->"

    companion object {
        private val MARKER = Regex("""<!--gitea-plugin:suggest=(\d{1,4}),([0-9a-f]{12})-->""")
        private val FENCE = Regex("""```suggestion[^\n]*\n(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)

        /** Hash of [lines] ignoring trailing whitespace and line separator style. */
        fun hashOf(lines: List<String>): String {
            val text = lines.joinToString("\n") { it.trimEnd() }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
        }

        /** [body] with its hidden marker, if any, cut off. */
        fun stripMarker(body: String): String = MARKER.replace(body, "").trimEnd()

        /** The suggestion [body] carries: its marker plus the code of its
         * first ```` ```suggestion ```` fence; null when either is missing. */
        fun parse(body: String): GiteaSuggestion? {
            val marker = MARKER.find(body) ?: return null
            val code = FENCE.find(body.replace("\r\n", "\n"))?.groupValues?.get(1) ?: return null
            val lines = marker.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return GiteaSuggestion(code, lines, marker.groupValues[2])
        }

        /**
         * Like [parse], and a plain ```` ```suggestion ```` fence without the
         * marker (typed in the Gitea web UI, or by someone without the plugin)
         * counts too: it has no [GiteaSuggestion.lines] (0, meaning the comment's
         * own line or range) and no hash, so it can't be checked against the code.
         */
        fun parseOrPlain(body: String): GiteaSuggestion? = parse(body)
            ?: FENCE.find(body.replace("\r\n", "\n"))?.groupValues?.get(1)?.let { GiteaSuggestion(it, 0, "") }

        /** The text to start a suggestion comment with: a suggestion fence holding [original]. */
        fun template(original: List<String>): String =
            "```suggestion\n${original.joinToString("\n")}\n```\n"

        /**
         * [body] with the hidden marker of a suggestion added, when it has a
         * suggestion fence; [original] are the lines it is written against.
         * Otherwise [body] unchanged.
         */
        fun withMarker(body: String, original: List<String>): String {
            if (original.isEmpty()) return body
            val code = FENCE.find(body.replace("\r\n", "\n"))?.groupValues?.get(1) ?: return body
            val clean = stripMarker(body).trimEnd()
            return "$clean\n${GiteaSuggestion(code, original.size, hashOf(original)).marker()}"
        }
    }
}
