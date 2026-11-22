package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Fonts the platform can offer. On Windows: Segoe UI for text, Segoe Fluent Icons for the nav (the
 * Microsoft 365 look), and Cooper Black for the wordmark (the Retro Sunset brand). All come from
 * the PC's own fonts, never bundled in the repo; null falls back to the default.
 */
data class BrandFonts(
    val text: FontFamily? = null,
    val icons: FontFamily? = null,
    val wordmark: FontFamily? = null,
)

val LocalBrandFonts = staticCompositionLocalOf { BrandFonts() }

/** Roger's Retro Sunset brand colors (Social Media Video Toolkit, captions). */
object Brand {
    val Orange = Color(0xFFE0702F)
    val Cream = Color(0xFFF3E3C3)
    val Brown = Color(0xFF2B1B12)
    val OrangeLight = Color(0xFFF08A4B)
    val OrangeTint = Color(0xFFFCEBDD)
}

/** Microsoft 365-style neutrals: quiet grays, white cards, the brand orange as the one accent. */
private val light =
    lightColorScheme(
        primary = Brand.Orange,
        onPrimary = Color.White,
        primaryContainer = Brand.OrangeTint,
        onPrimaryContainer = Brand.Brown,
        secondary = Color(0xFF6B4A36),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFF6EDE3),
        onSecondaryContainer = Brand.Brown,
        tertiary = Color(0xFF2E6F8E),
        background = Color(0xFFF5F5F5),
        onBackground = Color(0xFF242424),
        surface = Color.White,
        onSurface = Color(0xFF242424),
        surfaceVariant = Color(0xFFFAF9F8),
        onSurfaceVariant = Color(0xFF616161),
        surfaceContainer = Color(0xFFFAF9F8),
        surfaceContainerHigh = Color(0xFFF3F2F1),
        outline = Color(0xFFD1D1D1),
        outlineVariant = Color(0xFFE1DFDD),
    )

private val dark =
    darkColorScheme(
        primary = Brand.OrangeLight,
        onPrimary = Brand.Brown,
        primaryContainer = Color(0xFF4A2A18),
        onPrimaryContainer = Brand.Cream,
        secondary = Color(0xFFD9B79A),
        onSecondary = Brand.Brown,
        secondaryContainer = Color(0xFF3A2A20),
        onSecondaryContainer = Brand.Cream,
        tertiary = Color(0xFF7FB8D6),
        background = Color(0xFF1A1411),
        onBackground = Color(0xFFEDE6DD),
        surface = Color(0xFF241C17),
        onSurface = Color(0xFFEDE6DD),
        surfaceVariant = Color(0xFF2C231D),
        onSurfaceVariant = Color(0xFFB9ADA1),
        surfaceContainer = Color(0xFF2C231D),
        surfaceContainerHigh = Color(0xFF342A23),
        outline = Color(0xFF5A4C42),
        outlineVariant = Color(0xFF3F342C),
    )

private val shapes =
    Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(8.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(12.dp),
    )

/** Fluent's type ramp: 14 px body, semibold titles, nothing oversized. */
private fun typography(font: FontFamily?): Typography {
    val f = font ?: FontFamily.Default

    fun style(
        size: Int,
        weight: FontWeight = FontWeight.Normal,
        line: Int = size + 6,
    ) = TextStyle(fontFamily = f, fontSize = size.sp, fontWeight = weight, lineHeight = line.sp)
    return Typography(
        headlineLarge = style(32, FontWeight.SemiBold, 40),
        headlineMedium = style(28, FontWeight.SemiBold, 36),
        headlineSmall = style(24, FontWeight.SemiBold, 32),
        titleLarge = style(20, FontWeight.SemiBold, 28),
        titleMedium = style(16, FontWeight.SemiBold, 22),
        titleSmall = style(14, FontWeight.SemiBold, 20),
        bodyLarge = style(14, line = 20),
        bodyMedium = style(14, line = 20),
        bodySmall = style(12, line = 16),
        labelLarge = style(14, FontWeight.SemiBold, 20),
        labelMedium = style(12, FontWeight.SemiBold, 16),
        labelSmall = style(11, line = 14),
    )
}

@Composable
fun FourSightTheme(
    choice: ThemeChoice,
    fonts: BrandFonts,
    content: @Composable () -> Unit,
) {
    val isDark =
        when (choice) {
            ThemeChoice.SYSTEM -> isSystemInDarkTheme()
            ThemeChoice.LIGHT -> false
            ThemeChoice.DARK -> true
        }
    CompositionLocalProvider(LocalBrandFonts provides fonts) {
        MaterialTheme(
            colorScheme = if (isDark) dark else light,
            shapes = shapes,
            typography = typography(fonts.text),
            content = content,
        )
    }
}

val ColorScheme.isDark: Boolean get() = background.red < 0.5f

/**
 * One color per platform for charts and tags, from the brand's warm palette plus a teal for
 * contrast, so the four stay apart in both themes. Not the platforms' own brand colors.
 */
@Composable
fun platformColor(platform: SocialPlatform): Color {
    val dark = MaterialTheme.colorScheme.isDark
    return when (platform) {
        SocialPlatform.TIKTOK -> if (dark) Brand.OrangeLight else Brand.Orange
        SocialPlatform.YOUTUBE -> if (dark) Color(0xFFE58C7A) else Color(0xFFA23B2A)
        SocialPlatform.FACEBOOK -> if (dark) Color(0xFF7FB8D6) else Color(0xFF2E6F8E)
        SocialPlatform.INSTAGRAM -> if (dark) Color(0xFFD9B95B) else Color(0xFF8E6E12)
    }
}
