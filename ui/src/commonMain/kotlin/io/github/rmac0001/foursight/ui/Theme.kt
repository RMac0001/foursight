package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.rmac0001.foursight.core.SocialPlatform

/** The theme setting (Settings section): follow the system, or force light or dark. */
enum class ThemeChoice(
    val label: String,
) {
    SYSTEM("Match Windows"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    companion object {
        const val KEY = "ui.theme"

        fun parse(value: String?): ThemeChoice = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

private val light =
    lightColorScheme(
        primary = Color(0xFF2F5D8A),
        secondary = Color(0xFF5A6B7D),
        tertiary = Color(0xFF8A5A2F),
        background = Color(0xFFF8F9FB),
        surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFEEF1F5),
    )

private val dark =
    darkColorScheme(
        primary = Color(0xFF9CC3EA),
        secondary = Color(0xFFB4C2D0),
        tertiary = Color(0xFFE6B98C),
        background = Color(0xFF14171B),
        surface = Color(0xFF1B1F24),
        surfaceVariant = Color(0xFF262B32),
    )

@Composable
fun FourSightTheme(
    choice: ThemeChoice,
    content: @Composable () -> Unit,
) {
    val isDark =
        when (choice) {
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
        }
    MaterialTheme(colorScheme = if (isDark) dark else light, content = content)
}

/**
 * One color per platform for charts, chosen to stay apart from each other in both themes. Not
 * the platforms' brand colors (FourSight doesn't use their branding).
 */
@Composable
fun platformColor(platform: SocialPlatform): Color {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (platform) {
        SocialPlatform.TIKTOK -> if (isDark) Color(0xFF7FD1C7) else Color(0xFF1F8A7E)
        SocialPlatform.YOUTUBE -> if (isDark) Color(0xFFF2A28C) else Color(0xFFB6492D)
        SocialPlatform.FACEBOOK -> if (isDark) Color(0xFF9CB8F2) else Color(0xFF3A5FB5)
        SocialPlatform.INSTAGRAM -> if (isDark) Color(0xFFD9A6E0) else Color(0xFF8A3F96)
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
