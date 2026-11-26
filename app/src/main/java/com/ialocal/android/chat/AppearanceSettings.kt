package com.ialocal.android.chat

enum class AppearanceMode(val label: String) {
    SYSTEM("Sistema"),
    LIGHT("Claro"),
    DARK("Oscuro")
}

data class AppearanceSettings(
    val mode: AppearanceMode = AppearanceMode.SYSTEM,
    val reduceMotion: Boolean = false
)
