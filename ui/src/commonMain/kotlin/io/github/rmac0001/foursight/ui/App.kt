package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp

/** The app's sections, in the order the spec lists them. */
enum class Section(
    val title: String,
    val glyph: String,
) {
    OVERVIEW("Overview", "◎"),
    PLATFORMS("Platforms", "▦"),
    CONTENT("Content", "▤"),
    POSTING("Posting", "↑"),
    ACCOUNTS("Accounts", "⚿"),
    SETTINGS("Settings", "⚙"),
}

/**
 * Root composable shared by the Windows and Android apps. [services] is null where the app isn't
 * wired up yet (Android, until step 7).
 */
@Composable
fun App(services: AppServices? = null) {
    var theme by remember { mutableStateOf(ThemeChoice.parse(services?.setting(ThemeChoice.KEY))) }
    FourSightTheme(theme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (services == null) {
                EmptyState("This build of FourSight isn't connected to its data yet.")
                return@Surface
            }
            var selected by rememberSaveable { mutableStateOf(Section.OVERVIEW) }
            // Bumped after anything that changes data, so every screen reloads.
            var reload by remember { mutableIntStateOf(0) }
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    Section.entries.forEach { section ->
                        NavigationRailItem(
                            selected = section == selected,
                            onClick = { selected = section },
                            icon = { Text(section.glyph, style = MaterialTheme.typography.titleLarge) },
                            label = { Text(section.title) },
                        )
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    key(selected) {
                        Column(
                            Modifier
                                .widthIn(max = 1200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
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
