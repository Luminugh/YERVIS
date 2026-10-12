package com.yezvi.assistant.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yezvi.assistant.data.demo.DemoMemoryRepository
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.MemoryEntry
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.ui.screens.CaptureScreen
import com.yezvi.assistant.ui.screens.EntryDetailScreen
import com.yezvi.assistant.ui.screens.MemoryScreen
import com.yezvi.assistant.ui.screens.SettingsScreen
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion
import com.yezvi.assistant.ui.today.TodayScreen

@Composable
internal fun YezviApp(
    navController: NavHostController = rememberNavController(),
    onCaptureRequested: (CaptureInput) -> Unit = {},
) {
    val demoEntries = remember { DemoMemoryRepository.getEntries() }
    val findDemoEntryById: (String) -> MemoryEntry? = remember(demoEntries) {
        { entryId -> demoEntries.firstOrNull { it.id == entryId } }
    }
    val callbacks = remember(navController, onCaptureRequested) {
        MemoryUiCallbacks(
            // F1 only reviews drafts; F2 supplies a handler when an explicit save action exists.
            onCaptureRequested = onCaptureRequested,
            onNavigateTo = { destination ->
                navController.navigate(destination.route()) {
                    popUpTo(Destination.Today.route) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            },
            onMemoryEntrySelected = { entryId ->
                navController.navigate(Destination.EntryDetail.routeFor(entryId))
            },
        )
    }

    val reduceMotion = rememberReduceMotion()
    val navDuration = motionDurationMillis(Motion.StateChangeMillis, reduceMotion)

    NavHost(
        navController = navController,
        startDestination = Destination.Today.route,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { fadeIn(animationSpec = tween(durationMillis = navDuration)) },
        exitTransition = { fadeOut(animationSpec = tween(durationMillis = navDuration)) },
        popEnterTransition = { fadeIn(animationSpec = tween(durationMillis = navDuration)) },
        popExitTransition = { fadeOut(animationSpec = tween(durationMillis = navDuration)) },
    ) {
        composable(Destination.Today.route) {
            TodayScreen(
                onCaptureClick = { callbacks.onNavigateTo(AppDestination.CAPTURE) },
                bottomBar = {
                    YezviBottomBar(
                        currentDestination = AppDestination.TODAY,
                        onNavigateTo = callbacks.onNavigateTo,
                    )
                },
            )
        }
        composable(Destination.Memory.route) {
            MemoryScreen(entries = demoEntries, callbacks = callbacks)
        }
        composable(Destination.Capture.route) { CaptureScreen(callbacks) }
        composable(Destination.Settings.route) { SettingsScreen(callbacks) }
        composable(
            route = Destination.EntryDetail.route,
            arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
        ) { backStackEntry ->
            EntryDetailScreen(
                entryId = backStackEntry.arguments?.getString("entryId").orEmpty(),
                onBack = { navController.navigateUp() },
                findEntryById = findDemoEntryById,
            )
        }
    }
}

private fun AppDestination.route(): String = when (this) {
    AppDestination.TODAY -> Destination.Today.route
    AppDestination.MEMORY -> Destination.Memory.route
    AppDestination.CAPTURE -> Destination.Capture.route
    AppDestination.SETTINGS -> Destination.Settings.route
}
