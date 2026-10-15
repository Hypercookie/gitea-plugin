package com.github.hypercookie.giteaplugin.pullrequest.review

import com.github.hypercookie.giteaplugin.api.dto.ChangedFile

/** Where the merge-base version of a file in a pull request comes from,
 * which the editor's PR change markers diff against. */
sealed interface PullRequestBase {

    /** The pull request added the file, so its base version is empty. */
    data object Added : PullRequestBase

    /** The base version is [path] at the merge base. */
    data class At(val path: String) : PullRequestBase

    companion object {
        /**
         * Base of the file at new path [path], given its entry in the PR's
         * changed files (null when it isn't listed or the list couldn't be
         * loaded). A renamed or moved file's base is its previous path; an
         * unlisted file is never assumed to be added.
         */
        fun of(file: ChangedFile?, path: String): PullRequestBase = when {
            file == null -> At(path)
            file.status == "added" -> Added
            else -> At(file.previousFilename ?: file.filename)
        }
    }
}
