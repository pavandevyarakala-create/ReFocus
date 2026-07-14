package com.reeltrak.app.services.tracking.engine.detector

import android.view.accessibility.AccessibilityNodeInfo
import com.reeltrak.app.services.tracking.engine.core.IReelDetector
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import android.util.Log

class InstagramDetectorV2 : IReelDetector {
    override val platformName = "Instagram"

    companion object {
        private val USERNAME_CLEAN_REGEX = Regex("^[\\s\\d.,:/KkMmBb-]+$")
    }

    override fun supports(packageName: String): Boolean {
        return packageName == "com.instagram.android"
    }

    private data class DiscoveryResult(
        val container: AccessibilityNodeInfo? = null,
        val isOverlayVisible: Boolean = false
    )

    override fun analyze(rootNode: AccessibilityNodeInfo): DetectionSnapshot {
        // --- PASS 1: Targeted Anchor Discovery with Spatial Filtering ---
        val screenBounds = android.graphics.Rect()
        rootNode.getBoundsInScreen(screenBounds)
        val viewportCenterY = screenBounds.centerY()
        val viewportCenterX = screenBounds.centerX()

        val discovery = findReelContainer(rootNode, viewportCenterX, viewportCenterY)
        
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

        // --- PASS 2: Subtree BFS Extraction ---
        var creator = ""
        var caption = ""
        var song = ""
        var nodesVisited = 0
        val MAX_EXTRACTION_NODES = 200

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(containerNode)

        while (queue.isNotEmpty() && nodesVisited < MAX_EXTRACTION_NODES) {
            val node = queue.removeFirst()
            nodesVisited++

            val resId = node.viewIdResourceName ?: ""
            val text = node.text?.toString() ?: ""

            // Identity Extraction: Targeted to nodes visible to user
            if (node.isVisibleToUser && text.isNotBlank()) {
                val isUsernameText = resId.contains("user_name", ignoreCase = true) ||
                                     resId.contains("username", ignoreCase = true) ||
                                     (text.length in 3..30 && 
                                      !text.contains(" ") && 
                                      !text.contains("\n") &&
                                      (node.isClickable || (node.parent?.isClickable == true)) &&
                                      !text.matches(USERNAME_CLEAN_REGEX) &&
                                      !text.equals("Follow", ignoreCase = true) &&
                                      !text.equals("Following", ignoreCase = true) &&
                                      !resId.contains("caption", ignoreCase = true) &&
                                      !resId.contains("music", ignoreCase = true) &&
                                      !resId.contains("sound", ignoreCase = true))

                if (isUsernameText) {
                    creator = text
                }
                if (resId.contains("caption", ignoreCase = true)) {
                    caption = text
                }
                if (resId.contains("music_title", ignoreCase = true)) {
                    song = text
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
            
            if (node !== rootNode && node !== containerNode) {
                node.recycle()
            }
            
            if (creator.isNotBlank() && caption.isNotBlank() && song.isNotBlank()) break
        }
        
        cleanupQueue(queue, rootNode, containerNode)
        if (containerNode !== rootNode) containerNode.recycle()

        val normalizedCaption = normalizeCaption(caption)
        val classification = if (creator.isNotBlank()) SnapshotClassification.COMMITTED else SnapshotClassification.PENDING

        val snapshot = DetectionSnapshot(
            classification = classification,
            fingerprint = SemanticFingerprint(creator, normalizedCaption, song),
            platform = platformName,
            nodeCount = nodesVisited,
            isInternalOverlay = false
        )

        Log.d("InstagramDebug", "Analyze: creator='$creator', caption='$caption' (norm='$normalizedCaption'), song='$song', classification=$classification, nodes=$nodesVisited")
        return snapshot
    }

    private fun findReelContainer(root: AccessibilityNodeInfo, centerX: Int, centerY: Int): DiscoveryResult {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        val MAX_SHALLOW_NODES = 100

        val nodeBounds = android.graphics.Rect()

        while (queue.isNotEmpty() && visited < MAX_SHALLOW_NODES) {
            val node = queue.removeFirst()
            visited++
            
            val resId = node.viewIdResourceName ?: ""
            val className = node.className?.toString() ?: ""

            // 1. Detect Overlay (Immediate pause signal)
            if (resId.contains("bottom_sheet_container") || resId.contains("comment_thread") ||
                className.contains("Dialog") || className.contains("PopupWindow")) {
                cleanupQueue(queue, root, null)
                if (node !== root) node.recycle()
                return DiscoveryResult(isOverlayVisible = true)
            }

            // 2. Spatial Validation: Does this node overlap the screen center?
            node.getBoundsInScreen(nodeBounds)
            val isCentered = nodeBounds.contains(centerX, centerY)

            // 3. Exact Match (Preferred)
            if (isCentered && (resId.contains("reels_viewer_root") || resId.contains("clips_viewer"))) {
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
