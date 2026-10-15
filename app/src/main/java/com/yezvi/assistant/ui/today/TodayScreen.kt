package com.yezvi.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.domain.memory.CaptureSourceType
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.presentation.memory.MemoryUiState
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.screens.SourceBadge
import com.yezvi.assistant.ui.theme.AppearancePreferences
import com.yezvi.assistant.ui.theme.LocalVisualMetrics
import com.yezvi.assistant.ui.theme.ThemeMode
import com.yezvi.assistant.ui.theme.VisualStyle
import com.yezvi.assistant.ui.theme.YERVISTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** A projection of existing local memories. No invented agenda or sample tasks in production. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScreen(
    modifier: Modifier = Modifier,
    state: MemoryUiState = MemoryUiState.Empty,
    onCaptureClick: () -> Unit = {},
    onMemoryClick: () -> Unit = {},
    onMemoryEntryClick: (String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val metrics = LocalVisualMetrics.current
    val content = state as? MemoryUiState.Content
    val recent = remember(content?.entries) { content?.entries.orEmpty().take(3) }
    Scaffold(
        modifier = modifier.fillMaxSize(), containerColor = Color.Transparent,
        topBar = { TopAppBar(title = {
            Text(stringResource(R.string.nav_today), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
        }) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
            Button(onClick = onCaptureClick,
                modifier = Modifier.fillMaxWidth().padding(horizontal = metrics.screenPadding, vertical = 12.dp)
                    .heightIn(min = 56.dp).testTag("today_capture_fab")) {
                YervisIcon(YervisSymbol.Capture, modifier = Modifier.size(22.dp))
                Text(stringResource(R.string.ux_today_capture_title), modifier = Modifier.padding(start = 12.dp))
            }
            }
        },
    ) { insets ->
        LazyColumn(Modifier.fillMaxSize().testTag("today_content"),
            contentPadding = PaddingValues(start = metrics.screenPadding, end = metrics.screenPadding,
                top = insets.calculateTopPadding() + 16.dp, bottom = insets.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.itemSpacing)) {
            item(key = "welcome", contentType = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ux_today_subtitle), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.ux_today_capture_detail), style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (content != null) Text(pluralStringResource(R.plurals.ux_today_memory_count,
                        content.entries.size, content.entries.size), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("today_memory_count"))
                }
            }
            when (state) {
                MemoryUiState.Loading -> item(key = "loading", contentType = "status") {
                    Row(Modifier.padding(vertical = 32.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                        Text(stringResource(R.string.ux_today_loading))
                    }
                }
                MemoryUiState.Empty -> item(key = "empty", contentType = "status") {
                    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp).testTag("today_empty"),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        YervisIcon(YervisSymbol.Memory, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.ux_today_empty_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.ux_today_empty_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                MemoryUiState.Error -> item(key = "error", contentType = "status") {
                    Column(Modifier.padding(vertical = 24.dp)) {
                        Text(stringResource(R.string.ux_today_error), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.memory_retry))
                        }
                    }
                }
                is MemoryUiState.Content -> {
                    item(key = "recent-title", contentType = "heading") {
                        Text(stringResource(R.string.ux_today_recent), style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 20.dp).semantics { heading() })
                    }
                    items(recent, key = { it.id }, contentType = { "memory" }) { entry ->
                        RecentMemory(entry, state.currentTextById[entry.id] ?: entry.rawText,
                            onClick = { onMemoryEntryClick(entry.id) })
                    }
                    item(key = "all", contentType = "action") {
                        TextButton(onClick = onMemoryClick, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.ux_today_open_memory))
                            YervisIcon(YervisSymbol.Next, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        }
                    }
                }
            }
            item(key = "privacy", contentType = "caption") {
                Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    YervisIcon(YervisSymbol.Shield, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.ux_today_local), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecentMemory(entry: MemoryEntry, text: String, onClick: () -> Unit) {
    val date = remember(entry.capturedAtEpochMillis, entry.timezoneId) {
        SimpleDateFormat("d MMM · HH:mm", Locale.forLanguageTag("es-ES")).apply {
            timeZone = TimeZone.getTimeZone(entry.timezoneId)
        }.format(Date(entry.capturedAtEpochMillis))
    }
    Surface(onClick = onClick, shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("today_memory_${entry.id}")) {
        Row(Modifier.padding(LocalVisualMetrics.current.panelPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SourceBadge(entry.sourceType)
                    Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            YervisIcon(YervisSymbol.Next, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(showBackground = true)
@Composable private fun TodayLightPreview() { YERVISTheme(dynamicColor = false) { TodayScreen() } }

@Preview(showBackground = true)
@Composable private fun TodayCrystalDarkPreview() {
    YERVISTheme(dynamicColor = false, appearance = AppearancePreferences(theme = ThemeMode.Dark, style = VisualStyle.Crystal)) {
        TodayScreen()
    }
}
