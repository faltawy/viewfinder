package app.viewfinder.ui

import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.material3.ripple
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.animateColorAsState
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowDown
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.viewfinder.LiveState
import app.viewfinder.UiState
import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.camera.CameraProperty
import app.viewfinder.ptp.camera.Capability
import app.viewfinder.ptp.camera.StandardProps
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlin.math.roundToInt

class RemoteActions(
    val back: () -> Unit,
    val retry: () -> Unit,
    val focusAt: (Float, Float) -> Unit,
    val autofocus: () -> Unit,
    val shoot: () -> Unit,
    val manualFocus: (Int) -> Unit,
    val toggleMovie: () -> Unit,
    val toggleBulb: () -> Unit,
    val setProperty: (Int, PropValue) -> Unit,
    val thumbnail: suspend (Long) -> ImageBitmap?,
    val openPhoto: (Long) -> Unit,
    val share: ShareActions,
    val dismissMessage: () -> Unit,
)

/** The settings on the control deck, one per role, in deck order. */
private fun deck(properties: Map<Int, CameraProperty>): List<CameraProperty> =
    properties.values
        .filter { it.meta.deckOrder != null }
        .groupBy { it.meta.deckOrder!! }
        .toSortedMap()
        .map { (_, same) -> same.sortedWith(compareByDescending<CameraProperty> { it.editable }.thenByDescending { it.code >= 0xD000 }).first() }

@Composable
fun RemoteScreen(state: UiState, actions: RemoteActions) {
    val haze = rememberHazeState()
    val live = state.live
    val canStream = Capability.LiveView in state.capabilities
    val deckProps = remember(state.properties) { deck(state.properties) }
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    val flash = remember { Animatable(0f) }
    val blink = remember { Animatable(1f) }
    val motion = Motion.current
    val haptics = LocalHapticFeedback.current

    // A shot landing: a quick white flash and the frame blinking like a mirror slap, with a confirming tick.
    LaunchedEffect(live.shots) {
        if (live.shots == 0) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (motion.reduced) return@LaunchedEffect
        launch {
            flash.snapTo(0.7f)
            flash.animateTo(0f, motion.flash())
        }
        blink.snapTo(0.96f)
        blink.animateTo(1f, motion.bounce())
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Viewfinder(
            live, canStream, state.capabilities, actions,
            Modifier.fillMaxSize().graphicsLayer { scaleX = blink.value; scaleY = blink.value }.hazeSource(haze),
        )

        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))

        TopBar(state, haze, actions, Modifier.align(Alignment.TopCenter))

        // The focus rail steps aside while a value picker is open, so the two never collide.
        AnimatedVisibility(
            Capability.ManualFocus in state.capabilities && live.running && picking == null,
            enter = motion.fadeIn,
            exit = motion.fadeOut,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        ) {
            FocusRail(state, haze, actions, Modifier)
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp)) {
            val open = picking?.let { code -> deckProps.any { it.code == code } } == true
            // The last code picked keeps the picker drawn while it collapses; switching chips fades one
            // picker into the next. The property is looked up live by code, so its values stay current.
            val shownCode = rememberLast(picking)
            AnimatedVisibility(open, enter = motion.expand, exit = motion.collapse) {
                AnimatedContent(shownCode, Modifier.fillMaxWidth(), transitionSpec = { motion.swap() }, label = "picker") { code ->
                    deckProps.firstOrNull { it.code == code }?.let { prop ->
                        Picker(prop, haze) { value ->
                            actions.setProperty(prop.code, value)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Deck(deckProps, picking, haze, state.loadingProperties) { code -> picking = if (picking == code) null else code }
            Spacer(Modifier.height(10.dp))
            Controls(state, haze, actions)
        }

        Toast(state.message, actions.dismissMessage, haze, Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Viewfinder(
    live: LiveState,
    canStream: Boolean,
    capabilities: Set<Capability>,
    actions: RemoteActions,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val frame = live.frame
        if (frame != null) {
            // Where the frame actually sits inside the screen, for mapping taps to the sensor.
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = minOf(boxW / frame.width, boxH / frame.height)
            val drawnW = frame.width * scale
            val drawnH = frame.height * scale
            val left = (boxW - drawnW) / 2
            val top = (boxH - drawnH) / 2
            val canFocus = Capability.TouchFocus in capabilities || Capability.Autofocus in capabilities

            // The frame itself, blurred, fills the letterbox bars with its own colour.
            Image(frame, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(48.dp).alpha(0.5f))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            Image(
                frame,
                "Live view",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(frame.width, frame.height, canFocus) {
                        if (canFocus) {
                            detectTapGestures { p ->
                                val x = (p.x - left) / drawnW
                                val y = (p.y - top) / drawnH
                                if (x in 0f..1f && y in 0f..1f) actions.focusAt(x, y)
                            }
                        }
                    },
            )
            live.focusPoint?.let { (fx, fy) ->
                // Its own top-left anchored layer: the parent centres children, which would shift the offset.
                Box(Modifier.fillMaxSize()) { FocusReticle(live, Offset(left + fx * drawnW, top + fy * drawnH)) }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                when {
                    live.error != null -> {
                        Text("Live view stopped", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(live.error.removePrefix("Live view stopped: ").replaceFirstChar { it.uppercase() }, color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(16.dp))
                        QuietButton("Try again", actions.retry)
                    }
                    !canStream -> {
                        Text("Remote shutter", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text("This camera doesn't stream live view over Wi-Fi, but you can still shoot and change settings.", color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Palette.Accent, trackColor = Palette.AccentSoft, strokeWidth = 3.dp)
                        Spacer(Modifier.height(14.dp))
                        Text("Starting live view", color = Palette.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FocusReticle(live: LiveState, center: Offset) {
    val size = 72.dp
    val px = with(LocalDensity.current) { size.toPx() }
    val grow = remember(live.focusTap) { Animatable(1.5f) }
    val motion = Motion.current
    LaunchedEffect(live.focusTap) { grow.animateTo(1f, motion.bounce()) }
    val color = when (live.focused) {
        true -> Palette.Success
        false -> Palette.Danger
        null -> Palette.Accent
    }
    Canvas(
        Modifier
            .offset { IntOffset((center.x - px / 2).roundToInt(), (center.y - px / 2).roundToInt()) }
            .size(size)
            .scale(grow.value)
            .semantics { contentDescription = when (live.focused) { true -> "In focus"; false -> "Couldn't focus"; null -> "Focusing" } },
    ) {
        drawRoundRect(color, cornerRadius = CornerRadius(14.dp.toPx()), style = Stroke(2.dp.toPx()), size = Size(this.size.width, this.size.height))
        drawCircle(color, radius = 3.dp.toPx())
    }
}

@Composable
private fun TopBar(state: UiState, haze: HazeState, actions: RemoteActions, modifier: Modifier) {
    val mode = state.properties[StandardProps.ExposureProgramMode]
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back to photos", actions.back, haze = haze, style = ThinGlassStyle)
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.glass(haze, RoundedCornerShape(50), ThinGlassStyle).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val live = state.live
            ViewfinderMark(
                if (live.shooting || live.bulbSince != null || live.recording) MarkState.Working else MarkState.Connected,
                Modifier.size(22.dp).sharedMark(),
                shots = live.shots,
            )
            mode?.let {
                Text(it.display, style = MaterialTheme.typography.titleSmall, color = Palette.Accent, modifier = Modifier.semantics { contentDescription = "Mode ${it.display}" })
            }
            state.battery?.let { level ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Battery $level%" }) {
                    Icon(batteryIcon(level), null, tint = if (level <= 15) Palette.Danger else Palette.TextSecondary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("$level%", style = MaterialTheme.typography.labelLarge.tabular())
                }
            }
            state.shotsLeft?.let { shots ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "$shots shots left" }) {
                    Icon(Icons.Rounded.SdCard, null, tint = Palette.TextSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("%,d".format(shots), style = MaterialTheme.typography.labelLarge.tabular())
                }
            }
        }
    }
}

@Composable
private fun Deck(props: List<CameraProperty>, picking: Int?, haze: HazeState, loading: Boolean, onPick: (Int) -> Unit) {
    val scroll = rememberScrollState()
    val motion = Motion.current
    Row(
        Modifier
            .fillMaxWidth()
            .glass(haze, RoundedCornerShape(Dimens.Radius), ThinGlassStyle)
            .fadingEdges(scroll)
            .horizontalScroll(scroll)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (props.isEmpty()) {
            Text(
                if (loading) "Reading camera settings…" else "No settings available",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(14.dp),
            )
        }
        props.forEach { prop ->
            val active = picking == prop.code
            // A chip being edited scrolls fully into view, even if it was half hidden at the edge.
            val reveal = remember { BringIntoViewRequester() }
            LaunchedEffect(active) { if (active) reveal.bringIntoView() }
            val interaction = remember { MutableInteractionSource() }
            val fill by animateColorAsState(if (active) Palette.AccentSoft else Color.Transparent, motion.enter(), label = "deck-chip")
            val ink by animateColorAsState(if (active) Palette.Accent else Palette.TextSecondary, motion.enter(), label = "deck-chip-label")
            Column(
                Modifier
                    .bringIntoViewRequester(reveal)
                    .pressScale(interaction)
                    // A value that changes width ("1/60" to "1/4000") resizes its chip on a spring, so the strip never jumps.
                    .animateContentSize(motion.move())
                    .clip(RoundedCornerShape(12.dp))
                    .background(fill)
                    .semantics {
                        selected = active
                        stateDescription = prop.display
                    }
                    .clickable(interaction, ripple(), enabled = prop.editable && prop.desc.choices.isNotEmpty(), role = Role.Button, onClickLabel = "Change ${prop.name}") { onPick(prop.code) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .alpha(if (prop.editable) 1f else 0.6f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shortName(prop).uppercase(), style = MaterialTheme.typography.labelSmall, color = ink)
                    if (!prop.editable) {
                        Spacer(Modifier.width(3.dp))
                        Icon(Icons.Rounded.Lock, "Locked by the camera's mode", tint = Palette.TextSecondary, modifier = Modifier.size(10.dp))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(prop.display, style = MaterialTheme.typography.titleMedium.tabular(), maxLines = 1)
            }
        }
    }
}

private fun shortName(prop: CameraProperty): String = when (prop.meta.deckOrder) {
    0 -> "Mode"
    1 -> "Shutter"
    2 -> "Aperture"
    3 -> "ISO"
    4 -> "Exp. comp"
    5 -> "WB"
    6 -> "Focus"
    7 -> "Metering"
    8 -> "Quality"
    9 -> "Release"
    else -> prop.name
}

@Composable
private fun Picker(prop: CameraProperty, haze: HazeState, onChoose: (PropValue) -> Unit) {
    val choices = prop.desc.choices
    val selected = choices.indexOf(prop.desc.current).coerceAtLeast(0)
    val list = rememberLazyListState()
    val motion = Motion.current
    // Opens with the current value centred, and follows the choice when it changes.
    LaunchedEffect(prop.code) {
        list.scrollToItem(selected)
        list.centreOffset(selected)?.let { list.scrollBy(it) }
    }
    LaunchedEffect(selected) { list.centreOffset(selected)?.let { list.animateScrollBy(it, motion.move()) } }
    val haptics = LocalHapticFeedback.current
    Column(
        Modifier
            .fillMaxWidth()
            .glass(haze, RoundedCornerShape(Dimens.Radius), ThinGlassStyle)
            .padding(vertical = 10.dp),
    ) {
        Text(prop.name, style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(
            state = list,
            contentPadding = PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fadingEdges(list),
        ) {
            itemsIndexed(choices) { i, value ->
                val on = i == selected && value == prop.desc.current
                val interaction = remember { MutableInteractionSource() }
                val fill by animateColorAsState(if (on) Palette.Accent else Palette.Text.copy(alpha = 0.08f), motion.enter(), label = "chip")
                val ink by animateColorAsState(if (on) Palette.OnAccent else Palette.Text, motion.enter(), label = "chip-text")
                Text(
                    prop.format(value),
                    style = MaterialTheme.typography.titleSmall,
                    color = ink,
                    modifier = Modifier
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(50))
                        .background(fill)
                        .semantics { this.selected = on }
                        .clickable(interaction, ripple(), role = Role.Button) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onChoose(value)
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** How far to scroll so item [index] sits in the middle of the row; null until it has been laid out. */
private fun LazyListState.centreOffset(index: Int): Float? {
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val viewport = layoutInfo.viewportEndOffset + layoutInfo.viewportStartOffset
    return (item.offset + item.size / 2f) - viewport / 2f
}

/** Focus nudges while live view runs: big and small steps towards infinity or the near end. */
@Composable
private fun FocusRail(state: UiState, haze: HazeState, actions: RemoteActions, modifier: Modifier) {
    Column(
        modifier.glass(haze, RoundedCornerShape(50), ThinGlassStyle).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val busy = state.live.focusingManually
        RoundIconButton(Icons.Rounded.KeyboardDoubleArrowUp, "Focus farther, big step", { actions.manualFocus(BIG_STEP) }, enabled = !busy)
        RoundIconButton(Icons.Rounded.KeyboardArrowUp, "Focus farther", { actions.manualFocus(SMALL_STEP) }, enabled = !busy)
        Text("MF", style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, modifier = Modifier.padding(vertical = 4.dp))
        RoundIconButton(Icons.Rounded.KeyboardArrowDown, "Focus nearer", { actions.manualFocus(-SMALL_STEP) }, enabled = !busy)
        RoundIconButton(Icons.Rounded.KeyboardDoubleArrowDown, "Focus nearer, big step", { actions.manualFocus(-BIG_STEP) }, enabled = !busy)
    }
}

private const val SMALL_STEP = 40
private const val BIG_STEP = 400

/** Ticks once a second from [since] (elapsed-realtime ms) and formats m:ss. */
@Composable
private fun rememberElapsed(since: Long?): String? {
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(250)
        }
    }
    if (since == null) return null
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Controls(state: UiState, haze: HazeState, actions: RemoteActions) {
    val latest = state.photos.firstOrNull()
    val thumb by produceState<ImageBitmap?>(null, latest?.handle) { value = latest?.let { actions.thumbnail(it.handle) } }
    var video by rememberSaveable { mutableStateOf(false) }
    var recordingSince by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.live.recording) {
        recordingSince = if (state.live.recording) recordingSince ?: android.os.SystemClock.elapsedRealtime() else null
    }
    val bulb = state.properties.values.any { it.meta.deckOrder == 1 && it.display == "Bulb" } && Capability.Bulb in state.capabilities
    val elapsed = rememberElapsed(if (video) recordingSince else state.live.bulbSince)
    // The last reading stays on the pill while it collapses, instead of the pill emptying first.
    val timer = rememberLast(elapsed?.let { if (video) "REC $it" else "Bulb $it" })
    val motion = Motion.current

    // Both open and close in place: the column is bottom-aligned, so the deck and picker above glide rather than jump.
    AnimatedVisibility(Capability.Movie in state.capabilities && state.live.running, enter = motion.expand, exit = motion.collapse) {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
            SegmentedControl(
                options = listOf("Photo", "Video"),
                selected = if (video) 1 else 0,
                onSelect = { video = it == 1 },
                modifier = Modifier.glass(haze, RoundedCornerShape(50), ThinGlassStyle),
                enabled = !state.live.recording,
                track = Color.Transparent,
                segmentPadding = 20.dp,
            )
        }
    }
    AnimatedVisibility(elapsed != null, enter = motion.expand, exit = motion.collapse) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(Palette.Danger.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(6.dp))
                Text(timer.orEmpty(), style = MaterialTheme.typography.labelLarge.tabular(), color = Color.White)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            androidx.compose.animation.AnimatedVisibility(latest != null, enter = motion.appear, exit = motion.disappear) {
              if (latest != null) Row(verticalAlignment = Alignment.CenterVertically) {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .size(52.dp)
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                        .background(Palette.Surface)
                        .clickable(interaction, ripple(), role = Role.Button, onClickLabel = "Open latest photo") { actions.openPhoto(latest.handle) },
                ) {
                    // A new shot landing scales in over the last one: a small confirmation that it arrived.
                    AnimatedContent(thumb, Modifier.fillMaxSize(), transitionSpec = { motion.morph() }, label = "latest-shot") { shot ->
                        shot?.let { Image(it, "Latest photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                }
                // Shoot, then share: the latest shot (with its RAW twin, if any) in one tap.
                Spacer(Modifier.width(10.dp))
                ShareButton(listOfNotNull(state.tileFor(latest.handle)), state, actions.share, haze = haze, style = ThinGlassStyle)
              }
            }
        }
        // One shutter for every mode, so switching Photo, Video and Bulb animates it instead of replacing it.
        val recording = when {
            video -> state.live.recording
            bulb -> state.live.bulbSince != null
            else -> null
        }
        val label = when {
            video -> if (recording == true) "Stop recording" else "Start recording"
            bulb -> if (recording == true) "Close shutter" else "Open shutter (bulb)"
            else -> "Take a picture"
        }
        val onShutter = when {
            video -> actions.toggleMovie
            bulb -> actions.toggleBulb
            else -> actions.shoot
        }
        Shutter(busy = recording == null && state.live.shooting, onClick = onShutter, recording = recording, label = label)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (Capability.Autofocus in state.capabilities && state.live.running) {
                Box(contentAlignment = Alignment.Center) {
                    RoundIconButton(Icons.Rounded.CenterFocusStrong, "Autofocus", actions.autofocus, haze = haze, style = ThinGlassStyle, size = 56.dp)
                    androidx.compose.animation.AnimatedVisibility(state.live.focusing, enter = motion.fadeIn, exit = motion.fadeOut) {
                        CircularProgressIndicator(Modifier.size(56.dp), strokeWidth = 2.dp, color = Palette.Accent, trackColor = Color.Transparent)
                    }
                }
            }
        }
    }
}

@Composable
private fun Shutter(busy: Boolean, onClick: () -> Unit, recording: Boolean?, label: String) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = Motion.current
    val down = pressed || busy
    // Presses go down at once and spring back with a little overshoot.
    val inner by animateFloatAsState(
        if (down) 0.82f else if (recording == true) 0.5f else 1f,
        if (down) motion.press() else motion.bounce(),
        label = "shutter",
    )
    Box(
        Modifier
            .size(80.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .clickable(interaction, null, enabled = !busy, role = Role.Button, onClickLabel = label) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // Recording and bulb use a red dot that turns into a rounded square while running: the colour
        // cross-fades and the corners round off on a spring. 16% is the old 10dp on the 64dp face.
        val fill by animateColorAsState(if (recording != null) Palette.Danger else Palette.Accent, motion.enter(), label = "shutter-fill")
        val corner by animateIntAsState(if (recording == true) 16 else 50, motion.move(), label = "shutter-corner")
        Box(Modifier.fillMaxSize().scale(inner).clip(RoundedCornerShape(corner)).background(fill))
        AnimatedVisibility(busy, enter = motion.fadeIn, exit = motion.fadeOut) {
            CircularProgressIndicator(Modifier.size(28.dp), color = Palette.OnAccent, strokeWidth = 2.5.dp, trackColor = Color.Transparent)
        }
    }
}
