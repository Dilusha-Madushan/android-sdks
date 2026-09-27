// Copyright 2026 The ThunderID Authors
// SPDX-License-Identifier: Apache-2.0

package dev.thunderid.compose.management

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResourceStateTest {
    private class Failure : Exception()

    @Test
    fun `refetch publishes data and errors`() =
        runTest {
            var shouldFail = false
            val query =
                ResourceQueryState(listOf("users")) {
                    if (shouldFail) throw Failure()
                    42
                }

            assertEquals(42, query.refetch())
            assertEquals(42, query.data)
            assertNull(query.error)
            assertFalse(query.isLoading)

            shouldFail = true
            assertNull(query.refetch())
            assertTrue(query.error is Failure)
            assertEquals(42, query.data)
        }

    @Test
    fun `invalidation matches on key prefix`() {
        val invalidator = ResourceInvalidator()
        val calls = mutableListOf<String>()
        invalidator.subscribe(listOf("applications", "limit=10")) { calls += "list" }
        invalidator.subscribe(listOf("application", "app-1")) { calls += "detail" }
        val unsubscribe = invalidator.subscribe(listOf("application", "app-2")) { calls += "other" }
        unsubscribe()

        invalidator.invalidate(listOf("applications"))
        invalidator.invalidate(listOf("application", "app-1"))
        invalidator.invalidate(listOf("application", "app-2"))

        assertEquals(listOf("list", "detail"), calls)
    }

    @Test
    fun `mutation invalidates its keys after a success`() =
        runTest {
            val invalidator = ResourceInvalidator()
            val invalidated = mutableListOf<String>()
            invalidator.subscribe(listOf("applications")) { invalidated += "applications" }
            val mutation =
                ResourceMutationState<String, String>(invalidator, { listOf(listOf("applications")) }) { "created $it" }

            assertEquals("created app", mutation.mutate("app"))
            assertEquals("created app", mutation.data)
            assertEquals(listOf("applications"), invalidated)
        }

    @Test
    fun `mutate never throws but mutateThrowing does`() =
        runTest {
            val invalidator = ResourceInvalidator()
            val invalidated = mutableListOf<String>()
            invalidator.subscribe(listOf("users")) { invalidated += "users" }
            val mutation = ResourceMutationState<Int, Int>(invalidator, { listOf(listOf("users")) }) { throw Failure() }

            assertNull(mutation.mutate(1))
            assertTrue(mutation.error is Failure)
            try {
                mutation.mutateThrowing(1)
                fail("Expected a failure")
            } catch (_: Failure) {
            }
            assertTrue(invalidated.isEmpty())

            mutation.reset()
            assertNull(mutation.error)
        }
}
