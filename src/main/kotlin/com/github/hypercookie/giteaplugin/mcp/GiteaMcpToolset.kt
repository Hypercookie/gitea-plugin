package com.github.hypercookie.giteaplugin.mcp

import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

/** Shown for the toolset in the MCP Server settings. */
const val GITEA_MCP_DESCRIPTION =
    "Read and review Gitea pull requests: list, details, files, commits, timeline, checks, review threads, comments, reactions; " +
        "comment, reply, resolve, react and submit reviews."

/** One inline comment for [GiteaMcpToolset.gitea_submit_review]. */
@Serializable
class GiteaReviewCommentInput(
    @param:McpDescription("Path of the file in the pull request, as listed by gitea_get_pull_request_files")
    val path: String,
    @param:McpDescription("1-based line number in the new version of the file (or the old version if oldSide is true)")
    val line: Int,
    @param:McpDescription("The comment text (Markdown)")
    val body: String,
    @param:McpDescription("True to comment on a line of the old (base) version of the file; default false")
    val oldSide: Boolean = false,
)

/**
 * Gitea pull request tools for the IDE's MCP server. Loaded only when the
 * MCP Server plugin is present (see gitea-mcp.xml). All logic lives in
 * [GiteaMcpService]; this class maps tool calls onto it. Tool names are
 * prefixed `gitea_`. Every tool answers with JSON text. Pull requests
 * are addressed by number; pass `repository` (owner/name) only when the
 * project has several Gitea repositories.
 */
class GiteaMcpToolset : GiteaMcpToolsetBase() {

    private suspend fun service(): GiteaMcpService = GiteaMcpService(currentCoroutineContext().project)

    private suspend fun <T> run(block: suspend (GiteaMcpService) -> T): T =
        try {
            block(service())
        } catch (e: GiteaToolException) {
            mcpFail(e.message ?: "Gitea tool failed")
        }

    // --- Read ----------------------------------------------------------------

    @McpTool(name = "gitea_list_repositories")
    @McpDescription("Lists the Gitea repositories (owner/name) the open project's git remotes point to, and which one is the default.")
    suspend fun gitea_list_repositories(): String = run { it.listRepositories() }

    @McpTool(name = "gitea_list_pull_requests")
    @McpDescription("Lists or searches pull requests of the project's Gitea repository, newest activity first.")
    suspend fun gitea_list_pull_requests(
        @McpDescription("open (default), closed or all") state: String? = null,
        @McpDescription("Text to search for in titles and bodies") query: String? = null,
        @McpDescription("Only pull requests created by this user login") author: String? = null,
        @McpDescription("Only pull requests with this label name") label: String? = null,
        @McpDescription("Page number, starting at 1") page: Int? = null,
        @McpDescription("Page size, default 30, at most 50") limit: Int? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.listPullRequests(repository, state, query, author, label, page, limit) }

    @McpTool(name = "gitea_get_pull_request")
    @McpDescription("Gets a pull request: title, description, state, branches, head commit, labels, reviewers and size.")
    suspend fun gitea_get_pull_request(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getPullRequest(repository, number) }

    @McpTool(name = "gitea_get_pull_request_files")
    @McpDescription("Lists the files a pull request changes, with status and added/removed line counts.")
    suspend fun gitea_get_pull_request_files(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getFiles(repository, number) }

    @McpTool(name = "gitea_get_pull_request_commits")
    @McpDescription("Lists the commits of a pull request, oldest first.")
    suspend fun gitea_get_pull_request_commits(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getCommits(repository, number) }

    @McpTool(name = "gitea_get_pull_request_timeline")
    @McpDescription("Gets the activity timeline of a pull request (comments, reviews, pushes, label and title changes), the latest events at most 50.")
    suspend fun gitea_get_pull_request_timeline(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("How many of the latest events to return, default and maximum 50") limit: Int? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getTimeline(repository, number, limit) }

    @McpTool(name = "gitea_get_pull_request_checks")
    @McpDescription("Gets the combined status and the individual status checks (CI) of a pull request's head commit.")
    suspend fun gitea_get_pull_request_checks(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getChecks(repository, number) }

    @McpTool(name = "gitea_get_pull_request_comments")
    @McpDescription("Lists the general (non-inline) comments of a pull request.")
    suspend fun gitea_get_pull_request_comments(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getComments(repository, number) }

    @McpTool(name = "gitea_get_review_threads")
    @McpDescription("Lists the inline review threads of a pull request: file, line, resolved state and all comments. Each thread's threadId is the id of its first comment; use it with gitea_reply_to_review_comment and gitea_resolve_review_thread.")
    suspend fun gitea_get_review_threads(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("Include resolved threads, default false") includeResolved: Boolean? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getReviewThreads(repository, number, includeResolved ?: false) }

    @McpTool(name = "gitea_get_reactions")
    @McpDescription("Lists reactions on a pull request's description, or on a comment if commentId is given.")
    suspend fun gitea_get_reactions(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("Comment id (general or inline); omit for the pull request itself") commentId: Long? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.getReactions(repository, number, commentId) }

    // --- Write (switch in Settings | Version Control | Gitea) ----------------

    @McpTool(name = "gitea_add_comment")
    @McpDescription("Posts a general comment on a pull request as the user's Gitea account. Can be turned off in the Gitea settings.")
    suspend fun gitea_add_comment(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("Comment text (Markdown)") body: String,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.addComment(repository, number, body) }

    @McpTool(name = "gitea_reply_to_review_comment")
    @McpDescription("Replies to an inline review comment (needs Gitea 1.27). Can be turned off in the Gitea settings.")
    suspend fun gitea_reply_to_review_comment(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("Id of the comment to reply to, e.g. a threadId from gitea_get_review_threads") commentId: Long,
        @McpDescription("Reply text (Markdown)") body: String,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.reply(repository, number, commentId, body) }

    @McpTool(name = "gitea_resolve_review_thread")
    @McpDescription("Marks an inline review thread as resolved, or reopens it (needs Gitea 1.26). Can be turned off in the Gitea settings.")
    suspend fun gitea_resolve_review_thread(
        @McpDescription("threadId from gitea_get_review_threads (the id of the thread's first comment)") threadId: Long,
        @McpDescription("true to resolve (default), false to reopen") resolved: Boolean? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.setResolved(repository, threadId, resolved ?: true) }

    @McpTool(name = "gitea_add_reaction")
    @McpDescription("Adds a reaction (such as +1, heart, rocket) to a pull request or, with commentId, to a comment. Can be turned off in the Gitea settings.")
    suspend fun gitea_add_reaction(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("Reaction name, e.g. +1, -1, laugh, hooray, confused, heart, rocket, eyes") reaction: String,
        @McpDescription("Comment id (general or inline); omit to react to the pull request itself") commentId: Long? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.setReaction(repository, number, commentId, reaction, add = true) }

    @McpTool(name = "gitea_remove_reaction")
    @McpDescription("Removes the user's reaction from a pull request or, with commentId, from a comment. Can be turned off in the Gitea settings.")
    suspend fun gitea_remove_reaction(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("Reaction name, e.g. +1") reaction: String,
        @McpDescription("Comment id (general or inline); omit for the pull request itself") commentId: Long? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run { it.setReaction(repository, number, commentId, reaction, add = false) }

    @McpTool(name = "gitea_submit_review")
    @McpDescription("Submits a review on a pull request in one step: approve, comment or request changes, with an optional summary and inline comments. Can be turned off in the Gitea settings.")
    suspend fun gitea_submit_review(
        @McpDescription("Pull request number") number: Long,
        @McpDescription("APPROVED, COMMENT or REQUEST_CHANGES") event: String,
        @McpDescription("Review summary (Markdown); required for COMMENT and REQUEST_CHANGES unless inline comments are given") body: String? = null,
        @McpDescription("Inline comments attached to the review") comments: List<GiteaReviewCommentInput>? = null,
        @McpDescription("owner/name, only needed if the project has several Gitea repositories") repository: String? = null,
    ): String = run {
        it.submitReview(repository, number, event, body, comments.orEmpty().map { c -> McpInlineComment(c.path, c.line, c.body, c.oldSide) })
    }
}
