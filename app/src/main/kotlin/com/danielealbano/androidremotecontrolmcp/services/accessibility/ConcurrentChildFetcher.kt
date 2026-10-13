package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches every child of one accessibility node, with up to `parallelism` of the
 * [AccessibilityNodeInfo.getChild] calls in flight at once.
 *
 * Why: a child that is not in the framework node cache costs one round trip to the target app,
 * and the target handles it on its main thread through a plain (synchronous) Handler message.
 * While the app keeps a traversal scheduled, the view root's sync barrier blocks that message
 * until the next frame, so a serial walk pays about one frame per miss. Wide, flat screens
 * (many leaves under few containers) overflow the 50-node prefetch cap and leave most leaves to
 * be fetched one by one. Issuing a container's misses together lets the app's main thread drain
 * them all after a single barrier: one frame per wave of `parallelism` misses instead of one
 * per miss.
 *
 * Safe because the framework keeps one AccessibilityInteractionClient per calling thread (its
 * reply slot and interaction id are per instance), the shared node cache is internally locked,
 * and the system server forwards each request as an independent oneway call. Results are
 * returned in child-index order, so callers see exactly what a serial loop would produce.
 * Exceptions from getChild are rethrown on the calling thread (lowest failing index first),
 * after all in-flight calls have finished.
 */
internal class ConcurrentChildFetcher(
    private val executor: Executor = sharedExecutor,
) {
    fun fetch(
        node: AccessibilityNodeInfo,
        childCount: Int,
        parallelism: Int,
    ): List<AccessibilityNodeInfo?> {
        val workers = minOf(parallelism, childCount)
        if (workers <= 1) {
            return List(childCount) { node.getChild(it, AccessibilityTreeParser.CHILD_PREFETCH_STRATEGY) }
        }
        val results = arrayOfNulls<AccessibilityNodeInfo>(childCount)
        val errors = arrayOfNulls<Throwable>(childCount)
        val nextIndex = AtomicInteger(0)
        val finished = CountDownLatch(workers)
        repeat(workers) {
            executor.execute {
                try {
                    var i = nextIndex.getAndIncrement()
                    while (i < childCount) {
                        try {
                            results[i] = node.getChild(i, AccessibilityTreeParser.CHILD_PREFETCH_STRATEGY)
                        } catch (
                            @Suppress("TooGenericExceptionCaught") t: Throwable,
                        ) {
                            errors[i] = t
                        }
                        i = nextIndex.getAndIncrement()
                    }
                } finally {
                    finished.countDown()
                }
            }
        }
        // CountDownLatch.await() happens-after every countDown(), so the array writes are visible.
        finished.await()
        errors.firstOrNull { it != null }?.let { throw it }
        return results.asList()
    }

    companion object {
        /**
         * Upper bound on getChild calls in flight per container, and the pool size.
         *
         * Each in-flight call parks one pool thread on its reply and, once the barrier lifts, costs
         * the target app one node build on its main thread; the app drains everything queued back
         * to back. On the slow page this targets (Meituan MSC) a build is ~3 ms (main thread p50
         * 3.05 ms per request), so 16 keeps one drain near 50 ms (about 3 frames at 60 Hz) while
         * cutting a 22-leaf container from 22 barrier waits to 2. 32 allows the debug override
         * below to measure the trade-off without a rebuild.
         */
        const val DEFAULT_PARALLELISM = 16
        const val MAX_PARALLELISM = 32

        /** Debug override for [DEFAULT_PARALLELISM], `adb shell setprop debug.arc.walk.parallelism N`; 1 = serial. */
        const val PARALLELISM_PROPERTY = "debug.arc.walk.parallelism"

        /**
         * Long-lived fixed pool: the framework caches one AccessibilityInteractionClient per thread
         * id and never evicts it, so short-lived threads would leak one client each. A fixed pool
         * bounds that to [MAX_PARALLELISM] instances. Tasks never wait on other tasks, so a
         * saturated pool only queues work; it cannot deadlock.
         */
        val sharedExecutor: ExecutorService by lazy {
            Executors.newFixedThreadPool(MAX_PARALLELISM, WalkerThreadFactory())
        }

        /** Parses the debug override; null, blank, or invalid values fall back to the default. */
        fun parallelismFrom(value: String?): Int =
            value
                ?.trim()
                ?.toIntOrNull()
                ?.takeIf { it >= 1 }
                ?.coerceAtMost(MAX_PARALLELISM)
                ?: DEFAULT_PARALLELISM

        /** Reads [PARALLELISM_PROPERTY]; null where android.os.SystemProperties is unavailable (JVM tests). */
        fun readParallelismProperty(): String? =
            runCatching {
                Class
                    .forName("android.os.SystemProperties")
                    .getMethod("get", String::class.java)
                    .invoke(null, PARALLELISM_PROPERTY) as String?
            }.getOrNull()
    }

    private class WalkerThreadFactory : ThreadFactory {
        private val count = AtomicInteger(0)

        override fun newThread(r: Runnable): Thread {
            val thread = Thread(r, "arc-a11y-walk-${count.incrementAndGet()}")
            thread.isDaemon = true
            return thread
        }
    }
}
