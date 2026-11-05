package com.github.hypercookie.giteaplugin.mcp

import com.github.hypercookie.giteaplugin.accounts.GiteaAccountManager
import com.github.hypercookie.giteaplugin.api.GiteaApiClient
import com.github.hypercookie.giteaplugin.api.GiteaApiException
import com.github.hypercookie.giteaplugin.api.GiteaServerFeatures
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.CreatePullReviewOptions
import com.github.hypercookie.giteaplugin.api.dto.PullReviewComment
import com.github.hypercookie.giteaplugin.api.dto.ReviewEvent
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewSide
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewThread
import com.github.hypercookie.giteaplugin.pullrequest.review.ReviewThreads
import com.github.hypercookie.giteaplugin.pullrequest.PendingReviewStoreService
import com.github.hypercookie.giteaplugin.repo.GiteaRepoContext
import com.github.hypercookie.giteaplugin.repo.GiteaRepoLinkService
import com.github.hypercookie.giteaplugin.settings.GiteaSettings
import com.intellij.openapi.project.Project
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A failure to report to the MCP client as the tool's error text. */
class GiteaToolException(message: String) : Exception(message)

/** One inline comment of a review submitted through MCP. [line] is 1-based. */
data class McpInlineComment(val path: String, val line: Int, val body: String, val oldSide: Boolean = false)

/**
 * The Gitea operations behind the MCP tools (see [GiteaMcpToolset]). This
 * class has no dependency on the MCP server plugin, so it is loaded and
 * tested without it. Every function returns JSON text, which is what the
 * tools hand to the client, and throws [GiteaToolException] with a message
 * meant for the client.
 */
class GiteaMcpService(private val project: Project) {

    private class Target(
        val client: GiteaApiClient, val owner: String, val repo: String, val serverUrl: String,
        val context: GiteaRepoContext,
    )

    private val json = Json { prettyPrint = false }

    // --- Repository resolution -------------------------------------------

    private fun target(repository: String?): Target {
        val matches = GiteaRepoLinkService.getInstance(project).findAllMatches()
        if (matches.isEmpty()) {
            throw GiteaToolException("No git remote of project '${project.name}' belongs to a configured Gitea account. Add the account in Settings | Version Control | Gitea.")
        }
        val wanted = repository?.trim()?.takeIf { it.isNotEmpty() }
        val context = if (wanted != null) {
            matches.firstOrNull { "${it.owner}/${it.repo}".equals(wanted, ignoreCase = true) }
                ?: throw GiteaToolException("Repository '$wanted' is not one of this project's Gitea repositories: ${names(matches.map { "${it.owner}/${it.repo}" })}")
        } else {
            GiteaRepoLinkService.getInstance(project).resolveDefault()
                ?: throw GiteaToolException("The project has several Gitea repositories; pass 'repository' as one of: ${names(matches.map { "${it.owner}/${it.repo}" }.distinct())}")
        }
        return Target(GiteaAccountManager.getInstance().clientFor(context.account), context.owner, context.repo, context.account.serverUrl, context)
    }

    private fun names(list: List<String>) = list.distinct().joinToString(", ")

    private fun requireWriteEnabled() {
        if (!GiteaSettings.getInstance().mcpWriteTools) {
            throw GiteaToolException("Changing pull requests through MCP is turned off. Enable it in Settings | Version Control | Gitea | Features.")
        }
    }

    /** Runs [block], turning API failures into [GiteaToolException]s. */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: GiteaToolException) {
        throw e
    } catch (e: GiteaApiException) {
        throw GiteaToolException("Gitea request failed: ${e.message}")
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: java.io.IOException) {
        throw GiteaToolException("Could not reach the Gitea server: ${e.message}")
    }

    private fun render(element: JsonElement): String = json.encodeToString(JsonElement.serializer(), element)

    // --- Read ----------------------------------------------------------------

    fun listRepositories(): String {
        val matches = GiteaRepoLinkService.getInstance(project).findAllMatches()
        val default = GiteaRepoLinkService.getInstance(project).resolveDefault()
        return render(buildJsonArray {
            for (m in matches.distinctBy { it.account.id to "${it.owner}/${it.repo}" }) {
                add(buildJsonObject {
                    put("repository", "${m.owner}/${m.repo}")
                    put("server", m.account.serverUrl)
                    put("default", default != null && default.account.id == m.account.id && default.owner == m.owner && default.repo == m.repo)
                })
            }
        })
    }

    suspend fun listPullRequests(
        repository: String?, state: String?, query: String?, author: String?, label: String?, page: Int?, limit: Int?,
    ): String = guarded {
        val t = target(repository)
        val st = (state ?: "open").lowercase()
        if (st !in setOf("open", "closed", "all")) throw GiteaToolException("state must be open, closed or all")
        val items = t.client.searchPullRequestIssues(
            t.owner, t.repo, st, query, author, null, label, (page ?: 1).coerceAtLeast(1), (limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT),
        )
        render(buildJsonArray {
            for (i in items) add(buildJsonObject {
                put("number", i.number)
                put("title", i.title)
                put("state", i.state)
                put("author", i.user?.login)
                put("labels", JsonArray(i.labels.map { JsonPrimitive(it.name) }))
                put("comments", i.comments)
                put("updated", i.updatedAt)
                put("url", i.htmlUrl)
            })
        })
    }

    suspend fun getPullRequest(repository: String?, number: Long): String = guarded {
        val t = target(repository)
        val pr = t.client.getPullRequest(t.owner, t.repo, number)
        render(buildJsonObject {
            put("number", pr.number)
            put("title", pr.title)
            put("state", pr.state)
            put("draft", pr.draft)
            put("merged", pr.merged)
            put("mergeable", pr.mergeable)
            put("author", pr.user?.login)
            put("body", pr.body)
            put("base", pr.base.ref)
            put("head", pr.head.ref)
            put("headSha", pr.head.sha)
            put("mergeBase", pr.mergeBase)
            put("labels", JsonArray(pr.labels.map { JsonPrimitive(it.name) }))
            put("assignees", JsonArray(pr.assignees.orEmpty().map { JsonPrimitive(it.login) }))
            put("requestedReviewers", JsonArray(pr.requestedReviewers.orEmpty().map { JsonPrimitive(it.login) }))
            put("changedFiles", pr.changedFiles)
            put("additions", pr.additions)
            put("deletions", pr.deletions)
            put("created", pr.createdAt)
            put("updated", pr.updatedAt)
            put("url", pr.htmlUrl)
        })
    }

    suspend fun getFiles(repository: String?, number: Long): String = guarded {
        val t = target(repository)
        render(buildJsonArray {
            for (f in t.client.getPullRequestFiles(t.owner, t.repo, number)) add(buildJsonObject {
                put("path", f.filename)
                put("status", f.status)
                put("previousPath", f.previousFilename)
                put("additions", f.additions)
                put("deletions", f.deletions)
            })
        })
    }

    suspend fun getCommits(repository: String?, number: Long): String = guarded {
        val t = target(repository)
        render(buildJsonArray {
            for (c in t.client.getPullRequestCommits(t.owner, t.repo, number)) add(buildJsonObject {
                put("sha", c.sha)
                put("message", c.commit.message)
                put("author", c.author?.login ?: c.commit.author.name)
                put("date", c.commit.author.date)
            })
        })
    }

    suspend fun getTimeline(repository: String?, number: Long, limit: Int?): String = guarded {
        val t = target(repository)
        val events = t.client.getTimeline(t.owner, t.repo, number)
        val shown = events.takeLast((limit ?: MAX_LIMIT).coerceIn(1, MAX_LIMIT))
        render(buildJsonObject {
            put("total", events.size)
            put("events", buildJsonArray {
                for (e in shown) add(buildJsonObject {
                    put("id", e.id)
                    put("type", e.type)
                    put("user", e.user?.login)
                    put("created", e.createdAt)
                    put("body", e.body)
                    put("reviewId", e.reviewId)
                    put("label", e.label?.name)
                    put("assignee", e.assignee?.login)
                    put("oldTitle", e.oldTitle)
                    put("newTitle", e.newTitle)
                })
            })
        })
    }

    suspend fun getChecks(repository: String?, number: Long): String = guarded {
        val t = target(repository)
        val sha = t.client.getPullRequest(t.owner, t.repo, number).head.sha
        val combined = t.client.getCombinedStatus(t.owner, t.repo, sha, MAX_LIMIT)
        render(buildJsonObject {
            put("commit", sha)
            put("state", combined.state)
            put("checks", buildJsonArray {
                for (s in combined.statuses.orEmpty()) add(buildJsonObject {
                    put("context", s.context)
                    put("status", s.status)
                    put("description", s.description)
                })
            })
        })
    }

    suspend fun getComments(repository: String?, number: Long): String = guarded {
        val t = target(repository)
        render(buildJsonArray {
            for (c in t.client.listIssueComments(t.owner, t.repo, number)) add(buildJsonObject {
                put("id", c.id)
                put("author", c.user?.login)
                put("body", c.body)
                put("created", c.createdAt)
                put("updated", c.updatedAt)
            })
        })
    }

    suspend fun getReviewThreads(repository: String?, number: Long, includeResolved: Boolean): String = guarded {
        val t = target(repository)
        val threads = loadThreads(t, number)
        val shown = if (includeResolved) threads else threads.filterNot { it.isResolved }
        render(buildJsonArray {
            for (th in shown) add(threadJson(th))
        })
    }

    suspend fun getReactions(repository: String?, number: Long, commentId: Long?): String = guarded {
        val t = target(repository)
        val reactions = if (commentId != null) t.client.listCommentReactions(t.owner, t.repo, commentId)
        else t.client.listIssueReactions(t.owner, t.repo, number)
        render(buildJsonArray {
            for (r in reactions) add(buildJsonObject {
                put("reaction", r.content)
                put("user", r.user?.login)
            })
        })
    }

    private suspend fun loadThreads(t: Target, number: Long): List<ReviewThread> = coroutineScope {
        val reviews = t.client.listReviews(t.owner, t.repo, number)
        val permits = Semaphore(6)
        val comments = reviews.filter { (it.commentsCount ?: 1) > 0 }
            .map { review -> async { permits.withPermit { t.client.listReviewComments(t.owner, t.repo, number, review.id) } } }
            .awaitAll().flatten()
        ReviewThreads.group(comments, reviews.associate { it.id to it.commitId })
    }

    private fun threadJson(th: ReviewThread): JsonElement = buildJsonObject {
        // The first comment's id identifies the thread: pass it to
        // gitea_reply_to_review_comment and gitea_resolve_review_thread.
        put("threadId", th.id)
        put("path", th.path)
        put("line", th.line + 1)
        put("side", if (th.side == ReviewSide.OLD) "old" else "new")
        put("resolved", th.isResolved)
        put("resolvedBy", th.resolver?.login)
        put("comments", buildJsonArray {
            for (c in th.comments) add(commentJson(c))
        })
    }

    private fun commentJson(c: PullReviewComment): JsonElement = buildJsonObject {
        put("id", c.id)
        put("author", c.user?.login)
        put("body", c.body)
        put("created", c.createdAt)
    }

    // --- Write ---------------------------------------------------------------

    suspend fun addComment(repository: String?, number: Long, body: String): String = guarded {
        requireWriteEnabled()
        requireText(body)
        val t = target(repository)
        val c = t.client.createIssueComment(t.owner, t.repo, number, body)
        render(buildJsonObject { put("id", c.id); put("url", c.htmlUrl) })
    }

    suspend fun reply(repository: String?, number: Long, commentId: Long, body: String): String = guarded {
        requireWriteEnabled()
        requireText(body)
        val t = target(repository)
        if (!GiteaServerFeatures.of(t.serverUrl, t.client).replyToComments) {
            throw GiteaToolException("This Gitea server is older than ${GiteaServerFeatures.REPLY_TO_COMMENTS}; replying to review comments needs it.")
        }
        val c = t.client.replyToComment(t.owner, t.repo, number, commentId, body)
        render(commentJson(c))
    }

    suspend fun setResolved(repository: String?, commentId: Long, resolved: Boolean): String = guarded {
        requireWriteEnabled()
        val t = target(repository)
        if (!GiteaServerFeatures.of(t.serverUrl, t.client).resolveComments) {
            throw GiteaToolException("This Gitea server is older than ${GiteaServerFeatures.RESOLVE_COMMENTS}; resolving review comments needs it.")
        }
        if (resolved) t.client.resolveComment(t.owner, t.repo, commentId) else t.client.unresolveComment(t.owner, t.repo, commentId)
        render(buildJsonObject { put("threadId", commentId); put("resolved", resolved) })
    }

    suspend fun setReaction(repository: String?, number: Long, commentId: Long?, reaction: String, add: Boolean): String = guarded {
        requireWriteEnabled()
        val t = target(repository)
        val allowed = runCatching { t.client.getUISettings().allowedReactions }.getOrDefault(emptyList())
        if (allowed.isNotEmpty() && reaction !in allowed) {
            throw GiteaToolException("Reaction '$reaction' is not allowed on this server. Allowed: ${allowed.joinToString(", ")}")
        }
        if (add) {
            if (commentId != null) t.client.addCommentReaction(t.owner, t.repo, commentId, reaction)
            else t.client.addIssueReaction(t.owner, t.repo, number, reaction)
        } else {
            if (commentId != null) t.client.removeCommentReaction(t.owner, t.repo, commentId, reaction)
            else t.client.removeIssueReaction(t.owner, t.repo, number, reaction)
        }
        render(buildJsonObject { put("reaction", reaction); put("added", add) })
    }

    private fun parseEvent(event: String): ReviewEvent = when (event.trim().uppercase()) {
        "APPROVE", "APPROVED" -> ReviewEvent.APPROVED
        "COMMENT" -> ReviewEvent.COMMENT
        "REQUEST_CHANGES" -> ReviewEvent.REQUEST_CHANGES
        else -> throw GiteaToolException("event must be APPROVED, COMMENT or REQUEST_CHANGES")
    }

    private fun toInline(c: McpInlineComment): CreatePullReviewComment {
        if (c.line < 1) throw GiteaToolException("Line numbers are 1-based: ${c.path}:${c.line}")
        requireText(c.body)
        return CreatePullReviewComment(
            body = c.body, path = c.path,
            newPosition = if (c.oldSide) 0 else c.line.toLong(),
            oldPosition = if (c.oldSide) c.line.toLong() else 0,
        )
    }

    private fun requireReviewContent(event: ReviewEvent, body: String?, commentCount: Int) {
        if (event != ReviewEvent.APPROVED && body.isNullOrBlank() && commentCount == 0) {
            throw GiteaToolException("A $event review needs a body or at least one inline comment.")
        }
    }

    private fun reviewJson(review: com.github.hypercookie.giteaplugin.api.dto.PullReview): String = render(buildJsonObject {
        put("reviewId", review.id)
        put("state", review.state.name)
        put("url", review.htmlUrl)
    })

    suspend fun submitReview(
        repository: String?, number: Long, event: String, body: String?, comments: List<McpInlineComment>,
    ): String = guarded {
        requireWriteEnabled()
        val reviewEvent = parseEvent(event)
        requireReviewContent(reviewEvent, body, comments.size)
        val inline = comments.map(::toInline)
        val t = target(repository)
        val head = t.client.getPullRequest(t.owner, t.repo, number).head.sha
        reviewJson(t.client.createReview(t.owner, t.repo, number, CreatePullReviewOptions(reviewEvent, body.orEmpty(), head, inline)))
    }

    // --- Review mode: queue comments, then submit them as one review ------------
    // The queue is the IDE's own pending-review store, so comments added here
    // show up in the editor and the pull request tab, and the other way round.

    private fun pendingStore(t: Target, number: Long) =
        PendingReviewStoreService.getInstance(project).storeFor(t.context, number)

    private fun pendingJson(comments: List<CreatePullReviewComment>): JsonElement = buildJsonArray {
        comments.forEachIndexed { i, c ->
            add(buildJsonObject {
                put("index", i + 1)
                put("path", c.path)
                put("line", if (c.oldPosition > 0) c.oldPosition else c.newPosition)
                put("side", if (c.oldPosition > 0) "old" else "new")
                put("body", c.body)
            })
        }
    }

    fun getPendingReview(repository: String?, number: Long): String {
        val t = target(repository)
        return render(buildJsonObject { put("comments", pendingJson(pendingStore(t, number).pendingComments)) })
    }

    fun addPendingReviewComment(repository: String?, number: Long, comment: McpInlineComment): String {
        requireWriteEnabled()
        val t = target(repository)
        val store = pendingStore(t, number)
        store.addComment(toInline(comment))
        return render(buildJsonObject { put("pendingComments", store.pendingComments.size) })
    }

    /** Removes the pending comment at 1-based [index], or all of them when [index] is null. */
    fun discardPendingReview(repository: String?, number: Long, index: Int?): String {
        requireWriteEnabled()
        val t = target(repository)
        val store = pendingStore(t, number)
        if (index == null) {
            store.clear()
        } else {
            val comment = store.pendingComments.getOrNull(index - 1)
                ?: throw GiteaToolException("There is no pending comment $index; see gitea_get_pending_review.")
            store.removeComment(comment)
        }
        return render(buildJsonObject { put("pendingComments", store.pendingComments.size) })
    }

    suspend fun submitPendingReview(repository: String?, number: Long, event: String, body: String?): String = guarded {
        requireWriteEnabled()
        val reviewEvent = parseEvent(event)
        val t = target(repository)
        val store = pendingStore(t, number)
        val pending = store.pendingComments
        requireReviewContent(reviewEvent, body, pending.size)
        val head = t.client.getPullRequest(t.owner, t.repo, number).head.sha
        val review = t.client.createReview(t.owner, t.repo, number, CreatePullReviewOptions(reviewEvent, body.orEmpty(), head, pending))
        store.removeAll(pending)
        reviewJson(review)
    }

    private fun requireText(text: String) {
        if (text.isBlank()) throw GiteaToolException("The comment text is empty.")
    }


    private companion object {
        const val DEFAULT_LIMIT = 30
        const val MAX_LIMIT = 50
    }
}
