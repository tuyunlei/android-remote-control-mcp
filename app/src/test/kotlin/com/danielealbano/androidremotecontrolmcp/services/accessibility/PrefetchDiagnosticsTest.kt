package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("Prefetch diagnostics")
class PrefetchDiagnosticsTest {
    private val dfsU =
        AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE
    private val bfsU =
        AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST or
            AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Nested
    @DisplayName("PrefetchStrategySwitch.parse")
    inner class Parse {
        @Test
        fun `unset, blank or unknown keeps the default`() {
            assertNull(PrefetchStrategySwitch.parse(null))
            assertNull(PrefetchStrategySwitch.parse(""))
            assertNull(PrefetchStrategySwitch.parse("  "))
            assertNull(PrefetchStrategySwitch.parse("fastest"))
        }

        @Test
        fun `named strategies map to framework flags`() {
            assertEquals(dfsU, PrefetchStrategySwitch.parse("dfs"))
            assertEquals(bfsU, PrefetchStrategySwitch.parse(" BFS "))
            assertEquals(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID,
                PrefetchStrategySwitch.parse("hybrid"),
            )
            assertEquals(
                AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
                    AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE,
                PrefetchStrategySwitch.parse("hybrid_u"),
            )
            assertEquals(
                AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE,
                PrefetchStrategySwitch.parse("siblings"),
            )
            assertEquals(0, PrefetchStrategySwitch.parse("none"))
        }

        @Test
        fun `numeric flags are accepted only when the framework would accept them`() {
            // SIBLINGS | DEPTH_FIRST | UNINTERRUPTIBLE
            assertEquals(42, PrefetchStrategySwitch.parse("42"))
            // DEPTH_FIRST | BREADTH_FIRST: framework throws on two descendant strategies
            assertNull(PrefetchStrategySwitch.parse("24"))
            // outside FLAG_PREFETCH_MASK
            assertNull(PrefetchStrategySwitch.parse("64"))
            assertNull(PrefetchStrategySwitch.parse("-1"))
        }

        @Test
        fun `system property read fails closed to null off device`() {
            assertNull(PrefetchStrategySwitch.readSystemProperty(PrefetchStrategySwitch.PROPERTY))
        }
    }

    @Nested
    @DisplayName("parser uses the switch")
    inner class ParserSwitch {
        private fun leaf(): AccessibilityNodeInfo =
            mockk<AccessibilityNodeInfo>(relaxed = true).also {
                every { it.childCount } returns 0
                every { it.className } returns "android.view.View"
                every { it.getBoundsInScreen(any<Rect>()) } returns Unit
            }

        private fun parentOf(child: AccessibilityNodeInfo): AccessibilityNodeInfo =
            mockk<AccessibilityNodeInfo>(relaxed = true).also {
                every { it.childCount } returns 1
                every { it.getChild(0, any()) } returns child
                every { it.getBoundsInScreen(any<Rect>()) } returns Unit
            }

        private fun parseWith(value: String?): AccessibilityNodeInfo {
            val parent = parentOf(leaf())
            AccessibilityTreeParser().apply { readProperty = { value } }.parseTree(parent)
            return parent
        }

        @Test
        fun `unset switch uses the normal strategy and logs nothing`() {
            val parent = parseWith(null)
            verify(exactly = 1) { parent.getChild(0, dfsU) }
            verify(exactly = 0) { Log.i(PrefetchStats.TAG, any()) }
        }

        @Test
        fun `unrecognised value behaves like unset`() {
            val parent = parseWith("garbage")
            verify(exactly = 1) { parent.getChild(0, dfsU) }
            verify(exactly = 0) { Log.i(PrefetchStats.TAG, any()) }
        }

        @Test
        fun `bfs value switches the getChild flags and logs one summary`() {
            val parent = parseWith("bfs")
            verify(exactly = 1) { parent.getChild(0, bfsU) }
            verify(exactly = 1) { Log.i(PrefetchStats.TAG, match { it.startsWith("read ") && "strategy=bfs" in it }) }
        }

        @Test
        fun `recheck is not issued for cache hits and is off unless set to 1`() {
            val parent = parentOf(leaf())
            AccessibilityTreeParser()
                .apply {
                    readProperty = { key -> if (key == PrefetchStrategySwitch.PROPERTY) "bfs" else "1" }
                }.parseTree(parent)
            // relaxed mocks answer instantly, so the first call is classified a hit: no recheck
            verify(exactly = 1) { parent.getChild(0, bfsU) }
            verify(exactly = 0) { parent.getChild(0, 0) }
            assertEquals(true, ChildFetch.fromSwitch("bfs", "1").stats?.recheck)
            assertEquals(false, ChildFetch.fromSwitch("bfs", null).stats?.recheck)
        }

        @Test
        fun `none value fetches children without prefetch`() {
            val parent = parseWith("none")
            verify(exactly = 1) { parent.getChild(0, 0) }
        }

        @Test
        fun `default ChildFetch keeps the batched strategy without stats`() {
            assertSame(ChildFetch.DEFAULT, ChildFetch.fromSwitch(null))
            assertEquals(AccessibilityTreeParser.CHILD_PREFETCH_STRATEGY, ChildFetch.DEFAULT.strategy)
            assertNull(ChildFetch.DEFAULT.stats)
        }
    }

    @Nested
    @DisplayName("PrefetchStats")
    inner class Stats {
        private fun node(
            cls: String,
            visible: Boolean = false,
            childCount: Int = 0,
        ): AccessibilityNodeInfo =
            mockk<AccessibilityNodeInfo>(relaxed = true).also {
                every { it.className } returns cls
                every { it.isVisibleToUser } returns visible
                every { it.childCount } returns childCount
                every { it.text } returns "secret screen text"
            }

        @Test
        fun `classifies by elapsed time, attributes hits to the last miss, buckets the histogram`() {
            val stats = PrefetchStats("dfs", 40)
            val parent = node("P")
            val a = node("A", visible = true, childCount = 2)

            assertFalse(stats.record(parent, false, a, 3_000_000L)) // miss, 2-10 ms bucket
            assertTrue(stats.record(a, false, node("B"), 20_000L)) // hit, <100 µs
            assertTrue(stats.record(a, false, node("B"), 200_000L)) // hit, <500 µs
            assertFalse(stats.record(a, true, node("C"), 12_000_000L)) // miss, >=10 ms
            assertFalse(stats.record(a, false, null, 1_000L)) // null child

            assertEquals(5, stats.getChildCalls)
            assertEquals(2, stats.misses)
            assertEquals(2, stats.hits)
            assertEquals(1, stats.nullChildren)
            assertEquals(15_000_000L, stats.missNs)
            assertArrayEquals(intArrayOf(2, 1, 0, 1, 1), stats.histogram)
            val classA = stats.byClass.getValue("A")
            assertEquals(1, classA.misses)
            assertEquals(2, classA.served)
            assertEquals(1, classA.visible)
            assertEquals(1, classA.withChildren)
            assertEquals(0, classA.parentFromCache)
            assertEquals(1, stats.byClass.getValue("C").parentFromCache)
            assertEquals(0, stats.byClass.getValue("C").served)
        }

        @Test
        fun `recheck timings are attributed to the last missed class`() {
            val stats = PrefetchStats("dfs", 40, recheck = true)
            stats.recordRecheck(5_000_000L) // before any miss: ignored
            stats.record(node("P"), true, node("A"), 3_000_000L)
            stats.recordRecheck(20_000L)
            stats.record(node("P"), true, node("A"), 3_000_000L)
            stats.recordRecheck(4_000_000L)

            val classA = stats.byClass.getValue("A")
            assertEquals(1, classA.recheckHit)
            assertEquals(1, classA.recheckMiss)
        }

        @Test
        fun `node id helpers split view id and virtual id`() {
            // makeNodeId(viewId, HOST_VIEW_ID): what View.createAccessibilityNodeInfo produces
            val realId = (PrefetchStats.HOST_VIEW_ID.toLong() shl 32) or 1234L
            assertEquals(1234, PrefetchStats.viewId(realId))
            assertEquals(PrefetchStats.HOST_VIEW_ID, PrefetchStats.virtualDescendantId(realId))
            val virtualId = (7L shl 32) or 1234L
            assertEquals(7, PrefetchStats.virtualDescendantId(virtualId))
            assertEquals(1234, PrefetchStats.viewId(virtualId))
        }

        @Test
        fun `log lines carry counts and class names, never node text`() {
            val stats = PrefetchStats("dfs", 40)
            stats.record(node("P"), false, node("com.example.Cell"), 3_000_000L)
            val lines = mutableListOf<String>()
            every { Log.i(PrefetchStats.TAG, capture(lines)) } returns 0

            stats.log("com.example")

            assertEquals(2, lines.size)
            assertTrue(lines[0].startsWith("read pkg=com.example strategy=dfs flags=40 calls=1 misses=1"))
            assertTrue(lines[1].startsWith("class cls=com.example.Cell misses=1"))
            assertTrue(lines.none { "secret" in it })
        }
    }
}
