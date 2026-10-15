package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.ChangedFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PullRequestBaseTest {

    @Test
    fun addedFileHasEmptyBase() {
        assertEquals(PullRequestBase.Added, PullRequestBase.of(ChangedFile("src/New.kt", "added"), "src/New.kt"))
    }

    @Test
    fun renamedFileUsesPreviousPath() {
        val file = ChangedFile("src/moved/Foo.kt", "renamed", previousFilename = "src/Foo.kt")
        assertEquals(PullRequestBase.At("src/Foo.kt"), PullRequestBase.of(file, "src/moved/Foo.kt"))
    }

    @Test
    fun modifiedFileUsesItsOwnPath() {
        assertEquals(PullRequestBase.At("a.txt"), PullRequestBase.of(ChangedFile("a.txt", "modified"), "a.txt"))
    }

    @Test
    fun unlistedFileIsNotAssumedAdded() {
        // Not in the changed-file list, or the list failed to load.
        assertEquals(PullRequestBase.At("a.txt"), PullRequestBase.of(null, "a.txt"))
    }
}
