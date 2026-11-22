package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rmac0001.foursight.core.SocialPlatform
import io.github.rmac0001.foursight.core.collect.PlatformRunResult
import io.github.rmac0001.foursight.core.metrics.Availability
import io.github.rmac0001.foursight.core.metrics.Metrics
import io.github.rmac0001.foursight.core.query.ExportFormat
import io.github.rmac0001.foursight.core.query.PlatformLimits
import io.github.rmac0001.foursight.core.query.QueryJson
import kotlinx.coroutines.launch

/** What each platform's posting will be limited to (spec: What each platform gives, Posting). */
private fun postingLimit(platform: SocialPlatform): String =
    when (platform) {
        SocialPlatform.TIKTOK -> {
            "Unaudited apps post private only; the audit takes days to weeks. Uploading " +
                "to the inbox as a draft may work."
        }

        SocialPlatform.YOUTUBE -> {
            "Uploads from an unverified Google project stay private until Google's audit."
        }

        SocialPlatform.FACEBOOK -> {
            "Reels Publishing API, up to 30 API-published posts per 24 hours."
        }

        SocialPlatform.INSTAGRAM -> {
            "Content Publishing API through the linked professional account."
        }
    }

@Composable
fun PostingScreen() {
    ScreenTitle("Posting", "Post to all four platforms from the app, with your confirmation for every post.")
    Panel(
        "Not built yet",
        note =
            "Posting is step 8 of the build order. Until then, post in each platform's " +
                "own app.",
    ) {
        Muted(
            "When it's built, every post needs your explicit confirmation here. A post Claude asks for " +
                "through the MCP server waits as a pending post until you approve it.",
        )
    }
    SocialPlatform.entries.forEach { p ->
        Panel(p.displayName) {
            KeyValue("Status", "Not built yet (step 8)")
            Muted("Expected limit: ${postingLimit(p)}")
        }
    }
    Panel("Pending posts") { Muted("None.") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountsScreen(
    services: AppServices,
    reload: Int,
    onChanged: () -> Unit,
) {
    val zone = services.timeZone
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<String>>(emptyList()) }

    fun report(results: List<PlatformRunResult>) =
        results.flatMap { r ->
            val head =
                if (r.ok) {
                    "${r.platform.displayName}: ${r.posts} posts, ${r.newSnapshots} new snapshots"
                } else {
                    "${r.error} ${r.failure?.advice?.replace("<platform>", r.platform.name.lowercase()) ?: ""}"
                }
            listOf(head) + r.warnings.map { "  warning: $it" }
        }

    ScreenTitle("Accounts", "Sign-ins, last refresh, errors and limits for each connection.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        PrimaryButton(
            enabled = busy == null,
            onClick = {
                busy = "Refreshing every platform..."
                scope.launch {
                    messages = report(services.refresh(null))
                    busy = null
                    onChanged()
                }
            },
        ) { Text("Refresh all now") }
        busy?.let {
            CircularProgressIndicator(Modifier.width(20.dp))
            Muted(it)
        }
    }
    messages.forEach { Muted(it) }

    val state = rememberLoaded(reload) { services.connections() to services.queries.freshness() }
    LoadedView(state) { (connections, freshness) ->
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            connections.forEach { c ->
                Panel(
                    c.label,
                    note =
                        if (c.connected) {
                            "Signed in on this device."
                        } else {
                            "Not signed in on this " +
                                "device."
                        },
                ) {
                    c.platforms.sortedBy { it.ordinal }.forEach { p ->
                        val f = freshness.single { it.platform == p }
                        if (c.platforms.size > 1) Text(p.displayName, fontWeight = FontWeight.Medium)
                        KeyValue("Latest capture", Labels.time(f.latestCapture, zone))
                        KeyValue("Last success", Labels.time(f.status?.lastSuccessAt, zone))
                        KeyValue("Last attempt", Labels.time(f.status?.lastAttemptAt, zone))
                        f.status?.lastError?.let { KeyValue("Last error", it) }
                        KeyValue(
                            "Sign-in expires",
                            f.status?.tokenExpiresAt?.let { Labels.time(it, zone) }
                                ?: "not reported (renewed by collecting)",
                        )
                        PlatformLimits.of(p).forEach { Muted("Limit: $it") }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(
                            enabled = busy == null,
                            onClick = {
                                busy = "Waiting for the ${c.label} sign-in in your browser (up to 5 minutes)..."
                                scope.launch {
                                    messages = listOf(services.connect(c.id))
                                    busy = null
                                    onChanged()
                                }
                            },
                        ) { Text(if (c.connected) "Sign in again" else "Sign in") }
                        if (c.platforms.isNotEmpty()) {
                            SecondaryButton(
                                enabled = busy == null,
                                onClick = {
                                    busy = "Refreshing ${c.label}..."
                                    scope.launch {
                                        messages = report(services.refresh(c.platforms))
                                        busy = null
                                        onChanged()
                                    }
                                },
                            ) { Text("Refresh now") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    services: AppServices,
    theme: ThemeChoice,
    onTheme: (ThemeChoice) -> Unit,
    reload: Int,
) {
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    ScreenTitle("Settings")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("Theme") { ChoiceChips(ThemeChoice.entries, theme, { it.label }, onTheme) }

        val vault = rememberLoaded(reload, message) { services.vaultStatus() }
        LoadedView(vault) { v ->
            var path by remember(v) { mutableStateOf(v?.vault.orEmpty()) }
            Panel(
                "Obsidian vault",
                note =
                    "FourSight writes the 7-day block and stats fields into video notes " +
                        "with post ids, after each collection.",
            ) {
                OutlinedTextField(path, {
                    path = it
                }, label = { Text("Vault folder") }, singleLine = true, modifier = Modifier.width(520.dp))
                PrimaryButton(onClick = { message = services.setVault(path.trim()) }) { Text("Save") }
                if (v != null) {
                    KeyValue("Video notes folder", v.notesFolder)
                    KeyValue("Stats Overview note", v.overviewNote)
                }
            }
        }

        Panel("Sync", note = "Each device keeps its own database; Google Drive holds the shared files.") {
            KeyValue(
                "Google Drive",
                if (services.driveConnected) {
                    "Connected"
                } else {
                    "Not connected (sign in " +
                        "under Accounts)"
                },
            )
        }

        Panel(
            "Export",
            note =
                "Every post and capture, no secrets. JSON keeps the structure; CSV has one row " +
                    "per captured value.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(onClick = {
                    scope.launch { message = services.export(ExportFormat.JSON) ?: message }
                }) { Text("Export JSON...") }
                SecondaryButton(onClick = {
                    scope.launch { message = services.export(ExportFormat.CSV) ?: message }
                }) { Text("Export CSV...") }
            }
        }
        message?.let { Muted(it) }

        Panel("How every number is calculated", note = "Definitions version ${Metrics.DEFINITIONS_VERSION}.") {
            Metrics.all.forEach { m ->
                Text("${m.label} (${m.unit})", style = MaterialTheme.typography.titleSmall)
                CombineBadge(m.kind.summable)
                Muted(m.definition)
                SocialPlatform.entries.forEach { p ->
                    when (val a = m.availability(p)) {
                        is Availability.Available -> Muted("  ${p.displayName}: ${a.source}")
                        is Availability.Unavailable -> Muted("  ${p.displayName}: not reported. ${a.reason}")
                    }
                }
            }
            Text("Computed numbers", style = MaterialTheme.typography.titleSmall)
            QueryJson.computedDefinitions.forEach { (id, text) -> Muted("$id: $text") }
        }
    }
}
