package com.github.hypercookie.giteaplugin.pullrequest.review

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.TimeUnit

/** [LocalGitFiles] against a real repository made with the git command line. */
class LocalGitFilesTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private lateinit var files: LocalGitFiles
    private lateinit var head: String

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("gitea-local-files", null)
        git("init", "-q")
        File(dir, "src").mkdirs()
        File(dir, "src/a.txt").writeText("first\r\nsecond\n")
        git("add", ".")
        git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "init")
        head = git("rev-parse", "HEAD").trim()
        // Working tree changes must not leak into reads of a commit.
        File(dir, "src/a.txt").writeText("changed\n")
        val root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        files = LocalGitFiles(project, root)
    }

    override fun tearDown() {
        try {
            FileUtil.delete(dir)
        } finally {
            super.tearDown()
        }
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(30, TimeUnit.SECONDS)
        check(process.exitValue() == 0) { output }
        return output
    }

    fun testReadsCommittedBytes() = runBlocking {
        assertEquals(LocalGitFiles.Read.Found("first\r\nsecond\n"), files.read("src/a.txt", head))
    }

    fun testMissingPathInLocalCommit() = runBlocking {
        assertEquals(LocalGitFiles.Read.Missing, files.read("src/none.txt", head))
    }

    fun testUnknownCommitIsLeftToTheApi() = runBlocking {
        assertEquals(LocalGitFiles.Read.Unavailable, files.read("src/a.txt", "0123456789abcdef0123456789abcdef01234567"))
    }

    fun testNonCommitIdRefIsLeftToTheApi() = runBlocking {
        assertEquals(LocalGitFiles.Read.Unavailable, files.read("src/a.txt", "main"))
        assertEquals(LocalGitFiles.Read.Unavailable, files.read("src/a.txt", head.take(12)))
    }

    fun testDirectoryIsLeftToTheApi() = runBlocking {
        assertEquals(LocalGitFiles.Read.Unavailable, files.read("src", head))
    }
}
