package com.github.hypercookie.giteaplugin.util

import junit.framework.TestCase

class GiteaSuggestionTest : TestCase() {

    fun testRoundTrip() {
        val original = listOf("val a = 1", "val b = 2")
        val body = GiteaSuggestion.withMarker("Rename:\n\n" + GiteaSuggestion.template(original).replace("val a = 1", "val x = 1"), original)
        val s = GiteaSuggestion.parse(body)!!
        assertEquals("val x = 1\nval b = 2", s.code)
        assertEquals(2, s.lines)
        assertEquals(GiteaSuggestion.hashOf(original), s.originalHash)
        assertFalse(GiteaSuggestion.stripMarker(body).contains("gitea-plugin:suggest"))
        assertTrue(GiteaSuggestion.stripMarker(body).contains("```suggestion"))
    }

    fun testMarkerIsAClosedCommentAtTheEnd() {
        val body = GiteaSuggestion.withMarker("```suggestion\nx\n```", listOf("y"))
        assertTrue(body.trimEnd().endsWith("-->"))
    }

    fun testNoMarkerNoSuggestion() {
        assertNull(GiteaSuggestion.parse("```suggestion\nx\n```"))
        assertEquals("plain", GiteaSuggestion.withMarker("plain", listOf("y")))
    }

    fun testHashIgnoresTrailingWhitespace() {
        assertEquals(GiteaSuggestion.hashOf(listOf("a  ")), GiteaSuggestion.hashOf(listOf("a")))
        assertFalse(GiteaSuggestion.hashOf(listOf("a")) == GiteaSuggestion.hashOf(listOf("b")))
    }
}
