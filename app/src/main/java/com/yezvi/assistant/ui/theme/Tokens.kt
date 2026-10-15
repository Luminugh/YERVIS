package com.yezvi.assistant.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

val YervisShapes = Shapes(
    extraSmall = RoundedCornerShape(Spacing.xs),
    small = RoundedCornerShape(Spacing.sm),
    medium = RoundedCornerShape(Spacing.lg),
    large = RoundedCornerShape(Spacing.xl),
    extraLarge = RoundedCornerShape(32.dp),
)

object Motion {
    const val StateChangeMillis = 200
    const val ExpandCollapseMillis = 300
    const val NavigationMillis = 200
}
