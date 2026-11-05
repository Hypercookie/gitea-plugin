package com.github.hypercookie.giteaplugin.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

/**
 * IDE-wide switches for the plugin's features, shown on the Gitea settings
 * page. Every feature that talks to the server on its own (without the user
 * opening something) can be turned off here, which also stops its requests.
 */
@Service(Service.Level.APP)
@State(name = "GiteaSettings", storages = [Storage("gitea.xml")])
class GiteaSettings : SerializablePersistentStateComponent<GiteaSettings.State>(State()) {

    data class State(
        @JvmField val commitChecksInLog: Boolean = false,
        @JvmField val reviewInEditor: Boolean = true,
        @JvmField val showReactions: Boolean = true,
        @JvmField val suggestPullRequestAfterPush: Boolean = true,
        @JvmField val refreshOnFrameActivation: Boolean = true,
        @JvmField val showRequestCounter: Boolean = false,
        @JvmField val codeSuggestions: Boolean = false,
        @JvmField val multiLineComments: Boolean = false,
        @JvmField val mcpWriteTools: Boolean = false,
        @JvmField val mcpEnabled: Boolean = true,
    )

    /** "Gitea Commit Checks" column in the Git log and its status requests
     * (one per commit shown); off by default. */
    var commitChecksInLog: Boolean
        get() = state.commitChecksInLog
        set(value) { updateState { it.copy(commitChecksInLog = value) } }

    /** Review threads of the checked-out branch's pull request in editors,
     * and the requests that find that pull request on git changes. */
    var reviewInEditor: Boolean
        get() = state.reviewInEditor
        set(value) { updateState { it.copy(reviewInEditor = value) } }

    /** Reaction pills and the add reaction button on comments; each comment
     * costs one request for its reactions. */
    var showReactions: Boolean
        get() = state.showReactions
        set(value) { updateState { it.copy(showReactions = value) } }

    /** The "no pull request yet" notification after a push. */
    var suggestPullRequestAfterPush: Boolean
        get() = state.suggestPullRequestAfterPush
        set(value) { updateState { it.copy(suggestPullRequestAfterPush = value) } }

    /** Reloading open pull requests when the IDE window gets focus again. */
    var refreshOnFrameActivation: Boolean
        get() = state.refreshOnFrameActivation
        set(value) { updateState { it.copy(refreshOnFrameActivation = value) } }

    /** Code suggestions in review comments, with a hidden marker in the
     * comment body and an Apply button for other plugin users. Off by default. */
    var codeSuggestions: Boolean
        get() = state.codeSuggestions
        set(value) { updateState { it.copy(codeSuggestions = value) } }

    /** Comments on a selected range of lines, stored as a hidden marker in the
     * comment body (see `MultiLineRange`); off by default. Ranges written by
     * others are shown either way. */
    var multiLineComments: Boolean
        get() = state.multiLineComments
        set(value) { updateState { it.copy(multiLineComments = value) } }

    /** Offers the Gitea tools to the IDE's MCP server at all. When off, the
     * toolset reports itself disabled and disappears from the MCP Server
     * settings and from the tool list, without an IDE restart. */
    var mcpEnabled: Boolean
        get() = state.mcpEnabled
        set(value) { updateState { it.copy(mcpEnabled = value) } }

    /** Lets MCP clients (AI assistants) change pull requests through the IDE's
     * MCP server: comment, reply, resolve, react, submit reviews. Reading
     * tools are always available. Off by default. */
    var mcpWriteTools: Boolean
        get() = state.mcpWriteTools
        set(value) { updateState { it.copy(mcpWriteTools = value) } }

    /** Debug widget in the status bar counting Gitea API requests. */
    var showRequestCounter: Boolean
        get() = state.showRequestCounter
        set(value) { updateState { it.copy(showRequestCounter = value) } }

    /** Tells [GiteaSettingsListener]s that [before] was replaced by the current state. */
    internal fun notifyChanged(before: State) {
        val after = state
        if (before != after) {
            ApplicationManager.getApplication().messageBus.syncPublisher(GiteaSettingsListener.TOPIC).settingsChanged(before, after)
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(): GiteaSettings = ApplicationManager.getApplication().getService(GiteaSettings::class.java)
    }
}

fun interface GiteaSettingsListener {
    fun settingsChanged(before: GiteaSettings.State, after: GiteaSettings.State)

    companion object {
        @JvmField
        @Topic.AppLevel
        val TOPIC: Topic<GiteaSettingsListener> = Topic(GiteaSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
