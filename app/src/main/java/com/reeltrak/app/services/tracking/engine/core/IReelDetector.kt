package com.reeltrak.app.services.tracking.engine.core

import android.view.accessibility.AccessibilityNodeInfo
import com.reeltrak.app.services.tracking.engine.model.DetectionSnapshot

/**
 * Interface for platform-specific detection logic (Instagram, YouTube, etc.)
 */
interface IReelDetector {
    val platformName: String
    
    /**
     * Identifies the current Reel and evaluates the environment.
     */
    fun analyze(rootNode: AccessibilityNodeInfo): DetectionSnapshot
    
    /**
     * Checks if a given package belongs to this detector.
     */
    fun supports(packageName: String): Boolean
}
