package com.reeltrak.app.services.tracking.engine.detector

import android.view.accessibility.AccessibilityNodeInfo
import com.reeltrak.app.services.tracking.engine.core.IReelDetector
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import com.reeltrak.app.services.tracking.engine.model.SemanticFingerprint
import com.reeltrak.app.services.tracking.engine.model.SnapshotClassification

internal fun classifyYouTubeOverlay(
    resourceId: String,
    hasEngagementPanelDescendant: Boolean = false
): InternalOverlayKind = when {
    resourceId.contains("engagement_panel", ignoreCase = true) -> InternalOverlayKind.REEL_ENGAGEMENT
    resourceId.contains("bottom_sheet", ignoreCase = true) ->
        if (hasEngagementPanelDescendant) InternalOverlayKind.REEL_ENGAGEMENT else InternalOverlayKind.GENERIC
    resourceId.contains("dialog_container", ignoreCase = true) -> InternalOverlayKind.GENERIC
    else -> InternalOverlayKind.NONE
}

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
        val overlayKind: InternalOverlayKind = InternalOverlayKind.NONE,
        val nodesVisited: Int = 0,
        val wasTruncated: Boolean = false
    )

    override fun analyze(rootNode: AccessibilityNodeInfo): DetectionSnapshot {
        // --- PASS 1: Targeted Anchor Discovery with Viewport Spatial Filtering ---
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
                fingerprint = SemanticFingerprint("", "", "", platform = platformName),
                platform = platformName,
                nodeCount = discovery.nodesVisited,
                isInternalOverlay = true,
                internalOverlayKind = discovery.overlayKind
            )
        }

        val containerNode = discovery.container
        if (containerNode == null) {
            return DetectionSnapshot(
                // A missing anchor can mean either a confirmed non-Shorts screen or a transiently
                // incomplete accessibility hierarchy. Let the service perform one bounded re-read.
                classification = SnapshotClassification.INCONCLUSIVE,
                fingerprint = SemanticFingerprint("", "", "", platform = platformName),
                platform = platformName,
                nodeCount = discovery.nodesVisited,
                isInternalOverlay = false
            )
        }

        // --- PASS 2: Metadata Extraction (Targeted to Active Visible Short) ---
        var creator = ""
        var hasReliableCreator = false
        var caption = ""
        var song = ""
        val MAX_EXTRACTION_NODES = 200
        val nodeBounds = android.graphics.Rect()
        val extraction = try {
            boundedAccessibilityBreadthFirst(
                root = containerNode,
                maxNodes = MAX_EXTRACTION_NODES,
                childCount = { it.childCount },
                childAt = { node, index -> node.getChild(index) },
                inspect = { node ->
                    val resId = node.viewIdResourceName ?: ""
                    val text = node.text?.toString() ?: ""
                    node.getBoundsInScreen(nodeBounds)
                    val isInViewport = nodeBounds.isEmpty ||
                        (nodeBounds.bottom > screenBounds.top && nodeBounds.top < screenBounds.bottom)
                    if (node.isVisibleToUser && isInViewport && text.isNotBlank()) {
                        val isExplicitChannel = resId.contains("channel_name", ignoreCase = true) ||
                            resId.contains("channel_title", ignoreCase = true) ||
                            resId.contains("creator", ignoreCase = true)
                        val isFallbackChannel = !hasReliableCreator && text.startsWith("@") &&
                            !text.contains(" ") && !resId.contains("caption", ignoreCase = true) &&
                            !resId.contains("music", ignoreCase = true) && !resId.contains("sound", ignoreCase = true)
                        if (isExplicitChannel) {
                            creator = text
                            hasReliableCreator = true
                        } else if (isFallbackChannel && creator.isBlank()) {
                            creator = text
                        }
                        if (resId.contains("video_caption", ignoreCase = true) &&
                            (caption.isBlank() || text.length > caption.length)) caption = text
                        if (resId.contains("sound_metadata_title", ignoreCase = true)) song = text
                    }
                    creator.isNotBlank() && caption.isNotBlank() && song.isNotBlank()
                },
                recycle = { it.recycle() }
            )
        } finally {
            if (containerNode !== rootNode) try { containerNode.recycle() } catch (_: Throwable) {}
        }
        val nodesVisited = extraction.nodesVisited

        val classification = if (creator.isNotBlank()) SnapshotClassification.COMMITTED else SnapshotClassification.PENDING
        val normalizedCaption = normalizeCaption(caption)

        return DetectionSnapshot(
            classification = classification,
            fingerprint = SemanticFingerprint(creator, normalizedCaption, song, platform = platformName),
            platform = platformName,
            nodeCount = nodesVisited,
            isInternalOverlay = false
        )
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

                // 1. Detect Overlay
                val hasEngagementPanelDescendant = resId.contains("bottom_sheet", ignoreCase = true) &&
                    containsEngagementPanel(node)
                overlayKind = classifyYouTubeOverlay(resId, hasEngagementPanelDescendant)
                if (overlayKind != InternalOverlayKind.NONE) return@boundedAccessibilityBreadthFirst true

                node.getBoundsInScreen(nodeBounds)

                // 2. Detect Reel Container with Viewport-Aware Selection (rejecting prebuffered/offscreen children)
                if (resId.contains("reel_recycler") || resId.contains("shorts_viewer")) {
                    val childBounds = android.graphics.Rect()
                    var centeredChild: AccessibilityNodeInfo? = null
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        var keepChild = false
                        try {
                            child.getBoundsInScreen(childBounds)
                            if (childBounds.contains(centerX, centerY)) {
                                centeredChild = child
                                keepChild = true
                                break
                            }
                        } catch (_: Exception) {
                            // A stale child does not invalidate other candidates.
                        }
                        if (!keepChild) try { child.recycle() } catch (_: Throwable) {}
                    }
                    if (centeredChild != null) {
                        container = centeredChild
                        true
                    } else if (nodeBounds.contains(centerX, centerY)) {
                        container = node
                        true
                    } else false
                } else if (resId.contains("shorts_player") && nodeBounds.contains(centerX, centerY)) {
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

    private fun containsEngagementPanel(subtreeRoot: AccessibilityNodeInfo): Boolean {
        var found = false
        boundedAccessibilityBreadthFirst(
            root = subtreeRoot,
            maxNodes = 100,
            childCount = { it.childCount },
            childAt = { node, index -> node.getChild(index) },
            inspect = { node ->
                found = node.viewIdResourceName?.contains("engagement_panel", ignoreCase = true) == true
                found
            },
            recycle = { it.recycle() }
        )
        return found
    }

    internal fun normalizeCaption(caption: String): String =
        CaptionNormalizer.normalize(caption)

    private fun inconclusiveSnapshot(nodeCount: Int) = DetectionSnapshot(
        classification = SnapshotClassification.INCONCLUSIVE,
        fingerprint = SemanticFingerprint("", "", "", platform = platformName),
        platform = platformName,
        nodeCount = nodeCount,
        isInternalOverlay = false
    )
}
