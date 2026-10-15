package com.yezvi.assistant.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Native outline vectors with one visual weight, independent of fonts and bitmap assets. */
enum class YervisSymbol(private val path: String) {
    Home("M3 10L12 3L21 10M5 9V21H10V15H14V21H19V9"),
    Memory("M5 3H19V21H5ZM8 7H16M8 11H16M8 15H13"),
    Capture("M12 4V20M4 12H20"),
    Settings("M9 3H15L16 6L19 7L21 12L19 17L16 18L15 21H9L8 18L5 17L3 12L5 7L8 6ZM15 12A3 3 0 1 1 9 12A3 3 0 1 1 15 12"),
    Back("M15 5L8 12L15 19M8 12H21"),
    Next("M9 5L16 12L9 19"),
    Text("M4 5H20M12 5V21M8 21H16"),
    Voice("M9 5A3 3 0 0 1 15 5V12A3 3 0 0 1 9 12ZM6 10V12A6 6 0 0 0 18 12V10M12 18V22M8 22H16"),
    Calendar("M4 5H20V21H4ZM4 10H20M8 3V7M16 3V7M8 14H10M14 14H16M8 18H10"),
    Clock("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M12 7V12L16 14"),
    Person("M16 7A4 4 0 1 1 8 7A4 4 0 1 1 16 7M4 21V19A8 6 0 0 1 20 19V21"),
    Project("M3 6H10L12 8H21V20H3Z"),
    Task("M9 4H20V21H4V4H7M8 2H16V6H8ZM8 13L11 16L17 10"),
    Routine("M4 9A8 8 0 0 1 18 6L21 9M21 4V9H16M20 15A8 8 0 0 1 6 18L3 15M3 20V15H8"),
    Habit("M5 4H19V21H5ZM8 2V6M16 2V6M9 14L11 16L16 11"),
    Reminder("M6 16V10A6 6 0 0 1 18 10V16L20 18H4ZM10 21H14M12 2V4"),
    Idea("M8 15C2 10 6 3 12 3C18 3 22 10 16 15L15 18H9ZM9 21H15M12 14V18"),
    Fact("M5 3H15L19 7V21H5ZM14 3V8H19M8 12H16M8 16H14"),
    Goal("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M17 12A5 5 0 1 1 7 12A5 5 0 1 1 17 12M12 12L20 4"),
    Decision("M12 21V11M12 11L5 4M12 11L19 4M3 4H8M3 4V9M21 4H16M21 4V9"),
    Edit("M4 16L16 4L20 8L8 20L3 21ZM14 6L18 10"),
    Delete("M4 6H20M9 3H15M6 6L7 21H17L18 6M10 10V17M14 10V17"),
    Search("M17 10A7 7 0 1 1 3 10A7 7 0 1 1 17 10M15 15L21 21"),
    Filter("M3 4H21L14 12V20L10 18V12Z"),
    Check("M4 12L9 17L20 6"),
    Close("M5 5L19 19M19 5L5 19"),
    Info("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M12 11V17M12 7V7.1"),
    Shield("M12 2L20 6V12C20 17 16 20 12 22C8 20 4 17 4 12V6ZM8 12L11 15L16 9"),
    ExpandMore("M5 9L12 16L19 9"),
    ExpandLess("M5 15L12 8L19 15"),
    Play("M8 4L20 12L8 20Z"),
    Pause("M8 5V19M16 5V19"),
    Stop("M6 6H18V18H6Z"),
    Palette("M21 12C21 5 16 3 12 3C6 3 3 7 3 12C3 18 8 21 12 21H14C17 21 16 17 18 16C20 15 21 14 21 12M7 10V10.1M11 7V7.1M16 9V9.1M7 15V15.1"),
    Sun("M16 12A4 4 0 1 1 8 12A4 4 0 1 1 16 12M12 2V4M12 20V22M2 12H4M20 12H22M5 5L6 6M18 18L19 19M5 19L6 18M18 6L19 5"),
    Moon("M20 15A9 9 0 0 1 9 4A9 9 0 1 0 20 15"),
    Contrast("M21 12A9 9 0 1 1 3 12A9 9 0 1 1 21 12M12 3V21M12 6H16M12 9H19M12 12H20M12 15H19M12 18H16"),
    Motion("M3 6H12M3 12H8M3 18H12M14 6L20 12L14 18"),
    ;
    internal val vector: ImageVector by lazy {
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = this == Back || this == Next)
            .addPath(pathData = PathParser().parsePathString(path).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
    }
}

@Composable
fun YervisIcon(symbol: YervisSymbol, contentDescription: String? = null,
    modifier: Modifier = Modifier, tint: Color = LocalContentColor.current,
) = Icon(symbol.vector, contentDescription, modifier, tint)
