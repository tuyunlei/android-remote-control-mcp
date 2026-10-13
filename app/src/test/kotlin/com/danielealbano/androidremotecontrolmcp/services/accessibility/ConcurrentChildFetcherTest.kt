package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@DisplayName("ConcurrentChildFetcher")
class ConcurrentChildFetcherTest {
    private val pool = Executors.newFixedThreadPool(ConcurrentChildFetcher.MAX_PARALLELISM)
    private val fetcher = ConcurrentChildFetcher(pool)
    private val strategy = AccessibilityTreeParser.CHILD_PREFETCH_STRATEGY

    @AfterEach
    fun tearDown() {
        pool.shutdownNow()
    }

    private fun parentWith(
        count: Int,
        answer: (Int) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo {
        val parent = mockk<AccessibilityNodeInfo>()
        for (i in 0 until count) {
            every { parent.getChild(i, strategy) } answers { answer(i) }
        }
        return parent
    }

    @Nested
    @DisplayName("fetch")
    inner class Fetch {
        @Test
        @DisplayName("returns children in index order even when replies arrive out of order")
        fun returnsChildrenInIndexOrder() {
            val count = 8
            val children = List(count) { mockk<AccessibilityNodeInfo>() }
            // Later indices answer first.
            val parent =
                parentWith(count) { i ->
                    Thread.sleep((count - i) * 5L)
                    children[i]
                }

            val result = fetcher.fetch(parent, count, parallelism = count)

            assertEquals(count, result.size)
            result.forEachIndexed { i, node -> assertSame(children[i], node) }
        }

        @Test
        @DisplayName("issues the getChild calls of one node concurrently")
        fun issuesCallsConcurrently() {
            val count = 4
            // Each call only succeeds if all four are in flight at once; a serial loop would time out.
            val allInFlight = CountDownLatch(count)
            val parent =
                parentWith(count) {
                    allInFlight.countDown()
                    check(allInFlight.await(2, TimeUnit.SECONDS)) { "calls were not concurrent" }
                    mockk()
                }

            val result = fetcher.fetch(parent, count, parallelism = count)

            assertEquals(count, result.filterNotNull().size)
        }

        @Test
        @DisplayName("never has more calls in flight than the parallelism bound")
        fun respectsParallelismBound() {
            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            val parent =
                parentWith(20) {
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    Thread.sleep(10)
                    inFlight.decrementAndGet()
                    mockk()
                }

            fetcher.fetch(parent, 20, parallelism = 3)

            assertEquals(3, maxInFlight.get())
        }

        @Test
        @DisplayName("stays on the calling thread for a single child or parallelism 1")
        fun staysOnCallerThreadWhenNothingToOverlap() {
            val failingExecutor = Executor { error("executor must not be used") }
            val serialFetcher = ConcurrentChildFetcher(failingExecutor)
            val threads = Collections.synchronizedList(mutableListOf<Thread>())
            val parent =
                parentWith(5) {
                    threads.add(Thread.currentThread())
                    mockk()
                }

            serialFetcher.fetch(parent, 1, parallelism = 16)
            serialFetcher.fetch(parent, 5, parallelism = 1)

            assertEquals(6, threads.size)
            assertTrue(threads.all { it === Thread.currentThread() })
        }

        @Test
        @DisplayName("keeps null children at their index")
        fun keepsNullChildrenAtTheirIndex() {
            val child = mockk<AccessibilityNodeInfo>()
            val parent = parentWith(3) { i -> if (i == 1) null else child }

            val result = fetcher.fetch(parent, 3, parallelism = 3)

            assertSame(child, result[0])
            assertNull(result[1])
            assertSame(child, result[2])
        }

        @Test
        @DisplayName("rethrows the lowest-index failure after every call finished")
        fun rethrowsLowestIndexFailure() {
            val parent =
                parentWith(6) { i ->
                    when (i) {
                        2 -> throw IllegalStateException("child 2")
                        4 -> throw IllegalArgumentException("child 4")
                        else -> mockk()
                    }
                }

            val thrown = assertThrows<IllegalStateException> { fetcher.fetch(parent, 6, parallelism = 6) }

            assertEquals("child 2", thrown.message)
            for (i in 0 until 6) verify(exactly = 1) { parent.getChild(i, strategy) }
        }

        @Test
        @DisplayName("returns an empty list for a node without children")
        fun emptyForNoChildren() {
            assertEquals(emptyList<AccessibilityNodeInfo?>(), fetcher.fetch(mockk(), 0, parallelism = 16))
        }
    }

    @Nested
    @DisplayName("parallelismFrom")
    inner class ParallelismFrom {
        @Test
        @DisplayName("defaults when unset or invalid, clamps to the pool size")
        fun parsesOverride() {
            val default = ConcurrentChildFetcher.DEFAULT_PARALLELISM
            assertEquals(default, ConcurrentChildFetcher.parallelismFrom(null))
            assertEquals(default, ConcurrentChildFetcher.parallelismFrom(""))
            assertEquals(default, ConcurrentChildFetcher.parallelismFrom("abc"))
            assertEquals(default, ConcurrentChildFetcher.parallelismFrom("0"))
            assertEquals(default, ConcurrentChildFetcher.parallelismFrom("-3"))
            assertEquals(1, ConcurrentChildFetcher.parallelismFrom("1"))
            assertEquals(8, ConcurrentChildFetcher.parallelismFrom(" 8 "))
            assertEquals(ConcurrentChildFetcher.MAX_PARALLELISM, ConcurrentChildFetcher.parallelismFrom("999"))
        }
    }
}
