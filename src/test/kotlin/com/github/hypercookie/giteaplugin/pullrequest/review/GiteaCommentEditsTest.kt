package com.github.hypercookie.giteaplugin.pullrequest.review

import junit.framework.TestCase

class GiteaCommentEditsTest : TestCase() {

    fun `test a draft is kept per comment until finished`() {
        val edits = GiteaCommentEdits()
        assertNull(edits.draft(1))

        edits.update(1, "one")
        edits.update(2, "two")
        edits.update(1, "one more")
        assertEquals("one more", edits.draft(1))
        assertEquals("two", edits.draft(2))

        edits.finish(1)
        assertNull(edits.draft(1))
        assertEquals("two", edits.draft(2))
    }
}
