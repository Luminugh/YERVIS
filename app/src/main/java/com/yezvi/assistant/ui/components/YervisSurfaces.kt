package com.yezvi.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.yezvi.assistant.ui.theme.LocalAppearance
import com.yezvi.assistant.ui.theme.VisualStyle

/** Tonal transparency, not backdrop blur. Restricted to highlighted surfaces. */
@Composable
fun YervisPanel(modifier: Modifier = Modifier, highlighted: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val crystal = highlighted && LocalAppearance.current.style == VisualStyle.Crystal
    Surface(modifier = modifier, shape = MaterialTheme.shapes.large,
        color = if (crystal) colors.surfaceContainerLow.copy(alpha = 0.88f)
            else if (highlighted) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        contentColor = colors.onSurface, tonalElevation = if (highlighted) 1.dp else 0.dp,
        shadowElevation = if (crystal) 1.dp else 0.dp,
        border = if (crystal) BorderStroke(1.dp, Brush.linearGradient(listOf(
            colors.onSurface.copy(alpha = 0.22f), colors.primary.copy(alpha = 0.12f),
            colors.onSurface.copy(alpha = 0.06f)))) else null,
    ) { content() }
}

@Composable
fun YervisBackdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = if (LocalAppearance.current.style == VisualStyle.Crystal) {
        Modifier.background(Brush.linearGradient(listOf(colors.background,
            colors.primaryContainer.copy(alpha = 0.38f), colors.background,
            colors.secondaryContainer.copy(alpha = 0.26f))))
    } else Modifier.background(colors.background)
    Box(modifier.fillMaxSize().background(colors.background).then(background)) { content() }
}
