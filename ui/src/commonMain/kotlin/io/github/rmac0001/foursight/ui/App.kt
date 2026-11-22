package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The app's sections, in the order the spec lists them. [icon] is the Segoe Fluent Icons glyph
 * (the Microsoft 365 look); [fallback] is used where that font isn't available.
 */
enum class Section(
    val title: String,
    val icon: String,
    val fallback: String,
) {
    OVERVIEW("Overview", "", "◎"),
    PLATFORMS("Platforms", "", "▦"),
    CONTENT("Content", "", "▤"),
    POSTING("Posting", "", "↑"),
    ACCOUNTS("Accounts", "", "⚿"),
    SETTINGS("Settings", "", "⚙"),
}

/**
 * Root composable shared by the Windows and Android apps. [services] is null where the app isn't
 * wired up yet (Android, until step 7).
 */
@Composable
fun App(services: AppServices? = null) {
    var theme by remember { mutableStateOf(ThemeChoice.parse(services?.setting(ThemeChoice.KEY))) }
    FourSightTheme(theme, services?.fonts ?: BrandFonts()) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (services == null) {
                EmptyState("This build of FourSight isn't connected to its data yet.")
                return@Surface
            }
            var selected by rememberSaveable { mutableStateOf(Section.OVERVIEW) }
            // Bumped after anything that changes data, so every screen reloads.
            var reload by remember { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize()) {
                HeaderBar()
                Row(Modifier.fillMaxSize()) {
                    NavPane(selected) { selected = it }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        key(selected) {
                            Column(
                                Modifier
                                    .widthIn(max = 1240.dp)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                                    .padding(horizontal = 32.dp, vertical = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                val changed: () -> Unit = { reload += 1 }
                                when (selected) {
                                    Section.OVERVIEW -> {
                                        OverviewScreen(services, reload)
                                    }

                                    Section.PLATFORMS -> {
                                        PlatformsScreen(services, reload)
                                    }

                                    Section.CONTENT -> {
                                        ContentScreen(services, reload, changed)
                                    }

                                    Section.POSTING -> {
                                        PostingScreen()
                                    }

                                    Section.ACCOUNTS -> {
                                        AccountsScreen(services, reload, changed)
                                    }

                                    Section.SETTINGS -> {
                                        SettingsScreen(services, theme, {
                                            theme = it
                                            services.putSetting(ThemeChoice.KEY, it.name)
                                        }, reload)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The Microsoft 365-style app bar, in the brand's dark brown, with the sunset mark and wordmark. */
@Composable
private fun HeaderBar() {
    val fonts = LocalBrandFonts.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Brand.Brown)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SunsetMark(28.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            "FourSight",
            color = Brand.Cream,
            style =
                if (fonts.wordmark != null) {
                    TextStyle(fontFamily = fonts.wordmark, fontSize = 20.sp)
                } else {
                    MaterialTheme.typography.titleLarge
                },
        )
        Spacer(Modifier.width(12.dp))
        Text(
            "One Fat Man's Journey",
            color = Brand.Cream.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** The left navigation pane: icon and label, the selected item tinted with an orange bar. */
@Composable
private fun NavPane(
    selected: Section,
    onSelect: (Section) -> Unit,
) {
    val fonts = LocalBrandFonts.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(208.dp)
            .fillMaxHeight()
            .background(colors.surface)
            .padding(vertical = 8.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Section.entries.forEach { s ->
            val on = s == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (on) colors.primaryContainer else colors.surface)
                    .clickable { onSelect(s) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .background(if (on) colors.primary else colors.surface, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (fonts.icons != null) s.icon else s.fallback,
                    color = if (on) colors.primary else colors.onSurfaceVariant,
                    style =
                        if (fonts.icons != null) {
                            TextStyle(fontFamily = fonts.icons, fontSize = 16.sp)
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    s.title,
                    color = if (on) colors.onPrimaryContainer else colors.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = colors.outlineVariant)
        Text(
            "Numbers are never estimated; '-' means missing.",
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(12.dp),
        )
    }
}

/** The FourSight mark, drawn like the app icon: an orange sun cut by four stripes on brown. */
@Composable
fun SunsetMark(size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 1024f
        drawRoundRect(Brand.Brown, cornerRadius = CornerRadius(224 * s))
        drawCircle(Brand.Orange, radius = 330 * s, center = Offset(512 * s, 560 * s))
        // Stripes and the horizon cut only across the sun, so the rounded corners stay intact.
        val left = 160 * s
        val width = 704 * s
        listOf(590f to 22f, 660f to 30f, 730f to 38f, 800f to 46f).forEach { (y, h) ->
            drawRect(Brand.Brown, topLeft = Offset(left, (y - h / 2) * s), size = Size(width, h * s))
        }
        drawRect(Brand.Brown, topLeft = Offset(left, 840 * s), size = Size(width, 60 * s))
        drawRoundRect(
            Brand.Cream,
            topLeft = Offset(150 * s, 850 * s),
            size = Size(724 * s, 28 * s),
            cornerRadius =
                CornerRadius(14 * s),
        )
    }
}
