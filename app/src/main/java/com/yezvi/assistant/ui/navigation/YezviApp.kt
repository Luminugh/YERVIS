package com.yezvi.assistant.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yezvi.assistant.data.local.memory.YezviDatabaseFactory
import com.yezvi.assistant.data.repository.RoomMemoryCorrectionRepository
import com.yezvi.assistant.data.repository.RoomMemoryRepository
import com.yezvi.assistant.data.speech.SherpaOfflineSpeechToTextEngine
import com.yezvi.assistant.data.speech.AndroidLocalConfirmationSpeech
import com.yezvi.assistant.data.speech.AndroidLocalSpeechPreferencesStore
import com.yezvi.assistant.domain.speech.SpeechToTextEngine
import com.yezvi.assistant.domain.memory.CaptureInput
import com.yezvi.assistant.domain.memory.MemoryCorrectionRepository
import com.yezvi.assistant.domain.memory.MemoryRepository
import com.yezvi.assistant.presentation.actions.AppDestination
import com.yezvi.assistant.presentation.actions.MemoryUiCallbacks
import com.yezvi.assistant.presentation.capture.CaptureViewModel
import com.yezvi.assistant.presentation.intelligence.ProposalReviewDemoLoader
import com.yezvi.assistant.presentation.intelligence.ProposalReviewDemoViewModel
import com.yezvi.assistant.presentation.memory.EntryDeleteUiState
import com.yezvi.assistant.presentation.memory.EntryDetailViewModel
import com.yezvi.assistant.presentation.memory.MemoryViewModel
import com.yezvi.assistant.ui.screens.CaptureScreen
import com.yezvi.assistant.ui.screens.EntryDetailScreen
import com.yezvi.assistant.ui.screens.MemoryScreen
import com.yezvi.assistant.ui.screens.ProposalReviewScreen
import com.yezvi.assistant.ui.screens.SettingsScreen
import com.yezvi.assistant.ui.today.TodayScreen
import com.yezvi.assistant.ui.components.YervisBackdrop
import com.yezvi.assistant.ui.theme.Motion
import com.yezvi.assistant.ui.theme.motionDurationMillis
import com.yezvi.assistant.ui.theme.rememberReduceMotion
import kotlinx.coroutines.CoroutineScope

@Composable
internal fun YezviApp(
    navController: NavHostController = rememberNavController(),
    onCaptureRequested: (CaptureInput) -> Unit = {},
    captureViewModelOverride: CaptureViewModel? = null,
    memoryRepositoryOverride: MemoryRepository? = null,
    correctionRepositoryOverride: MemoryCorrectionRepository? = null,
) {
    val memoryRepository = memoryRepositoryOverride ?: rememberMemoryRepository()
    val correctionRepository = correctionRepositoryOverride ?: rememberMemoryCorrectionRepository()
    val injectedRepositoryKey = if (
        memoryRepositoryOverride != null || correctionRepositoryOverride != null
    ) {
        "injected-${System.identityHashCode(memoryRepository)}-" +
            System.identityHashCode(correctionRepository)
    } else {
        null
    }
    val sharedMemoryViewModel = memoryViewModel(memoryRepository, correctionRepository, injectedRepositoryKey)
    val appContext = LocalContext.current.applicationContext
    val reduceMotion = rememberReduceMotion()
    val navigationDuration = motionDurationMillis(Motion.NavigationMillis, reduceMotion)
    val speechPreferencesStore = remember(appContext) { AndroidLocalSpeechPreferencesStore(appContext) }
    val captureViewModel = captureViewModelOverride ?: rememberCaptureViewModel(memoryRepository, speechPreferencesStore)
    val voiceEngineFactory: (Lifecycle, CoroutineScope) -> SpeechToTextEngine = remember(appContext) {
        { lifecycle: Lifecycle, scope: CoroutineScope ->
            SherpaOfflineSpeechToTextEngine.create(appContext, lifecycle, scope)
        }
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

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTabIndex = if (currentRoute == null) 0 else topLevelTabRoutes.indexOf(currentRoute)
    val density = LocalDensity.current
    val swipeThresholdPx = with(density) { 72.dp.toPx() }
    var captureNavigationHandler by remember { mutableStateOf<((AppDestination) -> Unit)?>(null) }
    var captureEditorFocused by remember { mutableStateOf(false) }
    val updateCaptureNavigationHandler = remember {
        { handler: ((AppDestination) -> Unit)? -> captureNavigationHandler = handler }
    }
    val updateCaptureEditorFocus = remember { { focused: Boolean -> captureEditorFocused = focused } }
    val navigateFromBottomBar: (AppDestination) -> Unit = { destination ->
        if (currentRoute == Destination.Capture.route) {
            captureNavigationHandler?.invoke(destination) ?: callbacks.onNavigateTo(destination)
        } else {
            callbacks.onNavigateTo(destination)
        }
    }

    val currentNavigate by rememberUpdatedState(navigateFromBottomBar)
    YervisBackdrop {
        Column(modifier = Modifier.fillMaxSize()) {
            NavHost(
                navController = navController,
                startDestination = Destination.Today.route,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(currentTabIndex, currentRoute, captureEditorFocused, callbacks, swipeThresholdPx) {
                        val canSwipeTabs = currentTabIndex >= 0 &&
                            (currentRoute != Destination.Capture.route || !captureEditorFocused)
                        if (canSwipeTabs) {
                            var horizontalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val step = when {
                                        horizontalDrag <= -swipeThresholdPx -> 1
                                        horizontalDrag >= swipeThresholdPx -> -1
                                        else -> 0
                                    }
                                    val targetIndex = currentTabIndex + step
                                    if (step != 0 && targetIndex in topLevelTabRoutes.indices) {
                                        currentNavigate(topLevelDestination(targetIndex))
                                    }
                                    horizontalDrag = 0f
                                },
                                onDragCancel = { horizontalDrag = 0f },
                                onHorizontalDrag = { change, dragAmount ->
                                    change.consume()
                                    horizontalDrag += dragAmount
                                },
                            )
                        }
                    },
            ) {
                composable(
                    route = Destination.Today.route,
                    enterTransition = { tabEnterTransition(navigationDuration) },
                    exitTransition = { tabExitTransition(navigationDuration) },
                ) {
                    val state by sharedMemoryViewModel.uiState.collectAsStateWithLifecycle()
                    TodayScreen(
                        state = state,
                        onCaptureClick = { callbacks.onNavigateTo(AppDestination.CAPTURE) },
                        onMemoryClick = { callbacks.onNavigateTo(AppDestination.MEMORY) },
                        onMemoryEntryClick = callbacks.onMemoryEntrySelected,
                        onRetry = sharedMemoryViewModel::retry,
                    )
                }
                composable(
                    route = Destination.Memory.route,
                    enterTransition = { tabEnterTransition(navigationDuration) },
                    exitTransition = { tabExitTransition(navigationDuration) },
                ) {
                    val memoryViewModel = sharedMemoryViewModel
                    val state by memoryViewModel.uiState.collectAsStateWithLifecycle()
                    val selectionState by memoryViewModel.selectionState.collectAsStateWithLifecycle()
                    MemoryScreen(
                        state = state,
                        selectionState = selectionState,
                        callbacks = callbacks,
                        onRetry = memoryViewModel::retry,
                        onToggleSelection = memoryViewModel::toggleSelection,
                        onSelectAll = memoryViewModel::selectAll,
                        onClearSelection = memoryViewModel::clearSelection,
                        onRequestDeleteSelected = memoryViewModel::requestDeleteSelected,
                        onDismissDeleteSelected = memoryViewModel::dismissDeleteConfirmation,
                        onDeleteSelected = memoryViewModel::deleteSelected,
                    )
                }
                composable(
                    route = Destination.Capture.route,
                    enterTransition = { tabEnterTransition(navigationDuration) },
                    exitTransition = { tabExitTransition(navigationDuration) },
                ) {
                    CaptureScreen(
                        callbacks = callbacks,
                        viewModel = captureViewModel,
                        voiceEngineFactory = voiceEngineFactory,
                        onNavigationHandlerChanged = updateCaptureNavigationHandler,
                        onEditorFocusChanged = updateCaptureEditorFocus,
                    )
                }
                composable(
                    route = Destination.Settings.route,
                    enterTransition = { tabEnterTransition(navigationDuration) },
                    exitTransition = { tabExitTransition(navigationDuration) },
                ) {
                    SettingsScreen(
                        callbacks,
                        captureViewModel.spokenConfirmation,
                        onOpenProposalReview = { navController.navigate(Destination.ProposalReview.route) },
                    )
                }
                if (proposalReviewDemoEnabled()) {
                    composable(Destination.ProposalReview.route,
                        enterTransition = { secondaryEnterTransition(navigationDuration) },
                        popExitTransition = { secondaryExitTransition(navigationDuration) },
                    ) {
                        val context = LocalContext.current
                        val loader = remember(context) {
                            ProposalReviewDemoLoader { path ->
                                context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
                            }
                        }
                        val reviewViewModel = proposalReviewViewModel(loader)
                        val state by reviewViewModel.uiState.collectAsStateWithLifecycle()
                        ProposalReviewScreen(
                            state = state,
                            onBack = { navController.navigateUp() },
                            onSaveLocalFieldEdit = reviewViewModel::saveLocalFieldEdit,
                            onDecision = reviewViewModel::decide,
                        )
                    }
                }
                composable(
                    route = Destination.EntryDetail.route,
                    arguments = listOf(navArgument("entryId") { type = NavType.StringType }),
                    enterTransition = { secondaryEnterTransition(navigationDuration) },
                    popExitTransition = { secondaryExitTransition(navigationDuration) },
                ) { detailEntry ->
                    val entryId = detailEntry.arguments?.getString("entryId").orEmpty()
                    val detailViewModel = entryDetailViewModel(
                        memoryRepository = memoryRepository,
                        correctionRepository = correctionRepository,
                        entryId = entryId,
                        viewModelKey = injectedRepositoryKey?.let { "$it:$entryId" },
                    )
                    val state by detailViewModel.uiState.collectAsStateWithLifecycle()
                    val deleteState by detailViewModel.deleteState.collectAsStateWithLifecycle()
                    val correctionState by detailViewModel.correctionState.collectAsStateWithLifecycle()
                    EntryDetailScreen(
                        state = state,
                        deleteState = deleteState,
                        correctionState = correctionState,
                        onBack = { navController.navigateUp() },
                        onRetry = detailViewModel::retry,
                        onDelete = detailViewModel::delete,
                        onCancelDelete = detailViewModel::dismissDelete,
                        onRetryCorrections = detailViewModel::retryCorrections,
                        onBeginCorrection = detailViewModel::beginCorrection,
                        onCorrectionDraftChange = detailViewModel::updateCorrectionDraft,
                        onSaveCorrection = detailViewModel::saveCorrection,
                        onCancelCorrection = detailViewModel::cancelCorrection,
                    )
                }
            }

            if (currentTabIndex >= 0) {
                YezviBottomBar(
                    currentDestination = topLevelDestination(currentTabIndex),
                    onNavigateTo = navigateFromBottomBar,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabEnterTransition(
    durationMillis: Int,
): EnterTransition {
    if (durationMillis <= 0) return EnterTransition.None
    val direction = topLevelNavigationDirection(
        initialState.destination.route,
        targetState.destination.route,
    ) ?: return EnterTransition.None

    return slideInHorizontally(
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
    ) { width -> (width / 12) * direction }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.tabExitTransition(
    durationMillis: Int,
): ExitTransition {
    if (durationMillis <= 0) return ExitTransition.None
    val direction = topLevelNavigationDirection(
        initialState.destination.route,
        targetState.destination.route,
    ) ?: return ExitTransition.None

    return slideOutHorizontally(
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
    ) { width -> -(width / 12) * direction }
}

private fun topLevelNavigationDirection(initialRoute: String?, targetRoute: String?): Int? {
    val initialIndex = topLevelTabRoutes.indexOf(initialRoute)
    val targetIndex = topLevelTabRoutes.indexOf(targetRoute)
    return when {
        initialIndex < 0 || targetIndex < 0 || initialIndex == targetIndex -> null
        targetIndex > initialIndex -> 1
        else -> -1
    }
}

private fun secondaryEnterTransition(durationMillis: Int): EnterTransition =
    if (durationMillis <= 0) EnterTransition.None
    else slideInHorizontally(tween(durationMillis, easing = FastOutSlowInEasing)) { it / 12 }

private fun secondaryExitTransition(durationMillis: Int): ExitTransition =
    if (durationMillis <= 0) ExitTransition.None
    else slideOutHorizontally(tween(durationMillis, easing = FastOutSlowInEasing)) { it / 12 }

@Composable
private fun proposalReviewViewModel(loader: ProposalReviewDemoLoader): ProposalReviewDemoViewModel {
    val factory = remember(loader) {
        viewModelFactory {
            initializer { ProposalReviewDemoViewModel(loader) }
        }
    }
    return viewModel(factory = factory)
}

@Composable
private fun rememberMemoryRepository(): MemoryRepository {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        RoomMemoryRepository(YezviDatabaseFactory.getInstance(appContext).memoryEntryDao())
    }
}

@Composable
private fun rememberMemoryCorrectionRepository(): MemoryCorrectionRepository {
    val appContext = LocalContext.current.applicationContext
    return remember(appContext) {
        RoomMemoryCorrectionRepository(
            YezviDatabaseFactory.getInstance(appContext).memoryCorrectionDao(),
        )
    }
}

@Composable
private fun rememberCaptureViewModel(
    memoryRepository: MemoryRepository,
    speechPreferencesStore: AndroidLocalSpeechPreferencesStore,
): CaptureViewModel {
    val appContext = LocalContext.current.applicationContext
    val factory = remember(memoryRepository, appContext, speechPreferencesStore) {
        viewModelFactory {
            initializer {
                CaptureViewModel(
                    memoryRepository,
                    confirmationSpeechFactory = { AndroidLocalConfirmationSpeech(appContext) },
                    speechPreferencesStore = speechPreferencesStore,
                )
            }
        }
    }
    return viewModel(factory = factory)
}

@Composable
private fun memoryViewModel(
    memoryRepository: MemoryRepository,
    correctionRepository: MemoryCorrectionRepository,
    viewModelKey: String?,
): MemoryViewModel {
    val factory = remember(memoryRepository, correctionRepository) {
        viewModelFactory {
            initializer { MemoryViewModel(memoryRepository, correctionRepository) }
        }
    }
    return viewModel(key = viewModelKey, factory = factory)
}

@Composable
private fun entryDetailViewModel(
    memoryRepository: MemoryRepository,
    correctionRepository: MemoryCorrectionRepository,
    entryId: String,
    viewModelKey: String?,
): EntryDetailViewModel {
    val factory = remember(memoryRepository, correctionRepository, entryId) {
        viewModelFactory {
            initializer { EntryDetailViewModel(memoryRepository, correctionRepository, entryId) }
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

private val topLevelTabRoutes = listOf(
    Destination.Today.route,
    Destination.Memory.route,
    Destination.Capture.route,
    Destination.Settings.route,
)

private fun topLevelDestination(index: Int): AppDestination = when (index) {
    0 -> AppDestination.TODAY
    1 -> AppDestination.MEMORY
    2 -> AppDestination.CAPTURE
    3 -> AppDestination.SETTINGS
    else -> error("Invalid top-level tab index: $index")
}
