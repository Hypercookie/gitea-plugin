package com.github.hypercookie.giteaplugin.util

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job

/**
 * A child scope that is cancelled with its parent but can also be cancelled
 * on its own, and whose failing children don't cancel siblings.
 *
 * The platform's `com.intellij.platform.util.coroutines.childScope` does the
 * same but carries `@ApiStatus.Internal`/`@Experimental` members (migration
 * ledger I12), so this plugin uses its own.
 */
fun CoroutineScope.giteaChildScope(name: String): CoroutineScope =
    CoroutineScope(coroutineContext + SupervisorJob(coroutineContext.job) + CoroutineName(name))
