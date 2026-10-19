package com.github.hypercookie.giteaplugin.api

import java.util.concurrent.ConcurrentHashMap

/**
 * A Gitea server's version as `GET /api/v1/version` reports it: `1.27.3`,
 * `28.1.0`, `1.27.0-rc0` or a development build like `1.28.0+dev-12-gabc123`.
 * Only the numeric part counts; a pre-release or development build is
 * treated as its release.
 */
data class GiteaServerVersion(val parts: List<Int>) : Comparable<GiteaServerVersion> {

    override fun compareTo(other: GiteaServerVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    override fun toString(): String = parts.joinToString(".")

    companion object {
        fun parse(version: String): GiteaServerVersion? {
            val numbers = version.trim().removePrefix("v").takeWhile { it.isDigit() || it == '.' }
                .split('.').filter(String::isNotEmpty)
            if (numbers.isEmpty()) return null
            return GiteaServerVersion(numbers.map { it.toIntOrNull() ?: return null })
        }

        fun of(vararg parts: Int) = GiteaServerVersion(parts.toList())
    }
}

/**
 * What a server's Gitea version supports, for features that only newer
 * versions have. Checked against each version's API
 * (.github/gitea-versions.txt lists the versions the tests run against).
 * [version] null means unknown: everything is assumed to work, and a
 * failing request shows its error as before.
 */
data class GiteaServerFeatures(val version: GiteaServerVersion?) {

    /** `POST /repos/{owner}/{repo}/pulls/comments/{id}/resolve` and `/unresolve`. */
    val resolveComments: Boolean get() = atLeast(RESOLVE_COMMENTS)

    /** `POST /repos/{owner}/{repo}/pulls/{index}/comments/{id}/replies`. */
    val replyToComments: Boolean get() = atLeast(REPLY_TO_COMMENTS)

    private fun atLeast(required: GiteaServerVersion) = version == null || version >= required

    companion object {
        val RESOLVE_COMMENTS = GiteaServerVersion.of(1, 26)
        val REPLY_TO_COMMENTS = GiteaServerVersion.of(1, 27)
        val UNKNOWN = GiteaServerFeatures(null)

        /** By server URL. A server's version only changes on an upgrade, so it
         * is asked once per IDE session. */
        private val known = ConcurrentHashMap<String, GiteaServerFeatures>()

        /** The server's features; [UNKNOWN] when its version can't be read,
         * asking again next time. */
        suspend fun of(serverUrl: String, client: GiteaApiClient): GiteaServerFeatures {
            known[serverUrl]?.let { return it }
            val version = try {
                GiteaServerVersion.parse(client.getVersion().version)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return UNKNOWN
            return GiteaServerFeatures(version).also { known[serverUrl] = it }
        }
    }
}
