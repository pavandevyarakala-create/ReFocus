package com.reeltrak.app.services.tracking.engine.detector

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import com.reeltrak.app.services.tracking.engine.model.InternalOverlayKind
import org.junit.Test
import java.io.File

class DetectorV2RegressionTest {

    private fun findSourceFile(relativePath: String): File {
        val candidates = listOf(
            relativePath,
            "app/$relativePath",
            "../app/$relativePath"
        )
        return candidates.map { File(it) }.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Source file not found: $relativePath")
    }

    @Test
    fun testInstagramDetectorV2_ContainerDiscoveryRules() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt")
        val content = file.readText()

        // 1. Verify MAX_SHALLOW_NODES is 100 as in baseline
        assertTrue(
            "MAX_SHALLOW_NODES must be set to 100 in InstagramDetectorV2",
            content.contains("val MAX_SHALLOW_NODES = 100")
        )

        // 2. Verify spatial validation rule: center with resId requirement
        assertTrue(
            "Spatial check must compute isCentered = nodeBounds.contains(centerX, centerY)",
            content.contains("val isCentered = nodeBounds.contains(centerX, centerY)")
        )
        assertTrue(
            "ResId check must require reels_viewer_root or clips_viewer",
            content.contains("resId.contains(\"reels_viewer_root\") || resId.contains(\"clips_viewer\")")
        )
        assertTrue(
            "Must accept when matching ResId and centered",
            content.contains("if (isCentered && (resId.contains(\"reels_viewer_root\") || resId.contains(\"clips_viewer\")))")
        )

        // 3. Verify a matching container is retained for extraction.
        assertTrue(
            "Must retain the matching node as the discovered container",
            content.contains("container = node") && content.contains("retainMatchedNode = { it === container }")
        )
    }

    @Test
    fun testInstagramDetectorV2_InertBottomSheetContainerDoesNotTriggerOverlay() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt")
        val content = file.readText()

        assertTrue(
            "Overlay check must guard bottom_sheet_container with visibility and childCount",
            content.contains("node.isVisibleToUser && node.childCount > 0")
        )
    }

    @Test
    fun testYouTubeDetectorV2_ContainerDiscoveryRules() {
        val file = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt")
        val content = file.readText()

        // 1. Verify MAX_SHALLOW_NODES is 100 as in baseline
        assertTrue(
            "MAX_SHALLOW_NODES must be set to 100 in YouTubeDetectorV2",
            content.contains("val MAX_SHALLOW_NODES = 100")
        )

        // 2. Verify exact container matching and early exit
        assertTrue(
            "YouTube must check shorts_player, reel_recycler, shorts_viewer",
            content.contains("resId.contains(\"shorts_player\")") &&
            content.contains("resId.contains(\"reel_recycler\")") &&
            content.contains("resId.contains(\"shorts_viewer\")")
        )
        assertTrue(
            "Must retain a matched Shorts node or centered child",
            content.contains("container = node") && content.contains("container = centeredChild")
        )
    }

    @Test
    fun testMissingReelAnchorIsReportedAsInconclusiveOnBothPlatforms() {
        val instagram = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/InstagramDetectorV2.kt")
            .readText()
        val youtube = findSourceFile("src/main/java/com/reeltrak/app/services/tracking/engine/detector/YouTubeDetectorV2.kt")
            .readText()

        assertTrue(
            "Instagram must let the service retry a missing reel anchor once",
            instagram.contains("if (containerNode == null)") &&
                instagram.contains("classification = SnapshotClassification.INCONCLUSIVE") &&
                instagram.contains("MAX_RECOVERY_DISCOVERY_NODES = 200")
        )
        assertTrue(
            "YouTube must let the service retry a missing Shorts anchor once",
            youtube.contains("if (containerNode == null)") &&
                youtube.contains("classification = SnapshotClassification.INCONCLUSIVE") &&
                youtube.contains("MAX_RECOVERY_DISCOVERY_NODES = 200")
        )
    }

    @Test
    fun testInstagramDetectorV2_CommentsSheetsAreSeparatedFromGenericSheets() {
        assertEquals(
            InternalOverlayKind.REEL_COMMENTS,
            classifyInstagramOverlay("comment_thread", "")
        )
        assertEquals(
            InternalOverlayKind.REEL_COMMENTS,
            classifyInstagramOverlay("bottom_sheet_container", "", hasCommentThreadDescendant = true)
        )
        assertEquals(
            InternalOverlayKind.GENERIC,
            classifyInstagramOverlay("bottom_sheet_container", "", hasCommentThreadDescendant = false)
        )
        assertEquals(InternalOverlayKind.GENERIC, classifyInstagramOverlay("", "android.app.Dialog"))
        assertEquals(InternalOverlayKind.NONE, classifyInstagramOverlay("reels_viewer_root", ""))
    }

    @Test
    fun testYouTubeDetectorV2_EngagementSheetsAreSeparatedFromGenericSheets() {
        assertEquals(
            InternalOverlayKind.REEL_ENGAGEMENT,
            classifyYouTubeOverlay("engagement_panel")
        )
        assertEquals(
            InternalOverlayKind.REEL_ENGAGEMENT,
            classifyYouTubeOverlay("bottom_sheet", hasEngagementPanelDescendant = true)
        )
        assertEquals(
            InternalOverlayKind.GENERIC,
            classifyYouTubeOverlay("bottom_sheet", hasEngagementPanelDescendant = false)
        )
        assertEquals(InternalOverlayKind.GENERIC, classifyYouTubeOverlay("dialog_container"))
        assertEquals(InternalOverlayKind.NONE, classifyYouTubeOverlay("shorts_player"))
    }
}
