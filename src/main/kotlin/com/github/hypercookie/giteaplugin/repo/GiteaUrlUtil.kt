package com.github.hypercookie.giteaplugin.repo

import java.net.URI

/** Host + path, lowercased/split, with any `.git` suffix and leading/trailing
 * slashes stripped — a common shape for comparing a git remote URL against a
 * configured Gitea server's base URL regardless of scheme. */
data class ParsedGitUrl(val host: String, val pathSegments: List<String>)

/**
 * Gitea is always self-hosted, often behind a URL **subpath**
 * (`https://git.example.com/gitea/owner/repo.git`), unlike github.com/
 * gitlab.com which are recognizable by a fixed hostname alone. So matching a
 * local git remote to a configured Gitea account can't rely on hostname
 * detection the way GitHub/GitLab plugins do — it has to compare against the
 * account's full configured base URL (host + path prefix).
 */
object GiteaUrlUtil {

    private val SCP_LIKE = Regex("^(?:[^@/]+@)?([^:/]+):(.+)$")

    /** Parses a git remote URL: `https://host/path.git`, `ssh://user@host:port/path.git`,
     * or the scp-like `user@host:path.git` form. Returns null if unparseable. */
    fun parseRemoteUrl(url: String): ParsedGitUrl? {
        val trimmed = url.trim()
        return if (trimmed.contains("://")) parseUri(trimmed) else parseScpLike(trimmed)
    }

    /** Parses a configured account's server URL (always has an explicit scheme). */
    fun parseServerUrl(url: String): ParsedGitUrl? = parseUri(url.trim())

    private fun parseUri(url: String): ParsedGitUrl? = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return null
        ParsedGitUrl(host.lowercase(), splitPath(uri.path ?: ""))
    }.getOrNull()

    private fun parseScpLike(url: String): ParsedGitUrl? {
        val match = SCP_LIKE.matchEntire(url) ?: return null
        return ParsedGitUrl(match.groupValues[1].lowercase(), splitPath(match.groupValues[2]))
    }

    private fun splitPath(path: String): List<String> =
        path.trim('/')
            .removeSuffix(".git")
            .split('/')
            .filter { it.isNotBlank() }

    /** True if [remote] lives under [serverBase] — same host, and its path
     * starts with the server's own path prefix (empty prefix always matches). */
    fun isUnderServer(remote: ParsedGitUrl, serverBase: ParsedGitUrl): Boolean {
        if (!remote.host.equals(serverBase.host, ignoreCase = true)) return false
        if (remote.pathSegments.size < serverBase.pathSegments.size) return false
        return remote.pathSegments.take(serverBase.pathSegments.size) == serverBase.pathSegments
    }

    /** The (owner, repo) pair — the two path segments immediately after the
     * server's path prefix. Null if [remote] doesn't have exactly that shape
     * once the prefix is removed (e.g. it points somewhere else on the same host),
     * or if either is `.` or `..`, which would make API paths like
     * `/api/v1/repos/../x` that a server or proxy may resolve elsewhere. */
    fun ownerAndRepo(remote: ParsedGitUrl, serverBase: ParsedGitUrl): Pair<String, String>? {
        val remaining = remote.pathSegments.drop(serverBase.pathSegments.size)
        if (remaining.size != 2 || remaining.any { it == "." || it == ".." }) return null
        return remaining[0] to remaining[1]
    }
}
