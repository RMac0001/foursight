package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The app's sections, in the order the spec lists them. */
enum class Section(
    val title: String,
) {
    OVERVIEW("Overview"),
    PLATFORMS("Platforms"),
    CONTENT("Content"),
    POSTING("Posting"),
    ACCOUNTS("Accounts"),
    SETTINGS("Settings"),
}

/** Root composable shared by the Windows and Android apps. */
@Composable
fun App() {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var selected by rememberSaveable { mutableStateOf(Section.OVERVIEW) }
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    Section.entries.forEach { section ->
                        NavigationRailItem(
                            selected = section == selected,
                            onClick = { selected = section },
                            icon = { Text(section.title.take(1)) },
                            label = { Text(section.title) },
                        )
                    }
                }
                SectionPlaceholder(selected)
            }
        }
    }
}

@Composable
private fun SectionPlaceholder(section: Section) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(section.title, style = MaterialTheme.typography.headlineMedium)
            Text("Not built yet.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
