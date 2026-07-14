package com.reeltrak.app.services.tracking.engine.detector

import android.view.accessibility.AccessibilityNodeInfo
import com.reeltrak.app.services.tracking.engine.core.IReelDetector
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification

class YouTubeDetectorV2 : IReelDetector {
    override val platformName = "YouTube"

    companion object {
        private val USERNAME_CLEAN_REGEX = Regex("^[\\s\\d.,:/KkMmBb-]+$")
    }

    override fun supports(packageName: String): Boolean {
        return packageName == "com.google.android.youtube"
    }

    private data class DiscoveryResult(
        val container: AccessibilityNodeInfo? = null,
        val isOverlayVisible: Boolean = false
    )

    override fun analyze(rootNode: AccessibilityNodeInfo): DetectionSnapshot {
        // --- PASS 1: Targeted Anchor Discovery ---
        val discovery = findReelContainer(rootNode)
        
        if (discovery.isOverlayVisible) {
            return DetectionSnapshot(
                classification = SnapshotClassification.DISCARDED,
                fingerprint = SemanticFingerprint("", "", ""),
                platform = platformName,
                nodeCount = 0,
                isInternalOverlay = true
            )
        }

        val containerNode = discovery.container
        if (containerNode == null) {
            return DetectionSnapshot(
                classification = SnapshotClassification.DISCARDED,
                fingerprint = SemanticFingerprint("", "", ""),
                platform = platformName,
                nodeCount = 0,
                isInternalOverlay = false
            )
        }

        // --- PASS 2: Metadata Extraction (Original Logic) ---
        var creator = ""
        var caption = ""
        var song = ""
        var nodesVisited = 0

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(containerNode)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            nodesVisited++

            val resId = node.viewIdResourceName ?: ""
            val text = node.text?.toString() ?: ""

            // Identity Extraction
            if (node.isVisibleToUser && text.isNotBlank()) {
                val isChannelText = resId.contains("channel_name", ignoreCase = true) ||
                                    resId.contains("channel_title", ignoreCase = true) ||
                                    resId.contains("creator", ignoreCase = true) ||
                                    (text.startsWith("@") && !text.contains(" ") && 
                                     !resId.contains("caption", ignoreCase = true) && 
                                     !resId.contains("music", ignoreCase = true) && 
                                     !resId.contains("sound", ignoreCase = true))
                if (isChannelText) {
                    creator = text
                }
                if (resId.contains("video_caption", ignoreCase = true)) {
                    caption = text
                }
                if (resId.contains("sound_metadata_title", ignoreCase = true)) {
                    song = text
                }
            }

            try {
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } finally {
                if (node !== rootNode && node !== containerNode) {
                    node.recycle()
                }
            }
        }

        cleanupQueue(queue, rootNode, containerNode)
        if (containerNode !== rootNode) containerNode.recycle()

        val classification = if (creator.isNotBlank()) SnapshotClassification.COMMITTED else SnapshotClassification.PENDING
        val normalizedCaption = normalizeCaption(caption)

        return DetectionSnapshot(
            classification = classification,
            fingerprint = SemanticFingerprint(creator, normalizedCaption, song),
            platform = platformName,
            nodeCount = nodesVisited,
            isInternalOverlay = false
        )
    }

    private fun findReelContainer(root: AccessibilityNodeInfo): DiscoveryResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val MAX_SHALLOW_NODES = 100 

        while (queue.isNotEmpty() && visited < MAX_SHALLOW_NODES) {
            val node = queue.removeFirst()
            visited++
            
            val resId = node.viewIdResourceName ?: ""

            // 1. Detect Overlay
            if (resId.contains("engagement_panel") || 
                resId.contains("bottom_sheet") || 
                resId.contains("dialog_container")) {
                cleanupQueue(queue, root, null)
                if (node !== root) node.recycle()
                return DiscoveryResult(isOverlayVisible = true)
            }

            // 2. Detect Reel Container
            if (resId.contains("shorts_player") || 
                resId.contains("reel_recycler") || 
                resId.contains("shorts_viewer")) {
                cleanupQueue(queue, root, null)
                return DiscoveryResult(container = node) 
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            
            if (node !== root) {
                node.recycle()
            }
        }
        cleanupQueue(queue, root, null)
        return DiscoveryResult(container = null, isOverlayVisible = false)
    }

    private fun cleanupQueue(queue: ArrayDeque<AccessibilityNodeInfo>, root: AccessibilityNodeInfo, protected: AccessibilityNodeInfo?) {
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n !== root && n !== protected) n.recycle()
        }
    }

    private fun normalizeCaption(caption: String): String {
        var cleaned = caption
        val delimiters = listOf("...", "…", "#", "more")
        for (delim in delimiters) {
            val idx = cleaned.indexOf(delim, ignoreCase = true)
            if (idx != -1) {
                cleaned = cleaned.substring(0, idx)
            }
        }
        val alphaNumeric = cleaned.filter { it.isLetterOrDigit() }.lowercase()
        return alphaNumeric.take(12)
    }
}
