package com.danielealbano.androidremotecontrolmcp.services.accessibility

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Diagnostic-only switch for the child-fetch prefetch strategy used by [AccessibilityTreeParser].
 *
 * Read once per window per screen read from the system property [PROPERTY] (settable without root:
 * `adb shell setprop debug.arc.prefetch bfs`). Unset/empty/unknown => the parser's normal strategy
 * and no statistics, i.e. exactly the non-diagnostic behaviour. Any recognised value also turns on
 * [PrefetchStats] logging under [PrefetchStats.TAG].
 */
internal object PrefetchStrategySwitch {
    const val PROPERTY = "debug.arc.prefetch"

    private const val U = AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

    /** Named strategies. "hybrid" is what the no-argument [AccessibilityNodeInfo.getChild] uses. */
    val NAMED: Map<String, Int> =
        mapOf(
            "dfs" to (AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_DEPTH_FIRST or U),
            "bfs" to (AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST or U),
            "hybrid" to AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID,
            "hybrid_u" to (AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or U),
            "siblings" to (AccessibilityNodeInfo.FLAG_PREFETCH_SIBLINGS or U),
            "none" to 0,
        )

    /**
     * Returns the prefetch flags for [value], or null to keep the default. Accepts a name from
     * [NAMED] or a decimal flag set within [AccessibilityNodeInfo.FLAG_PREFETCH_MASK] with at most
     * one descendant strategy (the framework throws on more than one).
     */
    fun parse(value: String?): Int? {
        val v = value?.trim()?.lowercase().orEmpty()
        return NAMED[v] ?: v.toIntOrNull()?.takeIf(::isAcceptedFlagSet)
    }

    private fun isAcceptedFlagSet(flags: Int): Boolean {
        val descendants = flags and AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_MASK
        return flags >= 0 &&
            flags and AccessibilityNodeInfo.FLAG_PREFETCH_MASK.inv() == 0 &&
            descendants and (descendants - 1) == 0
    }

    /** Reads an Android system property; null if unavailable (e.g. JVM unit tests). */
    fun readSystemProperty(key: String): String? =
        runCatching {
            Class
                .forName("android.os.SystemProperties")
                .getMethod("get", String::class.java)
                .invoke(null, key) as String?
        }.getOrNull()
}

/**
 * Per-window counters for one tree walk under a diagnostic strategy. Counts and class names only,
 * never text. A [AccessibilityNodeInfo.getChild] answered from the framework node cache takes
 * microseconds; one that goes to the app takes at least a binder round trip (milliseconds), so a
 * call is classified as a miss when it takes at least [MISS_THRESHOLD_NS]; [histogram] lets the
 * threshold be checked against the actual timing distribution.
 */
internal class PrefetchStats(
    private val strategyName: String,
    private val strategyFlags: Int,
) {
    internal class ClassStats {
        var misses = 0
        var missNs = 0L
        var served = 0 // cache hits walked after a miss on this class, before the next miss
        var real = 0
        var virtual = 0
        var unknownKind = 0
        var sameHostAsParent = 0
        var visible = 0
        var withChildren = 0
        var parentFromCache = 0
    }

    var getChildCalls = 0
        private set
    var misses = 0
        private set
    var hits = 0
        private set
    var nullChildren = 0
        private set
    var missNs = 0L
        private set
    val histogram = IntArray(HISTOGRAM_BOUNDS_NS.size + 1)
    val byClass = linkedMapOf<String, ClassStats>()
    private var lastMissClass: ClassStats? = null

    /**
     * Records one getChild. [parentFromCache] says whether [parent] itself was a cache hit (true)
     * or fetched live; a miss below a cache-served parent means the parent's batch did not carry
     * this child.
     */
    fun record(
        parent: AccessibilityNodeInfo,
        parentFromCache: Boolean,
        child: AccessibilityNodeInfo?,
        elapsedNs: Long,
    ): Boolean {
        getChildCalls++
        histogram[HISTOGRAM_BOUNDS_NS.count { elapsedNs >= it }]++
        if (child == null) {
            nullChildren++
            return false
        }
        val isMiss = elapsedNs >= MISS_THRESHOLD_NS
        if (!isMiss) {
            hits++
            lastMissClass?.let { it.served++ }
            return true
        }
        misses++
        missNs += elapsedNs
        val cs = byClass.getOrPut(child.className?.toString() ?: "null") { ClassStats() }
        lastMissClass = cs
        cs.misses++
        cs.missNs += elapsedNs
        val childId = sourceNodeId(child)
        val parentId = sourceNodeId(parent)
        when {
            childId == null -> cs.unknownKind++
            virtualDescendantId(childId) == AccessibilityNodeInfo.UNDEFINED_ITEM_ID -> cs.real++
            else -> cs.virtual++
        }
        if (childId != null && parentId != null && viewId(childId) == viewId(parentId)) {
            cs.sameHostAsParent++
        }
        if (child.isVisibleToUser) cs.visible++
        if (child.childCount > 0) cs.withChildren++
        if (parentFromCache) cs.parentFromCache++
        return false
    }

    fun log(packageName: String?) {
        Log.i(TAG, summaryLine(packageName))
        byClass.entries
            .sortedByDescending { it.value.misses }
            .take(MAX_CLASS_LINES)
            .forEach { (cls, s) -> Log.i(TAG, classLine(cls, s)) }
    }

    fun summaryLine(packageName: String?): String =
        "read pkg=$packageName strategy=$strategyName flags=$strategyFlags calls=$getChildCalls " +
            "misses=$misses hits=$hits null=$nullChildren missMs=${missNs / NS_PER_MS} " +
            "hist=${histogram.joinToString(",")}"

    private fun classLine(
        cls: String,
        s: ClassStats,
    ): String =
        "class cls=$cls misses=${s.misses} missMs=${s.missNs / NS_PER_MS} served=${s.served} " +
            "real=${s.real} virtual=${s.virtual} unknownKind=${s.unknownKind} " +
            "sameHost=${s.sameHostAsParent} visible=${s.visible} withChildren=${s.withChildren} " +
            "parentFromCache=${s.parentFromCache}"

    companion object {
        const val TAG = "ARC_PREFETCH"

        /** Cache hit = local lookup + node copy (µs); miss = binder to system_server and the app. */
        const val MISS_THRESHOLD_NS = 500_000L

        /** Histogram buckets: <100 µs, <500 µs, <2 ms, <10 ms, >=10 ms. */
        val HISTOGRAM_BOUNDS_NS = longArrayOf(100_000L, 500_000L, 2_000_000L, 10_000_000L)
        private const val MAX_CLASS_LINES = 15
        private const val NS_PER_MS = 1_000_000L
        private const val VIRTUAL_DESCENDANT_ID_SHIFT = 32

        // getSourceNodeId() is @hide @UnsupportedAppUsage @TestApi (greylisted), so reflection works on
        // device; null on failure.
        private val getSourceNodeId by lazy {
            runCatching { AccessibilityNodeInfo::class.java.getMethod("getSourceNodeId") }.getOrNull()
        }

        fun sourceNodeId(node: AccessibilityNodeInfo): Long? =
            runCatching { getSourceNodeId?.invoke(node) as Long? }.getOrNull()

        fun viewId(nodeId: Long): Int = nodeId.toInt()

        fun virtualDescendantId(nodeId: Long): Int = (nodeId ushr VIRTUAL_DESCENDANT_ID_SHIFT).toInt()
    }
}

/** The getChild prefetch flags for one walk, plus counters when the diagnostic switch is set. */
internal class ChildFetch(
    val strategy: Int,
    val stats: PrefetchStats?,
) {
    companion object {
        val DEFAULT = ChildFetch(AccessibilityTreeParser.CHILD_PREFETCH_STRATEGY, null)

        /** [ChildFetch] for the switch value [raw]: [DEFAULT] when unset or unrecognised. */
        fun fromSwitch(raw: String?): ChildFetch {
            val flags = PrefetchStrategySwitch.parse(raw) ?: return DEFAULT
            return ChildFetch(flags, PrefetchStats(raw.orEmpty().trim().lowercase(), flags))
        }
    }
}
