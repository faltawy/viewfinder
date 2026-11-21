package app.viewfinder.ui

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery2Bar
import androidx.compose.material.icons.rounded.Battery4Bar
import androidx.compose.material.icons.rounded.Battery6Bar
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Frosted glass over photos: heavy blur, a warm charcoal tint and a little grain. */
val GlassStyle = HazeStyle(
    backgroundColor = Palette.Background,
    tint = HazeTint(Palette.Surface.copy(alpha = 0.64f)),
    blurRadius = 28.dp,
    noiseFactor = 0.06f,
)

/** Lighter glass for small floating controls over live view, so the image shows through. */
val ThinGlassStyle = HazeStyle(
    backgroundColor = Color.Black,
    tint = HazeTint(Palette.Background.copy(alpha = 0.42f)),
    blurRadius = 20.dp,
    noiseFactor = 0.04f,
)

/** A glass surface: blurs whatever [state]'s source draws behind it, with a hairline edge catching the light. */
fun Modifier.glass(state: HazeState, shape: Shape, style: HazeStyle = GlassStyle): Modifier =
    this
        .clip(shape)
        .hazeEffect(state = state, style = style)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))), shape)

/** A 48dp round icon button; glass when [haze] is given, otherwise a quiet filled circle. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    haze: HazeState? = null,
    style: HazeStyle = GlassStyle,
    tint: Color = Palette.Text,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = Dimens.Touch,
) {
    val shape = CircleShape
    val motion = Motion.current
    val interaction = remember { MutableInteractionSource() }
    val base = if (haze != null && !selected) modifier.pressScale(interaction).glass(haze, shape, style) else modifier.pressScale(interaction).clip(shape)
    // Selected, disabled and back blend rather than snap (the focus rail greys out on every nudge).
    val fill by animateColorAsState(if (selected) Palette.Text else if (haze == null) Palette.Raised else Color.Transparent, motion.enter(), label = "round-fill")
    val ink by animateColorAsState(
        when {
            !enabled -> Palette.Muted.copy(alpha = 0.5f)
            selected -> Palette.OnAccent
            else -> tint
        },
        motion.enter(), label = "round-ink",
    )
    Box(
        base
            .size(size)
            .background(fill)
            .clickable(interaction, ripple(), enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(Dimens.Icon))
    }
}

/** The one filled call to action per screen. Darkens and gives a little while pressed. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Darkens the instant it's pressed, eases back on release.
    val fill by animateColorAsState(
        if (!enabled) Palette.Raised else if (pressed) Palette.AccentPressed else Palette.Accent,
        if (pressed) snap() else Motion.current.enter(), label = "fill",
    )
    Row(
        modifier
            .pressScale(interaction)
            .defaultMinSize(minHeight = Dimens.Touch)
            .clip(RoundedCornerShape(50))
            .background(fill)
            .clickable(interaction, ripple(color = Palette.OnAccent), enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) Palette.OnAccent else Palette.Muted, modifier = Modifier.size(Dimens.IconSm))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Palette.OnAccent else Palette.Muted)
    }
}

/** A quiet secondary action: text on a raised pill. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = Palette.Text) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction)
            .defaultMinSize(minHeight = Dimens.Touch)
            .clip(RoundedCornerShape(50))
            .background(Palette.Text.copy(alpha = 0.08f))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(Dimens.IconSm))
            Spacer(Modifier.size(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Small uppercase tag on photos (RAW, RAW+JPG). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, strong: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = if (strong) Palette.OnAccent else Palette.Text,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (strong) Palette.Accent else Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/**
 * Slow drifting colour fields behind the connect screen, so its glass has something to frost.
 * Static when animations are turned off.
 */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val t = if (Motion.current.reduced) {
        0.3f
    } else {
        val transition = rememberInfiniteTransition(label = "ambient")
        val v by transition.animateFloat(0f, 1f, Motion.driftLoop(), label = "drift")
        v
    }
    Canvas(modifier.fillMaxSize().background(Palette.Background)) {
        fun blob(color: Color, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = r), radius = r, center = Offset(cx, cy))
        val w = size.width
        val h = size.height
        blob(Palette.Accent.copy(alpha = 0.42f), w * (0.15f + 0.25f * t), h * (0.12f + 0.08f * t), w * 0.75f)
        blob(Palette.DeepTeal.copy(alpha = 0.55f), w * (0.95f - 0.2f * t), h * (0.35f + 0.1f * t), w * 0.7f)
        blob(Palette.Gold.copy(alpha = 0.22f), w * (0.3f + 0.3f * t), h * (0.85f - 0.1f * t), w * 0.8f)
    }
}

/** The last non-null [value], so content that's leaving keeps showing what it showed, rather than emptying mid-exit. */
@Composable
fun <T : Any> rememberLast(value: T?): T? {
    val last = remember { mutableStateOf(value) }
    if (value != null) last.value = value
    return last.value
}

/** A value breathing between [from] and [to] (loading placeholders); held at the middle with reduced motion. */
@Composable
fun rememberPulse(from: Float, to: Float, label: String = "pulse"): Float {
    if (Motion.current.reduced) return (from + to) / 2
    val transition = rememberInfiniteTransition(label = label)
    val v by transition.animateFloat(from, to, Motion.pulseLoop(), label = label)
    return v
}

/**
 * Pressed things give a little: down at once, back with a small spring. Pass the same [interaction]
 * to the element's clickable.
 */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier {
    val motion = Motion.current
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, if (isPressed) motion.press() else motion.bounce(), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * One track, equal segments, and a raised pill that slides to the chosen one on a spring.
 * The labels are drawn twice: quiet ones on the track, and dark ones clipped to the pill, so whatever
 * the pill passes over is always crisp (no colour crossfade). Without a width from [modifier], every
 * segment takes the width of the widest label.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    track: Color = Palette.Text.copy(alpha = 0.07f),
    segmentPadding: Dp = 16.dp,
) {
    val position by animateFloatAsState(selected.toFloat(), Motion.current.move(), label = "segment")
    // One source for every segment: pressing any of them gives the whole control a little, at once.
    val interaction = remember { MutableInteractionSource() }
    val pill: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Rect = {
        val segment = size.width / options.size
        Rect(position * segment, 0f, (position + 1) * segment, size.height)
    }
    Box(
        modifier
            .pressScale(interaction, pressed = 0.98f)
            .width(IntrinsicSize.Max)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(3.dp)
            .drawBehind {
                val r = pill()
                drawRoundRect(Palette.Text, topLeft = r.topLeft, size = r.size, cornerRadius = CornerRadius(size.height / 2))
            },
    ) {
        SegmentLabels(options, if (enabled) Palette.TextSecondary else Palette.Muted, segmentPadding) { i ->
            Modifier
                .semantics { this.selected = i == selected }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Tab) { onSelect(i) }
        }
        SegmentLabels(
            options, Palette.OnAccent, segmentPadding,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    val r = pill()
                    clipRect(r.left, r.top, r.right, r.bottom) { this@drawWithContent.drawContent() }
                }
                .clearAndSetSemantics {},
        )
    }
}

@Composable
private fun SegmentLabels(
    options: List<String>,
    color: Color,
    padding: Dp,
    modifier: Modifier = Modifier,
    segment: (Int) -> Modifier = { Modifier },
) {
    Row(modifier) {
        options.forEachIndexed { i, label ->
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .then(segment(i))
                    .padding(horizontal = padding, vertical = 13.dp),
            )
        }
    }
}

/**
 * Fades a horizontally scrolling strip into its edges wherever there is more to scroll to, so content
 * runs out softly instead of being cut mid-word.
 */
fun Modifier.fadingEdges(scroll: ScrollableState, width: Dp = 28.dp): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fade = width.toPx()
            if (scroll.canScrollBackward) {
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fade), blendMode = BlendMode.DstIn)
            }
            if (scroll.canScrollForward) {
                drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fade, endX = size.width), blendMode = BlendMode.DstIn)
            }
        }

fun batteryIcon(level: Int) = when {
    level > 85 -> Icons.Rounded.BatteryFull
    level > 55 -> Icons.Rounded.Battery6Bar
    level > 25 -> Icons.Rounded.Battery4Bar
    else -> Icons.Rounded.Battery2Bar
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes B"
}
