package com.github.hypercookie.giteaplugin.pullrequest.review

/**
 * Line ranges for review comments. Gitea anchors a review comment to one
 * line and its API has no field for a range, so the range travels inside the
 * comment body as a closed HTML comment, `<!--gitea-plugin:lines=3-5-->`
 * (1-based lines of the comment's side; the comment itself is anchored at the
 * last line). Gitea's Markdown renderer drops it from the rendered comment,
 * so colleagues without the plugin see a normal single-line comment.
 *
 * An *unclosed* tag (`<tag` without `>`) is not hidden: Gitea renders it as
 * literal text, which is why the marker is a complete HTML comment.
 */
data class MultiLineRange(val start: Int, val end: Int) {

    override fun toString(): String = "$start-$end"

    companion object {
        private val MARKER = Regex("""<!--gitea-plugin:lines=(\d+)-(\d+)-->""")

        /** [body] without its range marker, and the range it carried, if any. */
        data class Parsed(val text: String, val range: MultiLineRange?)

        /** Parses regardless of the "multi-line comments" setting, so ranges written by others are shown. */
        fun parse(body: String): Parsed {
            val match = MARKER.findAll(body).lastOrNull() ?: return Parsed(body, null)
            val start = match.groupValues[1].toIntOrNull()
            val end = match.groupValues[2].toIntOrNull()
            val text = body.replace(MARKER, "").trimEnd()
            val range = if (start != null && end != null && start in 1 until end) MultiLineRange(start, end) else null
            return Parsed(text, range)
        }

        /** [body] with the marker for [range] appended; [body] unchanged for null. */
        fun append(body: String, range: MultiLineRange?): String =
            if (range == null) body else "${body.trimEnd()}\n\n<!--gitea-plugin:lines=$range-->"
    }
}
