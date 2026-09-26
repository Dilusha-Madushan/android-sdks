// Copyright 2026 The ThunderID Authors
// SPDX-License-Identifier: Apache-2.0

package dev.thunderid.compose.management

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets mutations tell live queries that their data is stale. Invalidation matches on key prefix, so
 * invalidating `listOf("applications")` reaches every applications list query regardless of its pagination.
 */
class ResourceInvalidator {
    private class Subscription(
        val key: List<String>,
        val listener: () -> Unit,
    )

    private val subscriptions = CopyOnWriteArrayList<Subscription>()

    /** Notifies every subscriber whose key starts with [prefix]. */
    fun invalidate(prefix: List<String>) {
        subscriptions
            .filter { it.key.size >= prefix.size && it.key.subList(0, prefix.size) == prefix }
            .forEach { it.listener() }
    }

    /** Registers [listener] for invalidations matching [key]. Returns a function that unsubscribes. */
    fun subscribe(
        key: List<String>,
        listener: () -> Unit,
    ): () -> Unit {
        val subscription = Subscription(key, listener)
        subscriptions.add(subscription)
        return { subscriptions.remove(subscription) }
    }
}

/**
 * A management resource and its loading state. Depends on no data fetching library: requests run
 * through the SDK's management API.
 */
@Stable
class ResourceQueryState<T> internal constructor(
    /** Identifies the data. Mutations invalidate by key prefix. */
    val key: List<String>,
    private val fetch: suspend () -> T,
) {
    var data by mutableStateOf<T?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set

    private var latestRequest = 0

    /**
     * Fetches the resource. Only the latest request updates state, so a slow earlier response cannot
     * overwrite a newer one. Returns the new data, or `null` if the request failed.
     */
    suspend fun refetch(): T? {
        val request = ++latestRequest
        isLoading = true
        return try {
            fetch().also {
                if (request == latestRequest) {
                    data = it
                    error = null
                }
            }
        } catch (e: Exception) {
            if (request == latestRequest) error = e
            null
        } finally {
            if (request == latestRequest) isLoading = false
        }
    }
}

/**
 * A management mutation that invalidates the queries whose data it changed. It produces no user-visible
 * side effect: success and failure are reported through its state and return value.
 */
@Stable
class ResourceMutationState<I, O> internal constructor(
    private val invalidator: ResourceInvalidator,
    private val invalidatedKeys: (I) -> List<List<String>>,
    private val perform: suspend (I) -> O,
) {
    var data by mutableStateOf<O?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var isLoading by mutableStateOf(false)
        private set

    /** Runs the mutation. Never throws: read [error] to handle a failure. */
    suspend fun mutate(input: I): O? = runCatching { mutateThrowing(input) }.getOrNull()

    /** Runs the mutation and throws if it fails. */
    suspend fun mutateThrowing(input: I): O {
        isLoading = true
        error = null
        return try {
            perform(input).also {
                data = it
                invalidatedKeys(input).forEach(invalidator::invalidate)
            }
        } catch (e: Exception) {
            error = e
            throw e
        } finally {
            isLoading = false
        }
    }

    /** Clears [data] and [error]. */
    fun reset() {
        data = null
        error = null
    }
}
