package com.reeltrak.app.services.tracking.engine.detector

import android.view.accessibility.AccessibilityNodeInfo
import com.reeltrak.app.services.tracking.engine.core.IReelDetector
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification
import android.util.Log

internal fun classifyInstagramOverlay(
    resourceId: String,
    className: String,
    hasCommentThreadDescendant: Boolean = false
): InternalOverlayKind = when {
    resourceId.contains("comment_thread", ignoreCase = true) -> InternalOverlayKind.REEL_COMMENTS
    resourceId.contains("bottom_sheet_container", ignoreCase = true) ->
        if (hasCommentThreadDescendant) InternalOverlayKind.REEL_COMMENTS else InternalOverlayKind.GENERIC
    className.contains("Dialog", ignoreCase = true) || className.contains("PopupWindow", ignoreCase = true) ->
        InternalOverlayKind.GENERIC
    else -> InternalOverlayKind.NONE
}

class InstagramDetectorV2 : IReelDetector {
    override val platformName = "Instagram"

    companion object {
        private val USERNAME_CLEAN_REGEX = Regex("^[\\s\\d.,:/KkMmBb-]+$")
        private val GENERIC_UI_WORDS = setOf(
            "share", "remix", "audio", "save", "saved", "like", "liked",
            "comment", "comments", "sponsored", "suggested", "shop", "install",
            "subscribe", "subscribed", "join", "more", "report"
        )
    }

    override fun supports(packageName: String): Boolean {
        return packageName == "com.instagram.android"
    }

    private data class DiscoveryResult(
        val container: AccessibilityNodeInfo? = null,
        val overlayKind: InternalOverlayKind = InternalOverlayKind.NONE,
        val nodesVisited: Int = 0,
        val wasTruncated: Boolean = false
    )

    override fun analyze(rootNode: AccessibilityNodeInfo): DetectionSnapshot {
        // --- PASS 1: Targeted Anchor Discovery with Spatial Filtering ---
        val screenBounds = android.graphics.Rect()
        try {
            rootNode.getBoundsInScreen(screenBounds)
        } catch (_: Exception) {
            return inconclusiveSnapshot(nodeCount = 0)
        }
        val viewportCenterY = screenBounds.centerY()
        val viewportCenterX = screenBounds.centerX()

        val MAX_SHALLOW_NODES = 100
        val MAX_RECOVERY_DISCOVERY_NODES = 200
        val firstDiscovery = findReelContainer(rootNode, viewportCenterX, viewportCenterY, MAX_SHALLOW_NODES)
        val discovery = if (firstDiscovery.container == null &&
            firstDiscovery.overlayKind == InternalOverlayKind.NONE &&
            firstDiscovery.wasTruncated) {
            findReelContainer(rootNode, viewportCenterX, viewportCenterY, MAX_RECOVERY_DISCOVERY_NODES)
        } else {
            firstDiscovery
        }
        
        if (discovery.overlayKind != InternalOverlayKind.NONE) {
            return DetectionSnapshot(
                classification = SnapshotClassification.DISCARDED,
                fingerprint = SemanticFingerprint("", "", ""),
                platform = platformName,
                nodeCount = discovery.nodesVisited,
                isInternalOverlay = true,
                internalOverlayKind = discovery.overlayKind
            )
        }

        val containerNode = discovery.container
        if (containerNode == null) {
            return DetectionSnapshot(
                // A missing anchor can mean either a confirmed non-Reel screen or a transiently
                // incomplete accessibility hierarchy. Let the service perform one bounded re-read.
                classification = SnapshotClassification.INCONCLUSIVE,
                fingerprint = SemanticFingerprint("", "", ""),
                platform = platformName,
                nodeCount = discovery.nodesVisited,
                isInternalOverlay = false
            )
        }

        // --- PASS 2: Subtree BFS Extraction ---
        var creator = ""
        var hasReliableCreator = false
        var caption = ""
        var song = ""
        val MAX_EXTRACTION_NODES = 200
        val extraction = try {
            boundedAccessibilityBreadthFirst(
                root = containerNode,
                maxNodes = MAX_EXTRACTION_NODES,
                childCount = { it.childCount },
                childAt = { node, index -> node.getChild(index) },
                inspect = { node ->
                    val resId = node.viewIdResourceName ?: ""
                    val text = node.text?.toString() ?: ""
                    if (node.isVisibleToUser && text.isNotBlank()) {
                        val isExplicitUsername = resId.contains("user_name", ignoreCase = true) ||
                            resId.contains("username", ignoreCase = true)
                        val parentNode = try { node.parent } catch (_: Exception) { null }
                        val isParentClickable = try {
                            parentNode?.isClickable == true
                        } finally {
                            try { parentNode?.recycle() } catch (_: Throwable) {}
                        }
                        val isFallbackUsername = !hasReliableCreator && text.length in 3..30 &&
                            !text.contains(" ") && !text.contains("\n") &&
                            (node.isClickable || isParentClickable) &&
                            !text.matches(USERNAME_CLEAN_REGEX) &&
                            !text.equals("Follow", ignoreCase = true) &&
                            !text.equals("Following", ignoreCase = true) &&
                            !GENERIC_UI_WORDS.contains(text.trim().lowercase()) &&
                            !resId.contains("caption", ignoreCase = true) &&
                            !resId.contains("music", ignoreCase = true) &&
                            !resId.contains("sound", ignoreCase = true)
                        if (isExplicitUsername) {
                            creator = text
                            hasReliableCreator = true
                        } else if (isFallbackUsername && creator.isBlank()) {
                            creator = text
                        }
                        if (resId.contains("caption", ignoreCase = true) &&
                            (caption.isBlank() || text.length > caption.length)) caption = text
                        if (resId.contains("music_title", ignoreCase = true)) song = text
                    }
                    creator.isNotBlank() && caption.isNotBlank() && song.isNotBlank()
                },
                recycle = { it.recycle() }
            )
        } finally {
            if (containerNode !== rootNode) try { containerNode.recycle() } catch (_: Throwable) {}
        }
        val nodesVisited = extraction.nodesVisited

        val normalizedCaption = normalizeCaption(caption)
        val classification = if (creator.isNotBlank()) SnapshotClassification.COMMITTED else SnapshotClassification.PENDING

        val snapshot = DetectionSnapshot(
            classification = classification,
            fingerprint = SemanticFingerprint(creator, normalizedCaption, song, platform = platformName),
            platform = platformName,
            nodeCount = nodesVisited,
            isInternalOverlay = false
        )

        Log.d("InstagramDebug", "Analyze: creator='$creator', caption='$caption' (norm='$normalizedCaption'), song='$song', classification=$classification, nodes=$nodesVisited")
        return snapshot
    }

    private fun findReelContainer(
        root: AccessibilityNodeInfo,
        centerX: Int,
        centerY: Int,
        maxShallowNodes: Int
    ): DiscoveryResult {
        val nodeBounds = android.graphics.Rect()
        var container: AccessibilityNodeInfo? = null
        var overlayKind = InternalOverlayKind.NONE
        val traversal = boundedAccessibilityBreadthFirst(
            root = root,
            maxNodes = maxShallowNodes,
            childCount = { it.childCount },
            childAt = { node, index -> node.getChild(index) },
            inspect = { node ->
                val resId = node.viewIdResourceName ?: ""
                val className = node.className?.toString() ?: ""

                // 1. Detect Overlay (Immediate pause signal)
                val isBottomSheet = resId.contains("bottom_sheet_container", ignoreCase = true)
                val isOverlayCandidate = if (isBottomSheet) {
                    node.isVisibleToUser && node.childCount > 0
                } else {
                    node.isVisibleToUser
                }
                if (isOverlayCandidate) {
                    val hasCommentThreadDescendant = isBottomSheet && containsCommentThread(node)
                    overlayKind = classifyInstagramOverlay(resId, className, hasCommentThreadDescendant)
                    if (overlayKind != InternalOverlayKind.NONE) return@boundedAccessibilityBreadthFirst true
                }

                node.getBoundsInScreen(nodeBounds)
                val isCentered = nodeBounds.contains(centerX, centerY)
                if (isCentered && (resId.contains("reels_viewer_root") || resId.contains("clips_viewer"))) {
                    container = node
                    true
                } else false
            },
            retainMatchedNode = { it === container },
            recycle = { it.recycle() }
        )
        return DiscoveryResult(
            container = container,
            overlayKind = overlayKind,
            nodesVisited = traversal.nodesVisited,
            wasTruncated = traversal.wasTruncated
        )
    }

    private fun containsCommentThread(subtreeRoot: AccessibilityNodeInfo): Boolean {
        var found = false
        boundedAccessibilityBreadthFirst(
            root = subtreeRoot,
            maxNodes = 100,
            childCount = { it.childCount },
            childAt = { node, index -> node.getChild(index) },
            inspect = { node ->
                found = node.viewIdResourceName?.contains("comment_thread", ignoreCase = true) == true
                found
            },
            recycle = { it.recycle() }
        )
        return found
    }

    private fun inconclusiveSnapshot(nodeCount: Int) = DetectionSnapshot(
        classification = SnapshotClassification.INCONCLUSIVE,
        fingerprint = SemanticFingerprint("", "", ""),
        platform = platformName,
        nodeCount = nodeCount,
        isInternalOverlay = false
    )

    internal fun normalizeCaption(caption: String): String =
        CaptionNormalizer.normalize(caption)
}
