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
        @JvmField val commitChecksInLog: Boolean = true,
        @JvmField val reviewInEditor: Boolean = true,
        @JvmField val showReactions: Boolean = true,
        @JvmField val suggestPullRequestAfterPush: Boolean = true,
        @JvmField val refreshOnFrameActivation: Boolean = true,
        @JvmField val showRequestCounter: Boolean = false,
    )

    /** "Gitea Commit Checks" column in the Git log and its status requests. */
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
