package com.github.hypercookie.giteaplugin.util

import junit.framework.TestCase

class GiteaHiddenMarkersTest : TestCase() {

    fun testSplitAndAttachRoundTrip() {
        val body = "Text\n\n```suggestion\nx\n```\n\n<!--gitea-plugin:lines=3-5-->\n<!--gitea-plugin:suggest=3,0a1b2c3d4e5f-->"
        val split = GiteaHiddenMarkers.split(body)
        assertEquals("Text\n\n```suggestion\nx\n```", split.text)
        assertEquals(listOf("<!--gitea-plugin:lines=3-5-->", "<!--gitea-plugin:suggest=3,0a1b2c3d4e5f-->"), split.markers)
        assertEquals(body, GiteaHiddenMarkers.attach(split.text, split.markers))
    }

    fun testEditedTextKeepsMarkers() {
        val split = GiteaHiddenMarkers.split("old\n\n<!--gitea-plugin:lines=1-2-->")
        assertEquals("new\n\n<!--gitea-plugin:lines=1-2-->", GiteaHiddenMarkers.attach("new", split.markers))
    }

    fun testOtherHtmlCommentsAndPlainTextAreKept() {
        val body = "a <!-- note --> b"
        val split = GiteaHiddenMarkers.split(body)
        assertEquals(body, split.text)
        assertTrue(split.markers.isEmpty())
        assertEquals(body, GiteaHiddenMarkers.attach(split.text, split.markers))
    }
}
