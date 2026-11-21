package app.viewfinder

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.viewfinder.ui.ConnectScreen
import app.viewfinder.ui.GalleryActions
import app.viewfinder.ui.GalleryScreen
import app.viewfinder.ui.LogScreen
import app.viewfinder.ui.RemoteActions
import app.viewfinder.ui.RemoteScreen
import app.viewfinder.ui.SettingsActions
import app.viewfinder.ui.SettingsScreen
import app.viewfinder.ui.TileReveal
import app.viewfinder.ui.ViewerScreen
import app.viewfinder.ui.ViewfinderTheme
import app.viewfinder.ui.LocalScreenVisibility
import app.viewfinder.ui.LocalSharedTransition
import app.viewfinder.ui.Motion
import app.viewfinder.ui.ShareActions
import app.viewfinder.ui.SharePanel
import app.viewfinder.ui.learn.ExplainPhotoScreen
import app.viewfinder.ui.learn.LearnScreen
import app.viewfinder.ui.learn.LessonScreen
import androidx.compose.ui.platform.LocalContext
import android.content.ActivityNotFoundException

class MainActivity : ComponentActivity() {
    /** What the tile or a launcher shortcut asked for; the app clears it once handled. */
    private val launchAction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Transfers and live view need the camera link alive; keep the screen on while the app is in front.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) launchAction.value = intent?.action
        setContent { ViewfinderTheme { App(launchAction) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        launchAction.value = intent.action
    }

    companion object {
        const val ACTION_REMOTE = "app.viewfinder.action.REMOTE"
        const val ACTION_GALLERY = "app.viewfinder.action.GALLERY"
        const val ACTION_LEARN = "app.viewfinder.action.LEARN"
    }
}

private sealed interface Screen {
    /** The gallery once connected; shown as [Connect] until then. */
    data object Main : Screen
    data object Connect : Screen
    data class Viewer(val handle: Long) : Screen
    data object Remote : Screen
    data object Settings : Screen
    data object Log : Screen
    /** Learning works with no camera at all, so these screens survive the camera disconnecting. */
    data object Learn : Screen
    data class Lesson(val id: String) : Screen
    data class Explain(val uri: android.net.Uri) : Screen
}

private val Screen.isLearn get() = this == Screen.Learn || this is Screen.Lesson || this is Screen.Explain

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun App(launchAction: MutableStateFlow<String?>, vm: CameraViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val log by vm.log.collectAsStateWithLifecycle()
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var returnTo by remember { mutableStateOf<Screen>(Screen.Main) }
    val grid = rememberLazyGridState()
    val reveal = remember(state.info) { TileReveal() }
    val connected = state.connection is Connection.Connected
    val motion = Motion.current
    val context = LocalContext.current

    // Sharing: one panel above every screen, and the share sheets the view model prepares.
    var shareTiles by remember { mutableStateOf<List<PhotoItem>?>(null) }
    val share = remember(vm) {
        ShareActions(
            share = { vm.share(it) },
            openPanel = { vm.refreshShareApp(); shareTiles = it },
            cancel = vm::cancelShare,
        )
    }
    LaunchedEffect(vm) {
        vm.shareIntents.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                vm.notify("That app can't take this share any more")
            }
        }
    }

    fun go(to: Screen) {
        returnTo = when (to) {
            is Screen.Viewer, Screen.Log -> screen
            is Screen.Lesson, is Screen.Explain -> Screen.Learn
            else -> Screen.Main
        }
        screen = to
    }

    // Lessons finished, kept between launches.
    val settings = remember { Settings(context) }
    var learnDone by remember { mutableStateOf(settings.learnDone) }

    BackHandler(enabled = screen != Screen.Main || state.selection.isNotEmpty()) {
        if (screen != Screen.Main) screen = if (screen == returnTo) Screen.Main else returnTo else vm.clearSelection()
    }

    // Leaving the connected state always lands back on the connect screen.
    LaunchedEffect(connected) { if (!connected && screen != Screen.Log && !screen.isLearn) screen = Screen.Main }

    // The tile and the Remote shortcut land on the remote as soon as the camera is connected.
    val action by launchAction.collectAsStateWithLifecycle()
    var wantRemote by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(action) {
        when (action) {
            MainActivity.ACTION_REMOTE -> wantRemote = true
            MainActivity.ACTION_GALLERY -> screen = Screen.Main
            MainActivity.ACTION_LEARN -> {
                // Learning needs no camera, so it doesn't reconnect.
                go(Screen.Learn)
                launchAction.value = null
                return@LaunchedEffect
            }
            else -> return@LaunchedEffect
        }
        vm.reconnect()
        launchAction.value = null
    }
    LaunchedEffect(connected, wantRemote) {
        if (connected && wantRemote) {
            wantRemote = false
            go(Screen.Remote)
        }
    }

    // Connecting and connected are different screens to the switcher, so the gallery arrives with a transition.
    val shown = if (screen == Screen.Main && !connected) Screen.Connect else screen

    SharedTransitionLayout {
        AnimatedContent(
            targetState = shown,
            transitionSpec = { motion.screen(this) },
            label = "screens",
        ) { s ->
            CompositionLocalProvider(LocalSharedTransition provides this@SharedTransitionLayout, LocalScreenVisibility provides this) {
                when (s) {
                    Screen.Log -> LogScreen(log) { screen = returnTo }
                    Screen.Learn -> LearnScreen(
                        done = learnDone,
                        onOpen = { go(Screen.Lesson(it.id)) },
                        onExplain = { go(Screen.Explain(it)) },
                        onBack = { screen = Screen.Main },
                    )
                    is Screen.Lesson -> {
                        val lesson = remember(s.id) { app.viewfinder.learn.Learn.lesson(s.id) }
                        if (lesson != null) {
                            fun markDone() {
                                learnDone = learnDone + lesson.id
                                settings.learnDone = learnDone
                            }
                            LessonScreen(
                                lesson = lesson,
                                onClose = { screen = Screen.Learn },
                                onFinish = { markDone(); screen = Screen.Learn },
                                onOpenLesson = { id -> markDone(); go(Screen.Lesson(id)) },
                            )
                        }
                    }
                    is Screen.Explain -> ExplainPhotoScreen(s.uri, onLesson = { go(Screen.Lesson(it)) }, onBack = { screen = Screen.Learn })
                    is Screen.Viewer -> ViewerScreen(
                        state = state,
                        startHandle = s.handle,
                        preview = vm::preview,
                        thumbnail = vm::thumbnail,
                        fullImage = vm::fullImage,
                        onSave = vm::save,
                        onDelete = vm::delete,
                        onBack = { screen = returnTo },
                        share = share,
                    )
                    Screen.Remote -> {
                        DisposableEffect(Unit) {
                            vm.startLiveView()
                            onDispose { vm.stopLiveView() }
                        }
                        RemoteScreen(
                            state,
                            RemoteActions(
                                back = { screen = Screen.Main },
                                retry = vm::startLiveView,
                                focusAt = vm::focusAt,
                                autofocus = vm::autofocus,
                                shoot = vm::shoot,
                                manualFocus = vm::manualFocus,
                                toggleMovie = vm::toggleMovie,
                                toggleBulb = vm::toggleBulb,
                                setProperty = vm::setProperty,
                                thumbnail = vm::thumbnail,
                                openPhoto = { go(Screen.Viewer(it)) },
                                share = share,
                                dismissMessage = vm::dismissMessage,
                            ),
                        )
                    }
                    Screen.Settings -> {
                        LaunchedEffect(Unit) { vm.loadProperties() }
                        SettingsScreen(
                            state,
                            vm.diagnostics,
                            SettingsActions(
                                back = { screen = Screen.Main },
                                setProperty = vm::setProperty,
                                syncClock = vm::syncClock,
                                formatCards = vm::formatCards,
                                showLog = { go(Screen.Log) },
                                dismissMessage = vm::dismissMessage,
                            ),
                        )
                    }
                    Screen.Main -> GalleryScreen(
                        state,
                        grid,
                        reveal,
                        GalleryActions(
                            thumbnail = vm::thumbnail,
                            open = { go(Screen.Viewer(it.handle)) },
                            toggleSelection = vm::toggleSelection,
                            selectAll = vm::selectAll,
                            clearSelection = vm::clearSelection,
                            saveSelection = vm::saveSelection,
                            deleteSelection = vm::deleteSelection,
                            saveAllUnsaved = vm::saveAllUnsaved,
                            shareSelection = vm::shareSelection,
                            share = share,
                            setFilter = vm::setFilter,
                            setAutoSave = vm::setAutoSave,
                            refresh = vm::refresh,
                            openRemote = { go(Screen.Remote) },
                            openSettings = { go(Screen.Settings) },
                            showLog = { go(Screen.Log) },
                            openLearn = { go(Screen.Learn) },
                            disconnect = vm::disconnect,
                            dismissMessage = vm::dismissMessage,
                        ),
                    )
                    Screen.Connect -> ConnectScreen(
                        state = state,
                        onChange = vm::updateWifi,
                        onConnect = vm::connect,
                        onCancel = vm::disconnect,
                        onShowLog = { go(Screen.Log) },
                        onScan = vm::scanWifi,
                        onLearn = { go(Screen.Learn) },
                    )
                }
            }
        }
    }

    shareTiles?.let { tiles ->
        SharePanel(
            tiles = tiles,
            state = state,
            preview = vm::preview,
            thumbnail = vm::thumbnail,
            caption = vm::shareCaption,
            onOptionsChange = vm::updateShareOptions,
            onShare = { options, toLastApp, focusX, focusY ->
                vm.updateShareOptions(options)
                vm.share(
                    tiles, options,
                    target = if (toLastApp) state.lastShareApp?.component else null,
                    focusX = focusX, focusY = focusY,
                    clearSelectionAfter = state.selection.isNotEmpty(),
                )
                shareTiles = null
            },
            onDismiss = { shareTiles = null },
        )
    }
}
