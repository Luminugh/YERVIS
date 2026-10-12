package com.yezvi.assistant.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.presentation.memory.EntryDetailViewModel
import com.yezvi.assistant.presentation.memory.MemoryViewModel
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
    captureViewModelOverride: CaptureViewModel? = null,
    memoryRepositoryOverride: MemoryRepository? = null,
) {
    val memoryRepository = memoryRepositoryOverride ?: rememberMemoryRepository()
    val injectedRepositoryKey = memoryRepositoryOverride?.let {
        "injected-${System.identityHashCode(it)}"
    }
    val captureViewModel = captureViewModelOverride ?: rememberCaptureViewModel(memoryRepository)
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
            val memoryViewModel = memoryViewModel(memoryRepository, injectedRepositoryKey)
            val state by memoryViewModel.uiState.collectAsStateWithLifecycle()
            MemoryScreen(
                state = state,
                callbacks = callbacks,
                onRetry = memoryViewModel::retry,
            )
        }
        composable(Destination.Capture.route) { CaptureScreen(callbacks, captureViewModel) }
        composable(Destination.Settings.route) { SettingsScreen(callbacks) }
        composable(
            route = Destination.EntryDetail.route,
            arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getString("entryId").orEmpty()
            val detailViewModel = entryDetailViewModel(
                memoryRepository = memoryRepository,
                entryId = entryId,
                viewModelKey = injectedRepositoryKey?.let { "$it:$entryId" },
            )
            val state by detailViewModel.uiState.collectAsStateWithLifecycle()
            EntryDetailScreen(
                state = state,
                onBack = { navController.navigateUp() },
                onRetry = detailViewModel::retry,
            )
        }
    }
}

@Composable
private fun rememberMemoryRepository(): MemoryRepository {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        RoomMemoryRepository(YezviDatabaseFactory.getInstance(appContext).memoryEntryDao())
    }
}

@Composable
private fun rememberCaptureViewModel(memoryRepository: MemoryRepository): CaptureViewModel {
    val factory = remember(memoryRepository) {
        viewModelFactory {
            initializer {
                CaptureViewModel(memoryRepository)
            }
        }
    }
    return viewModel(factory = factory)
}

@Composable
private fun memoryViewModel(
    memoryRepository: MemoryRepository,
    viewModelKey: String?,
): MemoryViewModel {
    val factory = remember(memoryRepository) {
        viewModelFactory {
            initializer { MemoryViewModel(memoryRepository) }
        }
    }
    return viewModel(key = viewModelKey, factory = factory)
}

@Composable
private fun entryDetailViewModel(
    memoryRepository: MemoryRepository,
    entryId: String,
    viewModelKey: String?,
): EntryDetailViewModel {
    val factory = remember(memoryRepository, entryId) {
        viewModelFactory {
            initializer { EntryDetailViewModel(memoryRepository, entryId) }
        }
    }
    return viewModel(key = viewModelKey, factory = factory)
}

private fun AppDestination.route(): String = when (this) {
    AppDestination.TODAY -> Destination.Today.route
    AppDestination.MEMORY -> Destination.Memory.route
    AppDestination.CAPTURE -> Destination.Capture.route
    AppDestination.SETTINGS -> Destination.Settings.route
}
