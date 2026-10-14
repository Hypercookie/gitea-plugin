package com.github.hypercookie.giteaplugin.pullrequest.review.mention

import com.github.hypercookie.giteaplugin.api.dto.GiteaUser
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class GiteaMentionCompletionTest : BasePlatformTestCase() {

    private fun user(id: Long, login: String, fullName: String? = null) = GiteaUser(id = id, login = login, fullName = fullName)

    private val source = GiteaMentionSource {
        GiteaMentions.merge(
            listOf(
                GiteaMentionCandidate(user(1, "alice", "Alice Example"), 15.0),
                GiteaMentionCandidate(user(2, "albert"), 1.0),
                GiteaMentionCandidate(user(3, "bob"), 1.0),
            ),
            excludeLogin = null,
        )
    }

    private fun configure(text: String) {
        myFixture.configureByText(PlainTextFileType.INSTANCE, text)
        GiteaMentionSource.install(myFixture.editor, source)
    }

    fun testSuggestsMatchingUsers() {
        configure("Thanks @al<caret>")
        val lookups = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertEquals(listOf("@alice", "@albert"), lookups)
    }

    fun testInsertsLoginAndSpace() {
        configure("ping @bo<caret>")
        myFixture.completeBasic()
        myFixture.checkResult("ping @bob <caret>")
    }

    fun testNothingWithoutSource() {
        myFixture.configureByText(PlainTextFileType.INSTANCE, "ping @bo<caret>")
        val lookups = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(lookups.contains("@bob"))
    }

    fun testNoMentionInsideEmailAddress() {
        configure("mail me@al<caret>")
        val lookups = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertFalse(lookups.any { it.startsWith("@") })
    }

    fun testMentionPrefix() {
        assertEquals("@al", GiteaMentions.mentionPrefix("hi @al"))
        assertEquals("@", GiteaMentions.mentionPrefix("hi @"))
        assertNull(GiteaMentions.mentionPrefix("hi al"))
        assertNull(GiteaMentions.mentionPrefix("me@al"))
        assertNull(GiteaMentions.mentionPrefix("hi @al "))
    }

    fun testMergeKeepsHighestPriorityAndExcludesSelf() {
        val merged = GiteaMentions.merge(
            listOf(
                GiteaMentionCandidate(user(1, "alice"), 1.0),
                GiteaMentionCandidate(user(1, "alice"), 10.0),
                GiteaMentionCandidate(user(2, "me"), 15.0),
                GiteaMentionCandidate(user(3, "bob"), 1.0),
            ),
            excludeLogin = "me",
        )
        assertEquals(listOf("alice" to 10.0, "bob" to 1.0), merged.map { it.user.login to it.priority })
    }
}
