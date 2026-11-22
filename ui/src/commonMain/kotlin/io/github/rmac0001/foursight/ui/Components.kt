package io.github.rmac0001.foursight.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A screen's data: loading, loaded, or failed with a message that's safe to show. */
sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>

    data class Done<T>(
        val value: T,
    ) : Loaded<T>

    data class Failed(
        val message: String,
    ) : Loaded<Nothing>
}

/**
 * Loads [load] off the main thread (the database is blocking) and reloads when any of [keys]
 * changes. Failures show the exception's class only: a message could carry anything.
 */
@Composable
fun <T> rememberLoaded(
    vararg keys: Any?,
    load: () -> T,
): Loaded<T> =
    produceState<Loaded<T>>(Loaded.Loading, *keys) {
        value =
            try {
                Loaded.Done(withContext(Dispatchers.Default) { load() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Loaded.Failed("Couldn't load this (${e::class.simpleName}).")
            }
    }.value

@Composable
fun <T> LoadedView(
    state: Loaded<T>,
    content: @Composable (T) -> Unit,
) {
    when (state) {
        Loaded.Loading -> {
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        is Loaded.Failed -> {
            EmptyState(state.message)
        }

        is Loaded.Done -> {
            content(state.value)
        }
    }
}

@Composable
fun EmptyState(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ScreenTitle(
    title: String,
    subtitle: String? = null,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        subtitle?.let { Muted(it) }
    }
}

/** A titled card holding one part of a screen. */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            note?.let { Muted(it) }
            content()
        }
    }
}

/** A big number with a label and an optional line under it (change, capture time, why it's missing). */
@Composable
fun StatTile(
    label: String,
    value: String,
    sub: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.widthIn(min = 150.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            sub?.let { Muted(it) }
        }
    }
}

@Composable
fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A row of single-choice chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
        }
    }
}

/** A row of multi-choice chips; at least one stays selected. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> MultiChips(
    options: List<T>,
    selected: Set<T>,
    label: (T) -> String,
    onChange: (Set<T>) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o in selected,
                onClick = {
                    val next = if (o in selected) selected - o else selected + o
                    if (next.isNotEmpty()) onChange(next)
                },
                label = { Text(label(o)) },
            )
        }
    }
}

/** Two cells of a simple label/value table. */
@Composable
fun KeyValue(
    key: String,
    value: String,
    note: String? = null,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            note?.let { Muted(it) }
        }
    }
}

/** A labeled badge: whether a number can be summed across platforms (spec: Combining rules). */
@Composable
fun CombineBadge(summable: Boolean) {
    Muted(if (summable) "Count: summed across platforms" else "Rate or duration: shown per platform, never combined")
}
