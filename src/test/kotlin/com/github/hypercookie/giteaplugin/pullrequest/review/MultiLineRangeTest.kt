package com.github.hypercookie.giteaplugin.pullrequest.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultiLineRangeTest {

    @Test
    fun `append then parse round trips`() {
        val body = MultiLineRange.append("Please extract this.\n", MultiLineRange(3, 5))
        assertEquals("Please extract this.\n\n<!--gitea-plugin:lines=3-5-->", body)
        val parsed = MultiLineRange.parse(body)
        assertEquals("Please extract this.", parsed.text)
        assertEquals(MultiLineRange(3, 5), parsed.range)
    }

    @Test
    fun `no range leaves the body alone`() {
        assertEquals("hi", MultiLineRange.append("hi", null))
        val parsed = MultiLineRange.parse("hi <!-- other comment -->")
        assertEquals("hi <!-- other comment -->", parsed.text)
        assertNull(parsed.range)
    }

    @Test
    fun `invalid ranges are stripped but ignored`() {
        val parsed = MultiLineRange.parse("x\n<!--gitea-plugin:lines=5-3-->")
        assertEquals("x", parsed.text)
        assertNull(parsed.range)
    }

    @Test
    fun `unclosed marker is not a marker`() {
        val parsed = MultiLineRange.parse("x <!--gitea-plugin:lines=3-5")
        assertNull(parsed.range)
    }
}
