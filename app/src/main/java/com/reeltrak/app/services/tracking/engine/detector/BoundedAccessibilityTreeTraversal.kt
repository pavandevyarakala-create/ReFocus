package com.reeltrak.app.services.tracking.engine.detector

/** Result for a bounded breadth-first walk over owned accessibility-node references. */
internal data class BoundedAccessibilityTraversalResult(
    val nodesVisited: Int,
    val wasTruncated: Boolean,
    val hadAccessFailure: Boolean
)

/**
 * Bounded BFS shared by detector discovery and metadata extraction. The caller owns [root] and
 * may retain the matched node when [retainMatchedNode] returns true. All other returned child
 * references are recycled, including queued nodes when a property read fails or traversal stops.
 */
internal fun <N : Any> boundedAccessibilityBreadthFirst(
    root: N,
    maxNodes: Int,
    childCount: (N) -> Int,
    childAt: (N, Int) -> N?,
    inspect: (N) -> Boolean,
    retainMatchedNode: (N) -> Boolean = { false },
    recycle: (N) -> Unit
): BoundedAccessibilityTraversalResult {
    val queue = ArrayDeque<N>()
    queue.add(root)
    var visited = 0
    var hadAccessFailure = false
    var stoppedOnMatch = false
    var wasTruncated = false
    var retainedNode: N? = null

    try {
        while (queue.isNotEmpty() && visited < maxNodes) {
            val node = queue.removeFirst()
            visited++

            val matched = try {
                inspect(node)
            } catch (_: Exception) {
                hadAccessFailure = true
                false
            }

            if (matched) {
                stoppedOnMatch = true
                if (retainMatchedNode(node)) retainedNode = node
                if (node !== root && node !== retainedNode) safeRecycle(node, recycle)
                break
            }

            val count = try {
                childCount(node).coerceAtLeast(0)
            } catch (_: Exception) {
                hadAccessFailure = true
                0
            }

            for (index in 0 until count) {
                try {
                    val child = childAt(node, index)
                    if (child == null) hadAccessFailure = true else queue.addLast(child)
                } catch (_: Exception) {
                    hadAccessFailure = true
                }
            }

            if (node !== root && node !== retainedNode) safeRecycle(node, recycle)
        }
        wasTruncated = !stoppedOnMatch && queue.isNotEmpty() && visited >= maxNodes
    } finally {
        while (queue.isNotEmpty()) {
            val queued = queue.removeFirst()
            if (queued !== root && queued !== retainedNode) safeRecycle(queued, recycle)
        }
    }

    return BoundedAccessibilityTraversalResult(
        nodesVisited = visited,
        wasTruncated = wasTruncated,
        hadAccessFailure = hadAccessFailure
    )
}

private fun <N : Any> safeRecycle(node: N, recycle: (N) -> Unit) {
    try {
        recycle(node)
    } catch (_: Throwable) {
        // Recycling is best-effort; cleanup must continue for the rest of the traversal queue.
    }
}
