package app.viewfinder.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.lerp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import app.viewfinder.PhotoItem
import app.viewfinder.ShareCrop
import app.viewfinder.ShareFormat
import app.viewfinder.ShareOptions
import app.viewfinder.ShareQuality
import app.viewfinder.ShareSize
import app.viewfinder.UiState
import app.viewfinder.photos

/** What the screens can do about sharing; the panel itself lives once, above every screen. */
class ShareActions(
    /** One tap: share with the saved options. */
    val share: (List<PhotoItem>) -> Unit,
    /** Long-press: choose the options first. */
    val openPanel: (List<PhotoItem>) -> Unit,
    val cancel: () -> Unit,
)

/**
 * The Share button: tap shares with your defaults, long-press opens the options. While its share is being
 * prepared it shows the progress around it and becomes a cancel button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShareButton(
    tiles: List<PhotoItem>,
    state: UiState,
    actions: ShareActions,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.Touch,
    haze: HazeState? = null,
    style: HazeStyle = GlassStyle,
) {
    val motion = Motion.current
    val sharing = state.sharing
    val mine = sharing != null && tiles.any { t -> t.handles.any { it in sharing.handles } }
    val progress = when {
        !mine -> null
        sharing!!.total == 1 -> state.saving[sharing.handles.first()]
        else -> sharing.done.toFloat() / sharing.total
    }
    val interaction = remember { MutableInteractionSource() }
    val enabled = tiles.isNotEmpty() && (sharing == null || mine)
    val surface = if (haze != null) Modifier.glass(haze, CircleShape, style) else Modifier.clip(CircleShape).background(Palette.Raised)
    Box(
        modifier
            .pressScale(interaction)
            .then(surface)
            .size(size)
            .combinedClickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled,
                role = Role.Button,
                onClickLabel = if (mine) "Cancel sharing" else "Share",
                onLongClickLabel = "Share options",
                onLongClick = { if (!mine) actions.openPanel(tiles) },
                onClick = { if (mine) actions.cancel() else actions.share(tiles) },
            )
            .semantics { if (mine) stateDescription = "Preparing" },
        contentAlignment = Alignment.Center,
    ) {
        // Share becomes cancel-with-progress and back as one morph, never a cut.
        AnimatedContent(mine, transitionSpec = { motion.morph() }, contentAlignment = Alignment.Center, label = "share-state") { busy ->
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                if (busy) {
                    val ring = Modifier.size(size - 6.dp)
                    if (progress != null) {
                        val shown by animateFloatAsState(progress, motion.move(), label = "share-progress")
                        CircularProgressIndicator({ shown }, ring, color = Palette.Accent, trackColor = Palette.AccentSoft, strokeWidth = 2.5.dp)
                    } else {
                        CircularProgressIndicator(ring, color = Palette.Accent, trackColor = Palette.AccentSoft, strokeWidth = 2.5.dp)
                    }
                    Icon(Icons.Rounded.Close, "Cancel sharing", tint = Palette.Text, modifier = Modifier.size(Dimens.IconSm))
                } else {
                    Icon(Icons.Rounded.Share, "Share", tint = if (enabled) Palette.Text else Palette.Muted.copy(alpha = 0.5f), modifier = Modifier.size(Dimens.Icon))
                }
            }
        }
    }
}

/** Formats a share of [count] photos can use on this phone. */
private fun formatsFor(count: Int, heif: Boolean) = ShareFormat.entries.filter { (count > 1 || !it.bulkOnly) && (heif || it != ShareFormat.Heif) }

/**
 * The share options: format first (it decides which other rows apply), then size, quality, crop, the photo
 * card and privacy, over a live preview. Every choice is remembered as the default for the next share.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SharePanel(
    tiles: List<PhotoItem>,
    state: UiState,
    preview: suspend (Long) -> ImageBitmap?,
    thumbnail: suspend (Long) -> ImageBitmap?,
    /** The photo card's caption for a photo, read from its details. */
    caption: suspend (PhotoItem) -> String,
    /** Every change is kept at once as the new default, whether or not this panel ends in a share. */
    onOptionsChange: (ShareOptions) -> Unit,
    onShare: (options: ShareOptions, toLastApp: Boolean, focusX: Float, focusY: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val motion = Motion.current
    val count = tiles.size
    var options by remember { mutableStateOf(state.shareOptions) }
    LaunchedEffect(options) { if (options != state.shareOptions) onOptionsChange(options) }
    val format = options.formatFor(count).takeIf { it in formatsFor(count, state.heifSupported) } ?: ShareFormat.Original
    var focusX by remember { mutableFloatStateOf(0.5f) }
    var focusY by remember { mutableFloatStateOf(0.5f) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val first = tiles.first()
    val image by produceState<ImageBitmap?>(null, first.handle) { value = preview(first.handle) ?: thumbnail(first.handle) }
    val cardText by produceState("", first.handle) { value = caption(first) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheet,
        containerColor = Palette.Surface,
        contentColor = Palette.Text,
        dragHandle = { BottomSheetDefaults.DragHandle(color = Palette.Muted.copy(alpha = 0.6f)) },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Text("Share ${photos(count)}", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            // The preview: the framed photo for converted formats, a stack of thumbnails otherwise.
            val previewKind = when {
                format.rendered -> 0
                count == 1 -> 1
                else -> 2
            }
            AnimatedContent(
                previewKind,
                transitionSpec = { motion.swap() },
                contentAlignment = Alignment.Center,
                label = "share-preview",
                modifier = Modifier.fillMaxWidth().height(240.dp),
            ) { kind ->
              Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                if (kind == 0) {
                    FramedPreview(
                        image, options.crop, options.card, cardText,
                        focusX, focusY,
                        onFocus = if (count == 1) { x, y -> focusX = x; focusY = y } else null,
                    )
                } else if (kind == 1) {
                    FramedPreview(image, ShareCrop.AsShot, card = false, caption = "", focusX = 0.5f, focusY = 0.5f, onFocus = null)
                } else {
                    Stack(tiles, thumbnail, format)
                }
              }
            }
            Spacer(Modifier.height(20.dp))

            Label("Format")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                formatsFor(count, state.heifSupported).forEach { f ->
                    Choice(f.label, f == format) { options = options.withFormat(count, f) }
                }
            }
            Hint(formatHint(format))

            AnimatedVisibility(format.rendered, enter = motion.expand, exit = motion.collapse) {
                Column {
                    Label("Size")
                    SegmentedControl(ShareSize.entries.map { it.label }, options.size.ordinal, { options = options.copy(size = ShareSize.entries[it]) }, Modifier.fillMaxWidth(), segmentPadding = 4.dp)
                    Label("Quality")
                    SegmentedControl(ShareQuality.entries.map { it.label }, options.quality.ordinal, { options = options.copy(quality = ShareQuality.entries[it]) }, Modifier.fillMaxWidth(), segmentPadding = 4.dp)
                    Label("Crop")
                    SegmentedControl(ShareCrop.entries.map { it.label }, options.crop.ordinal, { options = options.copy(crop = ShareCrop.entries[it]) }, Modifier.fillMaxWidth(), segmentPadding = 4.dp)
                    if (count == 1 && options.crop != ShareCrop.AsShot) Hint("Drag the photo to choose what stays in the frame.")
                    Spacer(Modifier.height(8.dp))
                    Toggle("Photo card", "A white border with the camera, lens and settings", options.card) { options = options.copy(card = it) }
                }
            }

            AnimatedVisibility(format != ShareFormat.ContactSheet, enter = motion.expand, exit = motion.collapse) {
                Column {
                    Label("Privacy")
                    Toggle("Remove location", "Where the photo was taken never leaves the phone", options.removeLocation) { options = options.copy(removeLocation = it) }
                    Toggle("Remove camera details", "Camera, lens, settings and time", options.removeDetails) { options = options.copy(removeDetails = it) }
                    privacyHint(format, options)?.let { Hint(it) }
                }
            }

            Spacer(Modifier.height(12.dp))
            if (format != ShareFormat.ContactSheet) {
                Text(
                    "Photos not on your phone yet are saved to Pictures/Viewfinder as they're shared.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(16.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                state.lastShareApp?.let { app ->
                    val icon = remember(app.component) { app.icon.toBitmap(96, 96).asImageBitmap() }
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        Modifier
                            .pressScale(interaction)
                            .widthIn(max = 180.dp)
                            .height(Dimens.Touch)
                            .clip(RoundedCornerShape(50))
                            .background(Palette.Raised)
                            .clickable(interaction, androidx.compose.material3.ripple(), role = Role.Button, onClickLabel = "Share to ${app.label}") {
                                onShare(options, true, focusX, focusY)
                            }
                            .padding(start = 8.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(icon, null, Modifier.size(30.dp).clip(CircleShape))
                        Spacer(Modifier.width(8.dp))
                        Text(app.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                AccentButton("Share", { onShare(options, false, focusX, focusY) }, Modifier.weight(1f), icon = Icons.Rounded.Share)
            }
        }
    }
}

/**
 * A single photo as it will be sent: in its crop frame (draggable to move the frame when [onFocus] is set),
 * with the photo card around it when chosen.
 */
@Composable
private fun FramedPreview(
    image: ImageBitmap?,
    crop: ShareCrop,
    card: Boolean,
    caption: String,
    focusX: Float,
    focusY: Float,
    onFocus: ((Float, Float) -> Unit)?,
) {
    val ratio = crop.ratio ?: image?.let { it.width.toFloat() / it.height } ?: 1.5f
    val shownRatio by animateFloatAsState(ratio, Motion.current.move(), label = "share-crop")
    val density = LocalDensity.current
    val motion = Motion.current
    // The card grows around the photo rather than snapping on: border, band and paper on one spring.
    val cardness by animateFloatAsState(if (card) 1f else 0f, motion.move(), label = "share-card")
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val pad = 10.dp * cardness
        val band = 28.dp * cardness
        // Fit the framed photo (plus the card) inside the preview area.
        val maxW = maxWidth - pad * 2
        val maxH = maxHeight - pad - band
        val w = minOf(maxW, maxH * shownRatio)
        val h = w / shownRatio
        Column(
            Modifier
                .clip(RoundedCornerShape(lerp(12.dp, 4.dp, cardness)))
                .background(Paper.copy(alpha = cardness))
                .padding(start = pad, end = pad, top = pad),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(w, h)
                    .clip(RoundedCornerShape(lerp(12.dp, 0.dp, cardness)))
                    .background(Palette.Raised)
                    .then(
                        if (onFocus != null && image != null && crop.ratio != null) {
                            Modifier.pointerInput(crop, image) {
                                // How far the photo overflows the frame, so a drag moves it 1:1 under the finger.
                                val imageRatio = image.width.toFloat() / image.height
                                val overflowX = with(density) { (h.toPx() * imageRatio - w.toPx()).coerceAtLeast(0f) }
                                val overflowY = with(density) { (w.toPx() / imageRatio - h.toPx()).coerceAtLeast(0f) }
                                var fx = focusX
                                var fy = focusY
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    if (overflowX > 0) fx = (fx - drag.x / overflowX).coerceIn(0f, 1f)
                                    if (overflowY > 0) fy = (fy - drag.y / overflowY).coerceIn(0f, 1f)
                                    onFocus(fx, fy)
                                }
                            }
                        } else {
                            Modifier
                        },
                    )
                    .semantics { contentDescription = "Preview" },
            ) {
                image?.let {
                    Image(
                        it, null,
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(focusX * 2 - 1, focusY * 2 - 1),
                        modifier = Modifier.size(w, h),
                    )
                }
            }
            if (cardness > 0f) {
                Box(Modifier.height(band).graphicsLayer { alpha = cardness }, contentAlignment = Alignment.Center) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.2.sp),
                        color = PaperInk,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Up to five thumbnails fanned out, with an icon for packed formats. */
@Composable
private fun Stack(tiles: List<PhotoItem>, thumbnail: suspend (Long) -> ImageBitmap?, format: ShareFormat) {
    val shown = tiles.take(5)
    val motion = Motion.current
    // The pile fans out once as the panel opens, so "several photos" reads at a glance.
    val fan = remember { Animatable(if (motion.reduced) 1f else 0f) }
    LaunchedEffect(Unit) { fan.animateTo(1f, motion.bounce()) }
    Box(contentAlignment = Alignment.Center) {
        shown.forEachIndexed { i, tile ->
            val thumb by produceState<ImageBitmap?>(null, tile.handle) { value = thumbnail(tile.handle) }
            val offset = (i - (shown.size - 1) / 2f) * 34 * fan.value
            val tilt = (i - (shown.size - 1) / 2f) * 5f * fan.value
            Box(
                Modifier
                    .offset(x = offset.dp)
                    .rotate(tilt)
                    .size(120.dp, 150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Raised)
                    .border(2.dp, Palette.Surface, RoundedCornerShape(10.dp)),
            ) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.size(120.dp, 150.dp)) }
            }
        }
        val badge = when (format) {
            ShareFormat.Zip -> Icons.Rounded.FolderZip
            ShareFormat.ContactSheet -> Icons.Rounded.GridView
            else -> null
        }
        badge?.let {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                Icon(it, null, tint = Palette.OnAccent)
            }
        }
    }
}

private fun formatHint(format: ShareFormat) = when (format) {
    ShareFormat.Original -> "Exactly as they came off the card, RAW and video included."
    ShareFormat.Jpeg -> "Opens everywhere."
    ShareFormat.Heif -> "About half the size of JPEG at the same quality."
    ShareFormat.Webp -> "Small files for chats and the web."
    ShareFormat.Zip -> "Every original in one file, for mail, Drive or Telegram."
    ShareFormat.ContactSheet -> "A PDF grid of the photos with names and settings, for picking shots together."
}

private fun privacyHint(format: ShareFormat, options: ShareOptions): String? = when {
    format == ShareFormat.Heif && !options.removeDetails -> "HEIF copies don't carry camera details."
    !format.rendered && options.removeLocation -> "RAW files and videos with a location are left out; JPEGs are cleaned."
    !format.rendered && options.removeDetails -> "RAW files and videos keep their details."
    else -> null
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.Muted, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Choice(text: String, selected: Boolean, onClick: () -> Unit) {
    val motion = Motion.current
    val interaction = remember { MutableInteractionSource() }
    val fill by androidx.compose.animation.animateColorAsState(if (selected) Palette.Accent else Palette.Raised, motion.enter(), label = "choice")
    val ink by androidx.compose.animation.animateColorAsState(if (selected) Palette.OnAccent else Palette.Text, motion.enter(), label = "choice-ink")
    Box(
        Modifier
            .pressScale(interaction)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(interaction, androidx.compose.material3.ripple(), role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink)
    }
}

@Composable
private fun Toggle(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked, null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.OnAccent, checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.Muted, uncheckedTrackColor = Palette.Raised, uncheckedBorderColor = Palette.Hairline,
            ),
        )
    }
}

// The photo card is paper, so its preview uses the printed card's colours rather than the dark theme.
private val Paper = Color(0xFFF6F6F2)
private val PaperInk = Color(0xFF5C645F)
