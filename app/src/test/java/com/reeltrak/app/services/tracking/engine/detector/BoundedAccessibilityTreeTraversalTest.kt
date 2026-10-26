package com.reeltrak.app.services.tracking.engine.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedAccessibilityTreeTraversalTest {
    private class Node(
        val resourceId: String? = null,
        val children: MutableList<Node?> = mutableListOf(),
        val unavailable: Boolean = false
    ) {
        var recycleCount = 0
    }

    private fun walk(root: Node, limit: Int, id: String) = boundedAccessibilityBreadthFirst(
        root = root,
        maxNodes = limit,
        childCount = { it.children.size },
        childAt = { node, index -> node.children[index] },
        inspect = { node ->
            if (node.unavailable) throw IllegalStateException("simulated stale node")
            node.resourceId?.contains(id, ignoreCase = true) == true
        },
        retainMatchedNode = { it.resourceId?.contains(id, ignoreCase = true) == true },
        recycle = { it.recycleCount++ }
    )

    @Test
    fun `source-backed reel anchor at discovery boundary is visited`() {
        val anchor = Node("com.instagram.android:id/reels_viewer_root")
        val root = Node(children = (0 until 98).map { Node("filler_$it") }.toMutableList())
        root.children += anchor
        var match: Node? = null
        val result = boundedAccessibilityBreadthFirst(
            root, 100, { it.children.size }, { n, i -> n.children[i] },
            { n -> (n.resourceId?.contains("reels_viewer_root") == true).also { if (it) match = n } },
            { it === match }, { it.recycleCount++ }
        )

        assertEquals(100, result.nodesVisited)
        assertFalse(result.wasTruncated)
        assertSame(anchor, match)
        assertEquals("matched child remains owned by detector extraction", 0, anchor.recycleCount)
    }

    @Test
    fun `metadata token at extraction boundary remains reachable`() {
        val metadata = Node("com.google.android.youtube:id/channel_name")
        val root = Node(children = (0 until 198).map { Node("filler_$it") }.toMutableList())
        root.children += metadata
        val result = walk(root, 200, "channel_name")

        assertEquals(200, result.nodesVisited)
        assertFalse(result.wasTruncated)
        assertEquals(0, metadata.recycleCount)
    }

    @Test
    fun `unavailable properties and null children are recorded while later anchor is found`() {
        val validAnchor = Node("com.instagram.android:id/clips_viewer")
        val root = Node(children = mutableListOf(Node(unavailable = true), null, validAnchor))
        var match: Node? = null
        val result = boundedAccessibilityBreadthFirst(
            root, 10, { it.children.size }, { n, i -> n.children[i] },
            { n ->
                if (n.unavailable) throw IllegalStateException("simulated stale property")
                (n.resourceId?.contains("clips_viewer") == true).also { if (it) match = n }
            },
            { it === match }, { it.recycleCount++ }
        )

        assertTrue(result.hadAccessFailure)
        assertSame(validAnchor, match)
        assertEquals(1, root.children[0]!!.recycleCount)
    }

    @Test
    fun `first matching candidate preserves existing breadth first selection and releases remainder`() {
        val first = Node("reel_recycler")
        val second = Node("shorts_viewer")
        val root = Node(children = mutableListOf(first, second))
        var match: Node? = null
        boundedAccessibilityBreadthFirst(
            root, 10, { it.children.size }, { n, i -> n.children[i] },
            { n -> (n.resourceId in setOf("reel_recycler", "shorts_viewer")).also { if (it) match = n } },
            { it === match }, { it.recycleCount++ }
        )

        assertSame(first, match)
        assertEquals(0, first.recycleCount)
        assertEquals(1, second.recycleCount)
    }

    @Test
    fun `incomplete non feed tree stays bounded and later valid tree recovers`() {
        val incomplete = Node(children = mutableListOf(Node("loading_skeleton")))
        val missingResult = walk(incomplete, 10, "shorts_player")
        assertFalse(missingResult.wasTruncated)
        assertFalse(missingResult.hadAccessFailure)

        val validLater = Node(children = mutableListOf(Node("shorts_player")))
        val recovered = walk(validLater, 10, "shorts_player")
        assertEquals(2, recovered.nodesVisited)
        assertFalse(recovered.wasTruncated)
    }

    @Test
    fun `truncated tree reports pending traversal and recycles unvisited references`() {
        val unvisited = Node("shorts_player")
        val root = Node(children = mutableListOf(Node("loading"), unvisited))
        val result = walk(root, 2, "shorts_player")

        assertTrue(result.wasTruncated)
        assertEquals(1, unvisited.recycleCount)
    }
}
