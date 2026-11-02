package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.util.GiteaSuggestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GiteaHiddenMarkersTest {

    private val original = listOf("val a = 1", "val b = 2")
    private val body = MultiLineRange.append(
        GiteaSuggestion.withMarker("Simplify:\n\n```suggestion\nval a = 1; val b = 2\n```", original),
        MultiLineRange(4, 5),
    )

    @Test
    fun `multi-line suggestion carries both markers`() {
        val parsed = MultiLineRange.parse(body)
        assertEquals(MultiLineRange(4, 5), parsed.range)
        val suggestion = GiteaSuggestion.parse(parsed.text)!!
        assertEquals(2, suggestion.lines)
        assertEquals(GiteaSuggestion.hashOf(original), suggestion.originalHash)
        assertEquals("val a = 1; val b = 2", suggestion.code)
    }

    @Test
    fun `edit text hides all markers and saving puts them back`() {
        val (text, markers) = GiteaHiddenMarkers.split(body)
        assertFalse("<!--" in text)
        assertEquals(2, markers.size)
        val saved = GiteaHiddenMarkers.join(text.replace("Simplify", "Shorter"), markers)
        assertEquals(MultiLineRange(4, 5), MultiLineRange.parse(saved).range)
        assertEquals(2, GiteaSuggestion.parse(saved)!!.lines)
    }

    @Test
    fun `no markers leaves text alone`() {
        assertEquals("plain" to emptyList<String>(), GiteaHiddenMarkers.split("plain"))
        assertEquals("plain", GiteaHiddenMarkers.join("plain", emptyList()))
    }
}
