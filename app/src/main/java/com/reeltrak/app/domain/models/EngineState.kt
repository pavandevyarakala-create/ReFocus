package com.reeltrak.app.domain.models

/**
 * Explicit finite states for the Reel Counter Engine.
 */
enum class EngineState {
    /** Waiting for user interaction. */
    IDLE,
    
    /** User is physically swiping. DOM is highly volatile. */
    SCROLLING,
    
    /** Velocity has dropped. Waiting for the DOM to stabilize. */
    SETTLING,
    
    /** The DOM has stabilized, but the signature matches the previous reel. */
    STABLE,
    
    /** An overlay (comments, share sheet) is covering the screen. */
    SUSPENDED
}
