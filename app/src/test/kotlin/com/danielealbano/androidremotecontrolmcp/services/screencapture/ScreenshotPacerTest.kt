package com.danielealbano.androidremotecontrolmcp.services.screencapture

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("ScreenshotPacer")
class ScreenshotPacerTest {
    private fun TestScope.pacer() = ScreenshotPacer(ScreenshotPacer.FRAMEWORK_MIN_INTERVAL_MS) { currentTime }

    /** Starts one capture that takes [durationMs] and returns the virtual time it started at. */
    private suspend fun TestScope.capture(
        pacer: ScreenshotPacer,
        durationMs: Long = 20,
    ): Long =
        pacer.paced {
            val started = currentTime
            delay(durationMs)
            started
        }

    @Test
    fun `framework interval is one past the rejected 333 ms`() {
        assertEquals(334L, ScreenshotPacer.FRAMEWORK_MIN_INTERVAL_MS)
    }

    @Test
    fun `first capture starts immediately`() =
        runTest {
            assertEquals(0L, capture(pacer()))
        }

    @Test
    fun `back-to-back capture waits the interval from when the previous one finished`() =
        runTest {
            val pacer = pacer()
            capture(pacer, durationMs = 20)
            // Previous finished at 20; the framework stamped it at or before then.
            assertEquals(20L + 334L, capture(pacer))
        }

    @Test
    fun `capture after the interval has passed does not wait`() =
        runTest {
            val pacer = pacer()
            capture(pacer, durationMs = 20)
            delay(500)
            assertEquals(520L, capture(pacer))
        }

    @Test
    fun `partial wait covers only what is left of the interval`() =
        runTest {
            val pacer = pacer()
            capture(pacer, durationMs = 20)
            delay(300)
            assertEquals(354L, capture(pacer))
        }

    @Test
    fun `concurrent captures are serialized and each spaced`() =
        runTest {
            val pacer = pacer()
            val starts = List(3) { async { capture(pacer, durationMs = 20) } }.awaitAll()
            assertEquals(listOf(0L, 354L, 708L), starts.sorted())
        }

    @Test
    fun `a failed capture still counts as the previous request`() =
        runTest {
            val pacer = pacer()
            assertThrows<IllegalStateException> {
                pacer.paced<Unit> {
                    delay(10)
                    error("capture failed")
                }
            }
            assertEquals(10L + 334L, capture(pacer))
        }
}
