package com.yezvi.assistant.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.R
import com.yezvi.assistant.ui.theme.YEZVITheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * F1.07 — Inicio «Hoy».
 *
 * Vista de solo lectura con DATOS SINTÉTICOS. No persiste nada: el modelo
 * [DemoTodayItem] es provisional y vive en la capa de UI hasta que Codex
 * entregue el contrato real (F1.01) y la persistencia (F2). No es un
 * contrato de dominio.
 */
internal enum class DemoItemKind(val label: String) {
    Tarea("Tarea"),
    Evento("Evento"),
    Idea("Idea"),
}

internal data class DemoTodayItem(
    val id: String,
    val kind: DemoItemKind,
    val title: String,
    val detail: String? = null,
    val timeLabel: String? = null,
)

private val demoPendingItems = listOf(
    DemoTodayItem("p1", DemoItemKind.Evento, "Llamada con Ana", "Confirmar a las 17:00", "17:00"),
    DemoTodayItem("p2", DemoItemKind.Tarea, "Enviar borrador del informe", "Pendiente de ayer", "09:30"),
    DemoTodayItem("p3", DemoItemKind.Tarea, "Revisar gastos del mes", null, "20:00"),
)

private val demoRecentItems = listOf(
    DemoTodayItem("r1", DemoItemKind.Idea, "Probar grabación sin conexión", "Nota de voz sin nube", "12:10"),
    DemoTodayItem("r2", DemoItemKind.Idea, "Idea para el widget de captura", null, "08:05"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScreen(
    modifier: Modifier = Modifier,
    pendingItems: List<DemoTodayItem> = demoPendingItems,
    recentItems: List<DemoTodayItem> = demoRecentItems,
    onCaptureClick: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.today_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = formatTodayDate(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onCaptureClick) {
                Text(text = stringResource(R.string.today_capture))
            }
        },
        floatingActionButtonPosition = FabPosition.End,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DemoNoticeBanner() }
            item {
                DaySummary(
                    pendingCount = pendingItems.size,
                    recentCount = recentItems.size,
                )
            }

            if (pendingItems.isEmpty() && recentItems.isEmpty()) {
                item { EmptyTodayState() }
            } else {
                if (pendingItems.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.today_section_pending)) }
                    items(pendingItems, key = { it.id }) { TodayItemCard(it) }
                }
                if (recentItems.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.today_section_recent)) }
                    items(recentItems, key = { it.id }) { TodayItemCard(it) }
                }
            }
        }
    }
}

@Composable
private fun DemoNoticeBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.today_demo_notice),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun DaySummary(pendingCount: Int, recentCount: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.today_summary, pendingCount, recentCount),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun TodayItemCard(item: DemoTodayItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KindBadge(item.kind)
                if (item.timeLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.timeLabel,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(text = item.title, style = MaterialTheme.typography.bodyLarge)
            if (item.detail != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun KindBadge(kind: DemoItemKind, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = kind.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyTodayState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.today_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.today_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatTodayDate(): String =
    SimpleDateFormat("EEEE d 'de' MMMM", Locale.forLanguageTag("es-ES")).format(Date())

@Preview(showBackground = true, name = "Hoy — datos demo")
@Composable
private fun TodayScreenPreview() {
    YEZVITheme(dynamicColor = false) {
        TodayScreen()
    }
}

@Preview(showBackground = true, name = "Hoy — estado vacío")
@Composable
private fun TodayScreenEmptyPreview() {
    YEZVITheme(dynamicColor = false) {
        TodayScreen(pendingItems = emptyList(), recentItems = emptyList())
    }
}
