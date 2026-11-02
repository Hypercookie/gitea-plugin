package com.github.hypercookie.giteaplugin.util

import java.security.MessageDigest

/**
 * A code suggestion in a review comment. Gitea has no API for suggestions,
 * so everything the plugin needs travels in the comment body itself: the
 * suggested code is a visible ```` ```suggestion ```` fence (readable in the
 * Gitea web UI), and a hidden marker at the very end of the body says how
 * many lines it replaces and which code it was written against.
 *
 * The marker is an empty, closed custom HTML element,
 * `<gitea-plugin-suggestion v="1" n=".." h=".."></gitea-plugin-suggestion>`:
 * Gitea's renderer does not show an element it does not know, its attributes
 * carry the data, and as it is closed it hides nothing after it. It is
 * written as the last thing in the body.
 *
 * @property lines how many lines, starting at the commented line, [code] replaces.
 * @property originalHash [hashOf] the lines it replaces, to refuse applying
 *   it once they have changed.
 */
data class GiteaSuggestion(val code: String, val lines: Int, val originalHash: String) {

    /** The marker to append to a comment body. */
    fun marker(): String = "$MARKER_START v=\"1\" n=\"$lines\" h=\"$originalHash\"></gitea-plugin-suggestion>"

    companion object {
        private const val MARKER_START = "<gitea-plugin-suggestion"
        private val MARKER = Regex("""\s*<gitea-plugin-suggestion v="1" n="(\d{1,4})" h="([0-9a-f]{12})"></gitea-plugin-suggestion>\s*$""")
        private val FENCE = Regex("""```suggestion[^\n]*\n(.*?)\n?```""", RegexOption.DOT_MATCHES_ALL)

        /** Hash of [lines] ignoring trailing whitespace and line separator style. */
        fun hashOf(lines: List<String>): String {
            val text = lines.joinToString("\n") { it.trimEnd() }
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
                .take(6).joinToString("") { "%02x".format(it) }
        }

        /** [body] with its hidden marker, if any, cut off. */
        fun stripMarker(body: String): String = MARKER.replace(body, "")

        /** The suggestion [body] carries: its marker plus the code of its
         * first ```` ```suggestion ```` fence; null when either is missing. */
        fun parse(body: String): GiteaSuggestion? {
            val marker = MARKER.find(body) ?: return null
            val code = FENCE.find(body.replace("\r\n", "\n"))?.groupValues?.get(1) ?: return null
            val lines = marker.groupValues[1].toIntOrNull()?.takeIf { it > 0 } ?: return null
            return GiteaSuggestion(code, lines, marker.groupValues[2])
        }

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
