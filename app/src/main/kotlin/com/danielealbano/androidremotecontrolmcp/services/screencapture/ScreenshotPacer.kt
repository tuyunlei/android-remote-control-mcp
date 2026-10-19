package com.danielealbano.androidremotecontrolmcp.services.screencapture

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Spaces [AccessibilityService.takeScreenshot] requests so the framework never rejects one as too soon.
 *
 * The framework fails a request that arrives within
 * `AccessibilityService.ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS` (333 ms) of the
 * previous accepted one with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` (error code 3), which this
 * server used to report as "Screenshot capture failed". Back-to-back screen reads with screenshots
 * hit it every time (measured on an API 36 emulator: a read started under 310 ms after the previous
 * one failed 32 of 32 times, at 350 ms or more 0 of 16).
 *
 * The interval is counted from when the previous capture *finished*. The framework stamps a request
 * when it accepts it, which is before its callback reaches us, so waiting [minIntervalMs] from the
 * callback always puts the next request more than [minIntervalMs] after the framework's stamp,
 * whatever the IPC latency. Calls are serialized so concurrent reads queue instead of colliding.
 *
 * @param minIntervalMs Minimum time between the end of one capture and the start of the next.
 * @param now Monotonic clock in milliseconds (production: `SystemClock.uptimeMillis`, the clock the
 *   framework uses for the same check).
 */
class ScreenshotPacer(
    private val minIntervalMs: Long,
    private val now: () -> Long,
) {
    private val mutex = Mutex()
    private var lastFinishedAt: Long? = null

    suspend fun <T> paced(capture: suspend () -> T): T =
        mutex.withLock {
            lastFinishedAt?.let { finished ->
                val wait = finished + minIntervalMs - now()
                if (wait > 0) delay(wait)
            }
            try {
                capture()
            } finally {
                lastFinishedAt = now()
            }
        }

    companion object {
        /** The framework rejects an interval `<= 333` ms, so the first accepted one is 334. */
        const val FRAMEWORK_MIN_INTERVAL_MS = 334L
    }
}
