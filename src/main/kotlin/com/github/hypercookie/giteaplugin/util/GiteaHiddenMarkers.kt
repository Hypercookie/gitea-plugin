package com.github.hypercookie.giteaplugin.util

/**
 * The hidden markers the plugin stores in comment bodies, closed HTML
 * comments that start with `<!--gitea-plugin:` (the multi-line range, a code
 * suggestion). Gitea's renderer drops them. Editing a comment shows its text
 * without them ([split]) and puts them back when it is saved ([attach]),
 * so an author never sees or breaks them.
 */
object GiteaHiddenMarkers {

    private val MARKER = Regex("""<!--gitea-plugin:.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** [text] without any marker, and the [markers] it carried, in order. */
    class Split(val text: String, val markers: List<String>)

    fun split(body: String): Split {
        val markers = MARKER.findAll(body).map { it.value }.toList()
        if (markers.isEmpty()) return Split(body, emptyList())
        return Split(MARKER.replace(body, "").trimEnd(), markers)
    }

    /** [text] with [markers] appended on a line of their own; [text] unchanged without markers. */
    fun attach(text: String, markers: List<String>): String =
        if (markers.isEmpty()) text else "${text.trimEnd()}\n\n${markers.joinToString("\n")}"
}
