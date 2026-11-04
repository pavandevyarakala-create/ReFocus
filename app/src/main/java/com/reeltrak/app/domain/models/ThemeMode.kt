package com.reeltrak.app.domain.models

/**
 * Re:Focus Appearance Modes.
 *
 * Supported user configurations:
 * - SYSTEM: Automatically matches the host device's OS display setting.
 * - LIGHT: Warm ivory/off-white foundation, charcoal type, restrained muted-lime.
 * - DARK: Deep charcoal/near-black foundation, warm-white type, luminous muted-lime.
 */
enum class ThemeMode(val label: String, val description: String) {
    LIGHT("Light", "Warm ivory foundation"),
    DARK("Dark", "Deep charcoal foundation")
}
