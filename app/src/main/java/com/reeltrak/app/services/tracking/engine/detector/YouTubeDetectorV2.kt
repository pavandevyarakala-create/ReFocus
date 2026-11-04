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
        private val USERNAME_CLEAN_REGEX = Regex("^[\\s\\d.,:/KkMmBb+-]+$")
        private val GENERIC_SHORTS_ACTIONS = setOf(
            "subscribe", "subscribed", "join", "share", "remix", "comment", "comments",
            "like", "likes", "dislike", "dislikes", "reply", "replies", "search", "shorts",
            "sound", "music", "audio", "create", "more", "save", "clip", "report",
            "download", "thanks", "live", "analytics", "pause", "play"
        )
    }

    override fun supports(packageName: String): Boolean {
        return packageName == "com.google.android.youtube"
    }

    private data class DiscoveryResult(
        val container: AccessibilityNodeInfo? = null,
        val overlayKind: InternalOverlayKind = InternalOverlayKind.NONE,
        val nodesVisited: Int = 0,
        val wasTruncated: Boolean = false,
        val hasConfirmedNonShortsSurface: Boolean = false
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
        val firstDiscovery = findReelContainer(rootNode, viewportCenterX, viewportCenterY, MAX_SHALLOW_NODES, screenBounds)
        val discovery = if (firstDiscovery.container == null &&
            firstDiscovery.overlayKind == InternalOverlayKind.NONE &&
            firstDiscovery.wasTruncated &&
            !firstDiscovery.hasConfirmedNonShortsSurface) {
            findReelContainer(rootNode, viewportCenterX, viewportCenterY, MAX_RECOVERY_DISCOVERY_NODES, screenBounds)
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
            val isConfirmedExit = discovery.hasConfirmedNonShortsSurface
            val classification = if (isConfirmedExit) {
                SnapshotClassification.DISCARDED
            } else {
                // A missing anchor can mean either a confirmed non-Shorts screen or a transiently
                // incomplete accessibility hierarchy. Let the service perform one bounded re-read.
                SnapshotClassification.INCONCLUSIVE
            }
            return DetectionSnapshot(
                classification = classification,
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
                        val isHandleChannel = !hasReliableCreator && text.startsWith("@") &&
                            !text.contains(" ") && !resId.contains("caption", ignoreCase = true) &&
                            !resId.contains("music", ignoreCase = true) && !resId.contains("sound", ignoreCase = true)
                        val isCandidateChannel = !hasReliableCreator && creator.isBlank() &&
                            isValidChannelCandidate(text, resId, node)

                        if (isExplicitChannel) {
                            creator = text
                            hasReliableCreator = true
                        } else if (isHandleChannel && (!hasReliableCreator || creator.isBlank())) {
                            creator = text
                            hasReliableCreator = true
                        } else if (isCandidateChannel && creator.isBlank()) {
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

    private fun isConfirmedNonShortsIdentifier(resId: String): Boolean {
        if (resId.isEmpty()) return false
        return resId.contains("browse_recycler", ignoreCase = true) ||
            resId.contains("results_list", ignoreCase = true) ||
            resId.contains("watch_player", ignoreCase = true) ||
            resId.contains("library", ignoreCase = true)
    }

    private fun isValidChannelCandidate(text: String, resId: String, node: AccessibilityNodeInfo): Boolean {
        val trimmed = text.trim()
        if (trimmed.length !in 2..40 || trimmed.contains("\n")) return false
        if (trimmed.matches(USERNAME_CLEAN_REGEX)) return false
        val lower = trimmed.lowercase()
        if (GENERIC_SHORTS_ACTIONS.contains(lower)) return false
        if (lower.startsWith("http://") || lower.startsWith("https://")) return false
        if (resId.contains("caption", ignoreCase = true) ||
            resId.contains("music", ignoreCase = true) ||
            resId.contains("sound", ignoreCase = true) ||
            resId.contains("button", ignoreCase = true) ||
            resId.contains("badge", ignoreCase = true)) {
            return false
        }
        val isSelfClickable = node.isClickable
        val parentNode = try { node.parent } catch (_: Throwable) { null }
        val isParentClickable = parentNode?.isClickable ?: false
        try { parentNode?.recycle() } catch (_: Throwable) {}
        return isSelfClickable || isParentClickable
    }

    private fun findReelContainer(
        root: AccessibilityNodeInfo,
        centerX: Int,
        centerY: Int,
        maxShallowNodes: Int,
        screenBounds: android.graphics.Rect
    ): DiscoveryResult {
        val nodeBounds = android.graphics.Rect()
        var container: AccessibilityNodeInfo? = null
        var overlayKind = InternalOverlayKind.NONE
        var hasConfirmedNonShortsSurface = false
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
                // Require a visible content container covering the active viewport center.
                // Generic tabs/bars and hidden retained nodes cannot confirm departure.
                if (node.isVisibleToUser && nodeBounds.contains(centerX, centerY) &&
                    isConfirmedNonShortsIdentifier(resId)) {
                    hasConfirmedNonShortsSurface = true
                }

                // 2. Detect Reel Container with Viewport-Aware Selection (rejecting prebuffered/offscreen children)
                if (resId.contains("reel_recycler") || resId.contains("shorts_viewer")) {
                    val childBounds = android.graphics.Rect()
                    var bestChild: AccessibilityNodeInfo? = null
                    var bestScore = -1
                    var bestOverlap = -1

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        var keepChild = false
                        try {
                            child.getBoundsInScreen(childBounds)
                            val containsCenter = childBounds.contains(centerX, centerY)
                            val spansVerticalCenter = childBounds.top <= centerY && childBounds.bottom >= centerY &&
                                childBounds.width() > 0 && childBounds.height() > 0
                            val overlapTop = maxOf(childBounds.top, screenBounds.top)
                            val overlapBottom = minOf(childBounds.bottom, screenBounds.bottom)
                            val verticalOverlap = maxOf(0, overlapBottom - overlapTop)

                            val score = when {
                                containsCenter -> 3
                                spansVerticalCenter -> 2
                                verticalOverlap > screenBounds.height() / 4 -> 1
                                else -> 0
                            }

                            if (score > bestScore || (score == bestScore && verticalOverlap > bestOverlap)) {
                                bestChild?.let { try { it.recycle() } catch (_: Throwable) {} }
                                bestChild = child
                                bestScore = score
                                bestOverlap = verticalOverlap
                                keepChild = true
                            }
                        } catch (_: Exception) {
                            // A stale child does not invalidate other candidates.
                        }
                        if (!keepChild) try { child.recycle() } catch (_: Throwable) {}
                    }
                    val centeredChild = bestChild
                    if (centeredChild != null && bestScore > 0) {
                        container = centeredChild
                        true
                    } else if (node.childCount == 0 && nodeBounds.contains(centerX, centerY)) {
                        // Only fall back to parent recycler if it has no children at all
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
            wasTruncated = traversal.wasTruncated,
            hasConfirmedNonShortsSurface = hasConfirmedNonShortsSurface
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
