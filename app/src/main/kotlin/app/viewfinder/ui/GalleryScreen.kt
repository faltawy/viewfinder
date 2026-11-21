package app.viewfinder.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Animatable
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Battery2Bar
import androidx.compose.material.icons.rounded.Battery4Bar
import androidx.compose.material.icons.rounded.Battery6Bar
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.viewfinder.Connection
import app.viewfinder.Filter
import app.viewfinder.MediaKind
import app.viewfinder.PhotoItem
import app.viewfinder.UiState
import app.viewfinder.ptp.camera.Capability
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

class GalleryActions(
    val thumbnail: suspend (Long) -> ImageBitmap?,
    val open: (PhotoItem) -> Unit,
    val toggleSelection: (Long) -> Unit,
    val selectAll: () -> Unit,
    val clearSelection: () -> Unit,
    val saveSelection: () -> Unit,
    val deleteSelection: () -> Unit,
    val saveAllUnsaved: () -> Unit,
    /** One tap on Share in the selection bar: the selection with the saved options, then the selection clears. */
    val shareSelection: () -> Unit,
    val share: ShareActions,
    val setFilter: (Filter) -> Unit,
    val setAutoSave: (Boolean) -> Unit,
    val refresh: () -> Unit,
    val openRemote: () -> Unit,
    val openSettings: () -> Unit,
    val showLog: () -> Unit,
    val openLearn: () -> Unit,
    val disconnect: () -> Unit,
    val dismissMessage: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(state: UiState, grid: LazyGridState, reveal: TileReveal, actions: GalleryActions) {
    val haze = rememberHazeState()
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(140.dp) }
    val refresh = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.refresh,
            state = refresh,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = refresh,
                    isRefreshing = state.refreshing,
                    containerColor = Palette.Raised,
                    color = Palette.Accent,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = headerHeight),
                )
            },
        ) {
            val photos = state.visiblePhotos
            val content = when {
                photos.isEmpty() && state.listing != null -> GalleryContent.Loading
                photos.isEmpty() -> GalleryContent.Empty
                else -> GalleryContent.Grid
            }
            // What's leaving keeps what it showed: the grid its photos, the empty note its filter.
            val gridPhotos = rememberLast(photos.ifEmpty { null }) ?: photos
            val emptyFilter = rememberLast(state.filter.takeIf { content == GalleryContent.Empty }) ?: state.filter
            val motion = Motion.current
            // Full screen, so it crossfades without resizing.
            AnimatedContent(
                content,
                transitionSpec = { (motion.fadeIn togetherWith motion.fadeOut).using(null) },
                modifier = Modifier.fillMaxSize(),
                label = "gallery-content",
            ) { c ->
                when (c) {
                    GalleryContent.Loading -> Skeleton(headerHeight)
                    GalleryContent.Empty -> Empty(emptyFilter, headerHeight)
                    GalleryContent.Grid -> Grid(state, gridPhotos, grid, reveal, headerHeight, actions)
                }
            }
        }

        Header(
            state, haze, actions,
            Modifier.align(Alignment.TopCenter).onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
        )

        // The grid sinks softly into the bottom edge, so text never peeks out beside the floating bar.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Background.copy(alpha = 0.9f)))),
        )
        BottomBar(state, haze, actions, Modifier.align(Alignment.BottomCenter))

        Toast(state.message, actions.dismissMessage, haze, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Header(state: UiState, haze: HazeState, actions: GalleryActions, modifier: Modifier) {
    val connected = state.connection as? Connection.Connected
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .hazeEffect(haze, GlassStyle)
            .drawBehind { drawLine(Palette.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 1f) }
            .statusBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            HeaderMark(state)
            Spacer(Modifier.width(14.dp))
            // The status line runs on under the buttons, so battery, card and progress always fit on one line.
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(connected?.model ?: "Camera", style = MaterialTheme.typography.headlineSmall, maxLines = 1, modifier = Modifier.weight(1f))
                    if (Capability.LiveView in state.capabilities || Capability.RemoteCapture in state.capabilities) {
                        RoundIconButton(Icons.Rounded.PhotoCamera, "Remote shooting", actions.openRemote, tint = Palette.Accent)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (Capability.Settings in state.capabilities) {
                        RoundIconButton(Icons.Rounded.Tune, "Camera settings", actions.openSettings)
                        Spacer(Modifier.width(6.dp))
                    }
                    Box {
                        RoundIconButton(Icons.Rounded.MoreVert, "More", { menu = true })
                        DropdownMenu(
                            expanded = menu,
                            onDismissRequest = { menu = false },
                            containerColor = Palette.Raised,
                            shape = RoundedCornerShape(Dimens.Radius),
                            border = BorderStroke(1.dp, Palette.Hairline),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                leadingIcon = { Icon(Icons.Rounded.SelectAll, null) },
                                onClick = { menu = false; actions.selectAll() },
                            )
                            DropdownMenuItem(
                                text = { Text("Learn photography") },
                                leadingIcon = { Icon(Icons.Rounded.School, null) },
                                onClick = { menu = false; actions.openLearn() },
                            )
                            DropdownMenuItem(
                                text = { Text("Connection log") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ReceiptLong, null) },
                                onClick = { menu = false; actions.showLog() },
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = { Icon(Icons.Rounded.LinkOff, null) },
                                onClick = { menu = false; actions.disconnect() },
                            )
                        }
                    }
                }
                StatusLine(state)
            }
        }
        Filters(state, actions)
        ReadingProgress(state.listing)
    }
}

/**
 * The card-reading bar under the filters. Its 2dp are always reserved, so the header (and the grid's top
 * padding measured from it) never shifts when a read starts or ends; the bar only fades in and out.
 */
@Composable
private fun ReadingProgress(listing: Pair<Int, Int>?) {
    val motion = Motion.current
    val on = listing != null
    val visible by animateFloatAsState(if (on) 1f else 0f, if (on) motion.enter() else motion.exit(), label = "reading")
    // Fading out, the bar keeps the last read's progress instead of dropping to empty.
    val fraction = rememberLast(listing)?.let { (read, total) -> if (total == 0) 0f else read.toFloat() / total } ?: 0f
    val progress = remember { Animatable(fraction) }
    LaunchedEffect(fraction) {
        // A read only moves forward, so going back means a new read: it starts at its own beginning
        // instead of sweeping back from where the last one ended.
        if (fraction < progress.value) progress.snapTo(fraction) else progress.animateTo(fraction, motion.move())
    }
    Box(Modifier.fillMaxWidth().height(2.dp).graphicsLayer { alpha = visible }) {
        LinearProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.fillMaxSize(),
            color = Palette.Accent,
            trackColor = Color.Transparent,
            drawStopIndicator = {},
        )
    }
}

/**
 * The mark beside the camera's name: pulsing while the card is read or files save, locking in when that
 * finishes, and pressing in when a new photo arrives from the camera.
 */
@Composable
private fun HeaderMark(state: UiState) {
    val working = state.listing != null || state.refreshing || state.saving.isNotEmpty()
    var arrivals by remember { mutableIntStateOf(0) }
    var known by remember { mutableIntStateOf(state.photos.size) }
    LaunchedEffect(state.photos.size, state.listing == null) {
        if (state.listing == null && state.photos.size > known) arrivals++
        known = state.photos.size
    }
    ViewfinderMark(if (working) MarkState.Working else MarkState.Connected, Modifier.size(40.dp).sharedMark(), shots = arrivals)
}

@Composable
private fun StatusLine(state: UiState) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        state.battery?.let { level ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(batteryIcon(level), "Battery", tint = if (level <= 15) Palette.Danger else Palette.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text("$level%", style = MaterialTheme.typography.bodySmall.tabular(), color = Palette.TextSecondary)
            }
        }
        state.shotsLeft?.let { shots ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(Icons.Rounded.SdCard, "Card", tint = Palette.TextSecondary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text("%,d left".format(shots), style = MaterialTheme.typography.bodySmall.tabular(), color = Palette.TextSecondary)
            }
        }
        val count = state.listing?.second ?: state.photos.size
        Text(
            when {
                state.listing == null -> "%,d files".format(count)
                count == 0 -> "Reading…"
                else -> "Reading ${state.listing.first}/$count"
            },
            style = MaterialTheme.typography.bodySmall.tabular(),
            color = Palette.TextSecondary,
            // One line always: wrapping would change the header's height while the card is read.
            maxLines = 1,
            softWrap = false,
        )
    }
}


@Composable
private fun Filters(state: UiState, actions: GalleryActions) {
    val motion = Motion.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SegmentedControl(
            options = Filter.entries.map { it.label },
            selected = state.filter.ordinal,
            onSelect = { actions.setFilter(Filter.entries[it]) },
            modifier = Modifier.weight(1f),
            segmentPadding = 4.dp,
        )
        Spacer(Modifier.width(10.dp))
        val on = state.autoSave
        val interaction = remember { MutableInteractionSource() }
        val tint by animateColorAsState(if (on) Palette.Accent else Palette.TextSecondary, motion.enter(), label = "auto-save")
        val fill by animateColorAsState(if (on) Palette.AccentSoft else Palette.Text.copy(alpha = 0.07f), motion.enter(), label = "auto-save-fill")
        val edge by animateColorAsState(if (on) Palette.Accent.copy(alpha = 0.6f) else Color.Transparent, motion.enter(), label = "auto-save-edge")
        Row(
            Modifier
                .pressScale(interaction)
                .clip(RoundedCornerShape(50))
                .background(fill)
                .border(1.dp, edge, RoundedCornerShape(50))
                .semantics { this.selected = on }
                .clickable(interaction, ripple(), role = Role.Switch, onClickLabel = "Auto-save new shots") { actions.setAutoSave(!on) }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Bolt, null, tint = tint, modifier = Modifier.size(Dimens.IconSm))
            Spacer(Modifier.width(6.dp))
            Text("Auto-save", style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
        }
    }
}

private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
private val dayYearFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault())

private fun dayLabel(day: LocalDate?): String {
    val today = LocalDate.now()
    return when {
        day == null -> "Unknown date"
        day == today -> "Today"
        day == today.minusDays(1) -> "Yesterday"
        day.year == today.year -> day.format(dayFormat)
        else -> day.format(dayYearFormat)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Grid(state: UiState, photos: List<PhotoItem>, grid: LazyGridState, reveal: TileReveal, top: androidx.compose.ui.unit.Dp, actions: GalleryActions) {
    val haptics = LocalHapticFeedback.current
    val groups = remember(photos) { photos.groupBy { it.captured?.toLocalDate() } }
    val motion = Motion.current
    // Once the card is read, every tile so far counts as seen: scrolling back to them never re-animates,
    // only shots arriving later do.
    LaunchedEffect(state.listing == null, photos.size) { if (state.listing == null) reveal.seen += photos.map { it.handle } }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(top = top, bottom = 140.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        groups.forEach { (day, items) ->
            item(key = "day-$day", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.Bottom) {
                    Text(dayLabel(day), style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Text("${items.size}", style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            items(items, key = { it.handle }, contentType = { "photo" }) { photo ->
                val selected = photo.handle in state.selection
                val interaction = remember { MutableInteractionSource() }
                Tile(
                    photo = photo,
                    selected = selected,
                    selecting = state.selection.isNotEmpty(),
                    progress = photo.handles.mapNotNull { state.saving[it] }.minOrNull(),
                    queued = photo.handles.any { it in state.queued },
                    thumbnail = actions.thumbnail,
                    revealDelay = remember(photo.handle) { reveal.delayFor(photo.handle, motion) },
                    modifier = Modifier
                        .pressScale(interaction, pressed = 0.97f)
                        .semantics { this.selected = selected }
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = ripple(),
                            onClickLabel = "Open",
                            onLongClickLabel = "Select",
                            onClick = { if (state.selection.isNotEmpty()) actions.toggleSelection(photo.handle) else actions.open(photo) },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                actions.toggleSelection(photo.handle)
                            },
                        ),
                )
            }
        }
    }
}

/**
 * Tiles appearing for the first time arrive as a wave: each batch composed together is staggered.
 * A plain holder, not state: it only decides delays when a tile is first composed. It lives above the
 * gallery (one per connection), so coming back from the viewer doesn't replay the wave.
 */
class TileReveal {
    val seen = mutableSetOf<Long>()
    private var batchStart = 0L
    private var batchIndex = 0

    /** Null when the tile was already shown once; otherwise how long it waits before appearing. */
    fun delayFor(handle: Long, motion: Motion): Int? {
        if (!seen.add(handle)) return null
        val now = SystemClock.uptimeMillis()
        if (now - batchStart > BATCH_WINDOW_MS) {
            batchStart = now
            batchIndex = 0
        }
        return motion.staggerMillis(batchIndex++)
    }

    private companion object {
        const val BATCH_WINDOW_MS = 150
    }
}

@Composable
private fun Tile(
    photo: PhotoItem,
    selected: Boolean,
    selecting: Boolean,
    progress: Float?,
    queued: Boolean,
    thumbnail: suspend (Long) -> ImageBitmap?,
    revealDelay: Int?,
    modifier: Modifier,
) {
    val motion = Motion.current
    val image by produceState<ImageBitmap?>(null, photo.handle) { value = thumbnail(photo.handle) }
    val shown by animateFloatAsState(if (image != null) 1f else 0f, motion.enter(), label = "fade")
    // 0 to 1 as the tile is selected: one value drives both the inset and the rounding, so they move together.
    val picked by animateFloatAsState(if (selected) 1f else 0f, motion.move(), label = "picked")
    val arrival = remember { Animatable(if (revealDelay == null) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (revealDelay != null) {
            delay(revealDelay.toLong())
            arrival.animateTo(1f, motion.move())
        }
    }
    Box(
        modifier
            .graphicsLayer {
                alpha = arrival.value
                translationY = (1f - arrival.value) * 16.dp.toPx()
            }
            .aspectRatio(1f)
            .background(Palette.Surface)
            .semantics { contentDescription = "${photo.name}${if (photo.allSaved) ", saved" else ""}" },
    ) {
        image?.let {
            Image(
                it,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .sharedPhoto(photo.handle)
                    // A transform, not padding: selecting never re-lays out the tile, only redraws it.
                    .graphicsLayer {
                        val scale = 1f - SELECTED_INSET * picked
                        scaleX = scale
                        scaleY = scale
                        // Divided by the scale so the corner reads 10dp on screen once the image has shrunk.
                        shape = RoundedCornerShape(10.dp.toPx() * picked / scale)
                        clip = true
                        alpha = shown
                    },
            )
        }

        Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                photo.raw != null -> Tag("RAW+JPG")
                photo.kind == MediaKind.Raw -> Tag("RAW")
                photo.kind == MediaKind.Video -> Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(3.dp)) {
                    Icon(Icons.Rounded.PlayArrow, "Video", tint = Color.White, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Save state in one fixed slot: queued, saving and saved scale through each other in place.
        val badge = when {
            progress != null -> Badge.Saving
            queued -> Badge.Queued
            photo.allSaved && !selecting -> Badge.Saved
            else -> Badge.None
        }
        // The ring fading out keeps its last progress rather than emptying.
        val lastProgress = rememberLast(progress) ?: 0f
        AnimatedContent(
            badge,
            transitionSpec = { motion.morph() },
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(24.dp),
            label = "badge",
        ) { b ->
            when (b) {
                Badge.Saving -> CircularProgressIndicator(
                    progress = { progress ?: lastProgress },
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(4.dp),
                    strokeWidth = 2.5.dp,
                    color = Palette.Accent,
                    trackColor = Color.White.copy(alpha = 0.2f),
                )
                Badge.Queued -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.75f)))
                Badge.Saved -> Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, tint = Palette.Success, modifier = Modifier.size(14.dp))
                }
                Badge.None -> {}
            }
        }

        AnimatedVisibility(
            selecting,
            enter = motion.fadeIn + scaleIn(motion.move(), initialScale = 0.85f),
            exit = motion.fadeOut,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            AnimatedContent(selected, transitionSpec = { motion.morph() }, contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp), label = "pick") { on ->
                if (on) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = Palette.Accent, modifier = Modifier.size(24.dp).background(Palette.OnAccent, CircleShape))
                } else {
                    Box(Modifier.size(22.dp).border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape).background(Color.Black.copy(alpha = 0.2f), CircleShape))
                }
            }
        }
    }
}

private enum class Badge { None, Queued, Saving, Saved }

/** How far a selected tile's image shrinks into its cell, as a fraction of the cell. */
private const val SELECTED_INSET = 0.1f

private enum class GalleryContent { Loading, Empty, Grid }

@Composable
private fun Skeleton(top: androidx.compose.ui.unit.Dp) {
    val pulse = rememberPulse(0.35f, 0.7f, label = "skeleton")
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(top = top + 56.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        userScrollEnabled = false,
    ) {
        items(18) { Box(Modifier.aspectRatio(1f).background(Palette.Surface.copy(alpha = pulse + 0.3f))) }
    }
}

private enum class BarMode { Sharing, Selecting, Saving, Unsaved }

@Composable
private fun BottomBar(state: UiState, haze: HazeState, actions: GalleryActions, modifier: Modifier) {
    val show = state.selection.isNotEmpty() || state.busyCount > 0 || (state.unsavedCount > 0 && state.listing == null)
    // While the bar sinks away it keeps drawing the state that showed it, not the emptied one that hid it:
    // otherwise it would fall through to "Save all" or read "0 selected" on its way out.
    val shown = rememberLast(state.takeIf { show }) ?: state
    val busy = shown.busyCount
    val selected = remember(shown.selection, shown.visiblePhotos) { shown.visiblePhotos.filter { it.handle in shown.selection } }
    val selectionShare = remember(actions) { ShareActions(share = { actions.shareSelection() }, openPanel = actions.share.openPanel, cancel = actions.share.cancel) }
    var confirmDelete by remember { mutableStateOf(false) }
    val mode = when {
        shown.selection.isNotEmpty() && shown.sharing != null -> BarMode.Sharing
        shown.selection.isNotEmpty() -> BarMode.Selecting
        busy > 0 -> BarMode.Saving
        else -> BarMode.Unsaved
    }

    AnimatedVisibility(
        visible = show,
        enter = Motion.current.riseIn,
        exit = Motion.current.sinkOut,
        modifier = modifier.navigationBarsPadding().padding(16.dp),
    ) {
        // One pill whose content swaps in place; it resizes on a spring instead of jumping, both between
        // modes and when a count within one mode changes its width.
        val motion = Motion.current
        Box(Modifier.glass(haze, RoundedCornerShape(50))) {
          AnimatedContent(mode, transitionSpec = { motion.swap() }, contentAlignment = Alignment.Center, label = "bottom-bar") { m ->
           Row(Modifier.animateContentSize(motion.move()).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Within a swap the leaving mode keeps its last numbers too, so it never fades out reading zero.
            when (m) {
                BarMode.Sharing -> {
                    ShareButton(selected, shown, selectionShare)
                    val s = rememberLast(shown.sharing)
                    Text(
                        if (s == null || s.total == 1) "Preparing 1 file" else "Preparing ${s.done} of ${s.total}",
                        style = MaterialTheme.typography.titleSmall.tabular(),
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
                BarMode.Selecting -> {
                    RoundIconButton(Icons.Rounded.Close, "Cancel selection", actions.clearSelection, modifier = Modifier.padding(end = 2.dp))
                    Text("${shown.selection.size} selected", style = MaterialTheme.typography.titleSmall.tabular(), modifier = Modifier.padding(horizontal = 10.dp))
                    if (Capability.Delete in shown.capabilities) {
                        RoundIconButton(Icons.Rounded.DeleteOutline, "Delete from card", { confirmDelete = true }, tint = Palette.Danger)
                        Spacer(Modifier.width(6.dp))
                    }
                    ShareButton(selected, state, selectionShare)
                    Spacer(Modifier.width(6.dp))
                    AccentButton("Save", actions.saveSelection, icon = Icons.Rounded.Download, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp))
                }
                BarMode.Saving -> {
                    val files = rememberLast(busy.takeIf { it > 0 }) ?: busy
                    val done = rememberLast(if (busy == 0) null else (shown.saving.values.sum() / busy).coerceIn(0f, 1f)) ?: 0f
                    CircularProgressIndicator(
                        progress = { done },
                        modifier = Modifier.padding(start = 10.dp).size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = Palette.Accent,
                        trackColor = Palette.AccentSoft,
                    )
                    Text(
                        if (files == 1) "Saving 1 file" else "Saving $files files",
                        style = MaterialTheme.typography.titleSmall.tabular(),
                        modifier = Modifier.padding(start = 12.dp, end = 18.dp, top = 12.dp, bottom = 12.dp),
                    )
                }
                BarMode.Unsaved -> {
                    val count = rememberLast(shown.unsavedCount.takeIf { it > 0 }) ?: shown.unsavedCount
                    Text(
                        if (count == 1) "1 photo not on your phone" else "$count photos not on your phone",
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 14.dp, end = 12.dp),
                    )
                    AccentButton("Save all", actions.saveAllUnsaved, icon = Icons.Rounded.Download, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp))
                }
            }
           }
          }
        }
    }

    if (confirmDelete) {
        val n = state.selection.size
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Palette.Raised,
            title = { Text(if (n == 1) "Delete this photo from the card?" else "Delete $n photos from the card?", style = MaterialTheme.typography.titleLarge) },
            text = { Text("They're removed from the camera's memory card and can't be recovered from there. Copies already saved on this phone stay.", color = Palette.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; actions.deleteSelection() }) { Text("Delete", color = Palette.Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep", color = Palette.Text) } },
        )
    }
}

/** A glass message above the bottom bar that fades away after a few seconds. */
@Composable
fun Toast(message: String?, dismiss: () -> Unit, haze: HazeState, modifier: Modifier) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(3_500)
            dismiss()
        }
    }
    val motion = Motion.current
    // Fading out, the pill keeps its last message instead of shrinking to an empty blob.
    val shown = rememberLast(message)
    val interaction = remember { MutableInteractionSource() }
    AnimatedVisibility(
        visible = message != null,
        enter = motion.appear,
        exit = motion.disappear,
        modifier = modifier.navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 96.dp),
    ) {
        // Wide as a row, so it gives less than a button when pressed.
        Box(
            Modifier
                .pressScale(interaction, pressed = 0.98f)
                .glass(haze, RoundedCornerShape(Dimens.Radius))
                .clickable(interaction, ripple(), onClickLabel = "Dismiss", onClick = dismiss)
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // A new message fades through the old one while the pill resizes on a spring.
            AnimatedContent(shown, transitionSpec = { motion.swap() }, label = "toast") { text ->
                Text(text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Empty(filter: Filter, top: androidx.compose.ui.unit.Dp) {
    Box(Modifier.fillMaxSize().padding(top = top).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (filter == Filter.All) "The card is empty" else "No ${filter.label} files", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Take a picture and it shows up here.", color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
