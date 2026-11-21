package app.viewfinder.ui

import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viewfinder.MediaKind
import app.viewfinder.PhotoItem
import app.viewfinder.UiState
import app.viewfinder.ptp.camera.Capability
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val takenDay = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault())
private val takenTime = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

/**
 * How long a photo may take to load before the spinner shows. A UX threshold, not an animation: local
 * decodes finish well inside it, so swiping through saved photos never flashes a spinner.
 */
private const val SPINNER_DELAY_MILLIS = 300L

private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 5f

@Composable
fun ViewerScreen(
    state: UiState,
    startHandle: Long,
    preview: suspend (Long) -> ImageBitmap?,
    thumbnail: suspend (Long) -> ImageBitmap?,
    fullImage: suspend (PhotoItem, Int) -> ImageBitmap?,
    onSave: (PhotoItem) -> Unit,
    onDelete: (PhotoItem) -> Unit,
    onBack: () -> Unit,
    share: ShareActions,
) {
    val photos = state.visiblePhotos
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val haze = rememberHazeState()
    val start = photos.indexOfFirst { it.handle == startHandle }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { photos.size }
    val current = photos[pager.currentPage.coerceIn(0, photos.lastIndex)]
    var chrome by remember { mutableStateOf(true) }
    // Hiding the controls hides the status and navigation bars too: just the photo, edge to edge.
    val activity = LocalActivity.current
    DisposableEffect(chrome, activity) {
        val window = activity?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chrome) bars?.show(WindowInsetsCompat.Type.systemBars()) else bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var zoomed by remember { mutableStateOf(false) }
    val motion = Motion.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { photos[it].handle },
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        ) { page ->
            Page(
                photos[page], preview, thumbnail, fullImage,
                onTap = { chrome = !chrome },
                onZoomChange = { if (page == pager.currentPage) zoomed = it },
            )
        }

        AnimatedVisibility(chrome, enter = motion.fadeIn, exit = motion.fadeOut, modifier = Modifier.align(Alignment.TopCenter)) {
            TopBar(current, pager.currentPage + 1, photos.size, haze, onBack)
        }
        AnimatedVisibility(chrome, enter = motion.fadeIn, exit = motion.fadeOut, modifier = Modifier.align(Alignment.BottomCenter)) {
            Details(current, state, haze, onSave, onDelete, share)
        }
    }
}

@Composable
private fun TopBar(photo: PhotoItem, index: Int, total: Int, haze: HazeState, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to photos", onBack, haze = haze, style = ThinGlassStyle)
        Spacer(Modifier.weight(1f))
        Text(
            "$index of $total",
            style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.glass(haze, RoundedCornerShape(50), ThinGlassStyle).padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Page(
    photo: PhotoItem,
    preview: suspend (Long) -> ImageBitmap?,
    thumbnail: suspend (Long) -> ImageBitmap?,
    fullImage: suspend (PhotoItem, Int) -> ImageBitmap?,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
) {
    // Decode the saved copy at about twice the screen's long side, so 2.5× zoom stays sharp.
    val configuration = LocalConfiguration.current
    val maxSide = with(LocalDensity.current) {
        (maxOf(configuration.screenWidthDp, configuration.screenHeightDp).dp.toPx() * 2).toInt()
    }
    // Thumbnail at once, then the best of: the full-resolution copy already on the phone (no Wi-Fi),
    // or the camera's larger preview when there is no usable copy.
    val thumb by produceState<ImageBitmap?>(null, photo.handle) { value = thumbnail(photo.handle) }
    var full by remember(photo.handle) { mutableStateOf<ImageBitmap?>(null) }
    var large by remember(photo.handle) { mutableStateOf<ImageBitmap?>(null) }
    var settled by remember(photo.handle) { mutableStateOf(false) }
    LaunchedEffect(photo.handle, photo.saved) {
        val local = if (photo.saved) fullImage(photo, maxSide) else null
        if (local != null) full = local else large = preview(photo.handle)
        settled = true
    }
    val image = full ?: large ?: thumb
    // Only a slow load shows the spinner (see SPINNER_DELAY_MILLIS).
    var slow by remember(photo.handle) { mutableStateOf(false) }
    LaunchedEffect(photo.handle) {
        delay(SPINNER_DELAY_MILLIS)
        slow = true
    }

    // The gesture snaps both straight to the fingers, so the photo never trails them; a double tap
    // springs both together, so zooming out never drops the photo back to the centre with a jump.
    val motion = Motion.current
    val scope = rememberCoroutineScope()
    val zoom = remember { Animatable(1f) }
    val pan = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val zoomed by remember { derivedStateOf { zoom.value > 1.01f } }
    LaunchedEffect(zoomed) { onZoomChange(zoomed) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(photo.handle, motion) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        val target = if (zoom.targetValue > 1f) 1f else DOUBLE_TAP_ZOOM
                        scope.launch { zoom.animateTo(target, motion.move()) }
                        scope.launch { pan.animateTo(Offset.Zero, motion.move()) }
                    },
                )
            }
            .pointerInput(photo.handle) {
                // Pinch to zoom; one finger pans only once zoomed, so the pager keeps its swipe.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    // Picks up from wherever a double-tap spring is, mid-flight.
                    var scale = zoom.value
                    var offset = pan.value
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.size > 1 || scale > 1f) {
                            val factor = event.calculateZoom()
                            val moved = event.calculatePan()
                            // A finger lifting changes nothing; snapping then would freeze a running spring.
                            if (factor != 1f || moved != Offset.Zero) {
                                scale = (scale * factor).coerceIn(1f, MAX_ZOOM)
                                offset = if (scale > 1f) offset + moved else Offset.Zero
                                val toScale = scale
                                val toOffset = offset
                                scope.launch { zoom.snapTo(toScale) }
                                scope.launch { pan.snapTo(toOffset) }
                            }
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // The photo itself, blurred and enlarged, tints the space around it.
        image?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(60.dp).alpha(0.45f))
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

        // Sized to the photo's own shape, so it can grow out of its square gallery tile and shrink back.
        image?.let {
            Image(
                it,
                photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .aspectRatio(frameRatio(photo, it))
                    .sharedPhoto(photo.handle)
                    .graphicsLayer {
                        scaleX = zoom.value
                        scaleY = zoom.value
                        translationX = pan.value.x
                        translationY = pan.value.y
                    },
            )
        }
        AnimatedVisibility(!settled && slow, enter = motion.fadeIn, exit = motion.fadeOut) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = Palette.Text.copy(alpha = 0.7f), trackColor = Color.Transparent)
        }
    }
}

/**
 * The photo's final shape, so its space is reserved before the better image arrives: the camera's
 * thumbnail is 4:3 while the photo is 3:2, and the thumbnail is simply cropped to it. The camera reports
 * sensor-side dimensions, but the saved copy decodes upright, so the ratio follows the bitmap's
 * orientation: a portrait shot is never cropped to landscape.
 */
private fun frameRatio(photo: PhotoItem, bitmap: ImageBitmap): Float {
    val shown = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
    if (photo.width <= 0 || photo.height <= 0) return shown
    val reported = photo.width.toFloat() / photo.height
    return if ((reported >= 1f) == (shown >= 1f)) reported else 1f / reported
}

private enum class SaveState { Unsaved, Saving, Saved }

@Composable
private fun Details(photo: PhotoItem, state: UiState, haze: HazeState, onSave: (PhotoItem) -> Unit, onDelete: (PhotoItem) -> Unit, share: ShareActions) {
    val progress = photo.handles.mapNotNull { state.saving[it] }.minOrNull()
    val queued = photo.handles.any { it in state.queued }
    val saveState = when {
        photo.allSaved -> SaveState.Saved
        progress != null || queued -> SaveState.Saving
        else -> SaveState.Unsaved
    }
    val motion = Motion.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(12.dp)
            .glass(haze, RoundedCornerShape(Dimens.RadiusLg))
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    photo.captured?.format(takenDay) ?: "Unknown date",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val facts = buildList {
                    photo.captured?.let { add(it.format(takenTime)) }
                    add(
                        when {
                            photo.raw != null -> "RAW + JPEG"
                            photo.kind == MediaKind.Raw -> "RAW"
                            photo.kind == MediaKind.Video -> "Video"
                            else -> "JPEG"
                        },
                    )
                    if (photo.width > 0 && photo.height > 0) add("%.1f MP".format(photo.width * photo.height / 1e6))
                    add(sizeLabel(photo.size + (photo.raw?.size ?: 0)))
                }
                Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                Text(photo.name + (photo.raw?.let { " + " + it.name.substringAfterLast('.') } ?: ""), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // One slot, three states (save, saving, saved): each fades through to the next in place.
            AnimatedContent(saveState, Modifier.weight(1f).height(Dimens.Touch), transitionSpec = { motion.swap() }, label = "save") { shown ->
                when (shown) {
                    SaveState.Saved -> Row(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(Palette.Success.copy(alpha = 0.14f)).padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Success, modifier = Modifier.size(Dimens.IconSm))
                        Spacer(Modifier.width(8.dp))
                        Text("On your phone", style = MaterialTheme.typography.labelLarge, color = Palette.Success)
                    }
                    SaveState.Saving -> {
                        // Fading out once saved, it holds its last progress rather than dropping back to "Waiting".
                        val last = rememberLast(progress)
                        val percent = progress ?: if (transition.targetState == EnterExitState.PostExit) last else null
                        val bar by animateFloatAsState(percent ?: 0f, motion.move(), label = "save-progress")
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                            Text(
                                if (percent != null) "Saving… ${(percent * 100).toInt()}%" else "Waiting to save",
                                style = MaterialTheme.typography.labelLarge.tabular(),
                            )
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { bar },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                                color = Palette.Accent,
                                trackColor = Palette.AccentSoft,
                                drawStopIndicator = {},
                            )
                        }
                    }
                    SaveState.Unsaved -> AccentButton(
                        if (photo.raw != null) "Save RAW + JPEG" else "Save full size",
                        { onSave(photo) },
                        Modifier.fillMaxWidth(),
                        icon = Icons.Rounded.Download,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            ShareButton(listOf(photo), state, share)
            if (Capability.Delete in state.capabilities) {
                Spacer(Modifier.width(10.dp))
                RoundIconButton(Icons.Rounded.DeleteOutline, "Delete from card", { confirmDelete = true }, tint = Palette.Danger)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.Raised,
            title = { Text("Delete from the card?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("${photo.name}${if (photo.raw != null) " and its RAW file" else ""} will be removed from the camera's memory card. A copy saved on this phone stays.", color = Palette.TextSecondary) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(photo) }) { Text("Delete", color = Palette.Danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep", color = Palette.Text) } },
        )
    }
}
