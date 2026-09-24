package com.github.hypercookie.giteaplugin.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GiteaUrlUtilTest {

    @Test
    fun `parses https remote url`() {
        val parsed = GiteaUrlUtil.parseRemoteUrl("https://git.example.com/owner/repo.git")!!
        assertEquals("git.example.com", parsed.host)
        assertEquals(listOf("owner", "repo"), parsed.pathSegments)
    }

    @Test
    fun `parses https remote url with subpath`() {
        val parsed = GiteaUrlUtil.parseRemoteUrl("https://git.example.com/gitea/owner/repo.git")!!
        assertEquals("git.example.com", parsed.host)
        assertEquals(listOf("gitea", "owner", "repo"), parsed.pathSegments)
    }

    @Test
    fun `parses scp-like ssh remote url`() {
        val parsed = GiteaUrlUtil.parseRemoteUrl("git@git.example.com:owner/repo.git")!!
        assertEquals("git.example.com", parsed.host)
        assertEquals(listOf("owner", "repo"), parsed.pathSegments)
    }

    @Test
    fun `parses ssh scheme remote url with custom port`() {
        val parsed = GiteaUrlUtil.parseRemoteUrl("ssh://git@git.example.com:2222/owner/repo.git")!!
        assertEquals("git.example.com", parsed.host)
        assertEquals(listOf("owner", "repo"), parsed.pathSegments)
    }

    @Test
    fun `parses remote url without dot-git suffix`() {
        val parsed = GiteaUrlUtil.parseRemoteUrl("https://git.example.com/owner/repo")!!
        assertEquals(listOf("owner", "repo"), parsed.pathSegments)
    }

    @Test
    fun `matches remote under bare-host server`() {
        val server = GiteaUrlUtil.parseServerUrl("https://git.example.com")!!
        val remote = GiteaUrlUtil.parseRemoteUrl("git@git.example.com:owner/repo.git")!!
        assertTrue(GiteaUrlUtil.isUnderServer(remote, server))
        assertEquals("owner" to "repo", GiteaUrlUtil.ownerAndRepo(remote, server))
    }

    @Test
    fun `matches remote under subpath server`() {
        val server = GiteaUrlUtil.parseServerUrl("https://git.example.com/gitea")!!
        val remote = GiteaUrlUtil.parseRemoteUrl("https://git.example.com/gitea/owner/repo.git")!!
        assertTrue(GiteaUrlUtil.isUnderServer(remote, server))
        assertEquals("owner" to "repo", GiteaUrlUtil.ownerAndRepo(remote, server))
    }

    @Test
    fun `does not match remote under a different subpath on the same host`() {
        val server = GiteaUrlUtil.parseServerUrl("https://git.example.com/gitea")!!
        val remote = GiteaUrlUtil.parseRemoteUrl("https://git.example.com/other/owner/repo.git")!!
        assertFalse(GiteaUrlUtil.isUnderServer(remote, server))
    }

    @Test
    fun `does not match remote on a different host`() {
        val server = GiteaUrlUtil.parseServerUrl("https://git.example.com")!!
        val remote = GiteaUrlUtil.parseRemoteUrl("https://github.com/owner/repo.git")!!
        assertFalse(GiteaUrlUtil.isUnderServer(remote, server))
    }

    @Test
    fun `host match is case-insensitive`() {
        val server = GiteaUrlUtil.parseServerUrl("https://Git.Example.com")!!
        val remote = GiteaUrlUtil.parseRemoteUrl("https://git.example.COM/owner/repo.git")!!
        assertTrue(GiteaUrlUtil.isUnderServer(remote, server))
    }

    @Test
    fun `owner-repo is null when path shape does not fit after prefix`() {
        val server = GiteaUrlUtil.parseServerUrl("https://git.example.com")!!
        // Three segments left over (e.g. a nested-group repo) doesn't fit
        // the plain owner/repo shape this plugin assumes.
        val remote = GiteaUrlUtil.parseRemoteUrl("https://git.example.com/group/owner/repo.git")!!
        assertNull(GiteaUrlUtil.ownerAndRepo(remote, server))
    }
}
