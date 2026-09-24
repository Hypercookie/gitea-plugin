package com.github.hypercookie.giteaplugin.accounts

import java.util.UUID

/**
 * A configured Gitea server + account identity. Mutable var properties and a
 * no-arg constructor are required for IntelliJ's XML state persistence
 * (XmlSerializerUtil) to (de)serialize instances via reflection.
 */
class GiteaAccount() {
    var id: String = UUID.randomUUID().toString()
    var name: String = ""
    var serverUrl: String = ""

    constructor(name: String, serverUrl: String) : this() {
        this.name = name
        this.serverUrl = serverUrl.trimEnd('/')
    }

    override fun equals(other: Any?): Boolean = other is GiteaAccount && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = if (name.isNotBlank()) name else serverUrl
}
