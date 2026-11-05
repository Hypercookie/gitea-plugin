package com.github.hypercookie.giteaplugin.pullrequest.review

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Pull request files read from the local repository's objects instead of
 * the Gitea contents endpoint, which is otherwise the most called one: every
 * diff and every open file of a reviewed pull request needs both sides.
 *
 * The commits a review reads (head, merge base, the commits of the pull
 * request) are usually local already, whether or not the branch is checked
 * out. A commit that isn't, or a ref that isn't a commit id, is left to the
 * API, and so is any read that fails for another reason than a missing file.
 */
internal class LocalGitFiles(private val project: Project, private val root: VirtualFile) {

    /** Result of [read]: [Unavailable] means ask the API. */
    sealed interface Read {
        data class Found(val text: String) : Read
        data object Missing : Read
        data object Unavailable : Read
    }

    /** Whether each commit id is in the local repository; commits don't
     * disappear while a review is open, and get fetched only rarely. */
    private val hasCommit = ConcurrentHashMap<String, Boolean>()

    suspend fun read(path: String, ref: String): Read {
        if (!COMMIT_ID.matches(ref)) return Read.Unavailable
        return withContext(Dispatchers.IO) {
            if (hasCommit[ref] != true) {
                // Not cached as false: a fetch or checkout can bring the commit in.
                if (!exists("$ref^{commit}")) return@withContext Read.Unavailable
                hasCommit[ref] = true
            }
            try {
                val handler = GitBinaryHandler(project, root, GitCommand.CAT_FILE).apply {
                    setSilent(true)
                    addParameters("blob", "$ref:$path")
                }
                Read.Found(String(handler.run(), Charsets.UTF_8))
            } catch (e: VcsException) {
                when {
                    !exists("$ref:$path") -> Read.Missing
                    else -> {
                        thisLogger().info("Failed to read $path at $ref from the local repository", e)
                        Read.Unavailable
                    }
                }
            }
        }
    }

    private fun exists(objectName: String): Boolean {
        val handler = GitLineHandler(project, root, GitCommand.CAT_FILE).apply {
            setSilent(true)
            addParameters("-e", objectName)
        }
        return Git.getInstance().runCommand(handler).success()
    }

    private companion object {
        /** Full SHA-1 or SHA-256 commit ids only: an abbreviated one could
         * be ambiguous locally while unique on the server. */
        val COMMIT_ID = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
    }
}
