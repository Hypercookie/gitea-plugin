package com.github.hypercookie.giteaplugin.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A child scope that is cancelled with its parent but can also be cancelled
 * on its own, and whose failing children don't cancel siblings. [context]
 * is added on top of the parent's, e.g. a dispatcher.
 *
 * The platform's `com.intellij.platform.util.coroutines.childScope` does the
 * same but carries `@ApiStatus.Internal`/`@Experimental` members, so this plugin uses its own.
 */
fun CoroutineScope.giteaChildScope(name: String, context: CoroutineContext = EmptyCoroutineContext): CoroutineScope =
    CoroutineScope(coroutineContext + context + SupervisorJob(coroutineContext.job) + CoroutineName(name))

/**
 * [runCatching] for code that suspends: a [CancellationException] is
 * rethrown instead of becoming a failed [Result], so a cancelled coroutine
 * stops instead of carrying on as if a request had failed.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
