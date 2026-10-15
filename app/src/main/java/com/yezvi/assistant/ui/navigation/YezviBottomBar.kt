package com.yezvi.assistant.ui.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.ui.components.YervisIcon
import com.yezvi.assistant.ui.components.YervisPanel
import com.yezvi.assistant.ui.components.YervisSymbol
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion

private val bottomDestinations = listOf(
    Triple(AppDestination.TODAY, Destination.Today, YervisSymbol.Home),
    Triple(AppDestination.MEMORY, Destination.Memory, YervisSymbol.Memory),
    Triple(AppDestination.CAPTURE, Destination.Capture, YervisSymbol.Voice),
    Triple(AppDestination.SETTINGS, Destination.Settings, YervisSymbol.Settings),
)

@Composable
internal fun YezviBottomBar(
    currentDestination: AppDestination,
    onNavigateTo: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val selectedIndex = bottomDestinations.indexOfFirst { it.first == currentDestination }.coerceAtLeast(0)
    val position by animateFloatAsState(selectedIndex.toFloat(),
        tween(motionDurationMillis(Motion.NavigationMillis, reduceMotion), easing = FastOutSlowInEasing),
        label = "bottom-nav-position")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    YervisPanel(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), highlighted = true) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp)) {
            val tabWidth = maxWidth / bottomDestinations.size
            // This layer does not affect measurement: the labels determine the bar's height at 200% font.
            Box(Modifier.matchParentSize()) {
                Surface(
                    modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight()
                        .width(tabWidth - 4.dp).graphicsLayer {
                            translationX = ((tabWidth * position) + 2.dp).toPx() * if (rtl) -1f else 1f
                        }.testTag("bottom_nav_indicator"),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    content = {},
                )
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                bottomDestinations.forEachIndexed { index, (destination, route, symbol) ->
                    val selected = index == selectedIndex
                    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .selectable(selected, role = Role.Tab,
                                onClick = { if (!selected) onNavigateTo(destination) })
                            .padding(horizontal = 2.dp, vertical = 8.dp)
                            .testTag("bottom_nav_${route.route}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
                    ) {
                        YervisIcon(symbol, modifier = Modifier.size(24.dp), tint = contentColor)
                        Text(stringResource(route.labelRes), style = MaterialTheme.typography.labelSmall,
                            color = contentColor, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
