package app.viewfinder.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import app.viewfinder.ptp.PropForm
import kotlin.math.roundToLong
import app.viewfinder.UiState
import app.viewfinder.ptp.PropValue
import app.viewfinder.ptp.camera.CameraProperty
import app.viewfinder.ptp.camera.Capability
import app.viewfinder.ptp.camera.Diagnostics
import app.viewfinder.ptp.camera.StandardProps
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

class SettingsActions(
    val back: () -> Unit,
    val setProperty: (Int, PropValue) -> Unit,
    val syncClock: () -> Unit,
    val formatCards: () -> Unit,
    val showLog: () -> Unit,
    val dismissMessage: () -> Unit,
)

/** Every property the camera exposes, grouped, with the editable ones one tap away. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, diagnostics: Diagnostics?, actions: SettingsActions) {
    val haze = rememberHazeState()
    val motion = Motion.current
    var editing by remember { mutableStateOf<Int?>(null) }
    // Hoisted, so choosing a value can slide the sheet away before it leaves composition.
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var headerHeight by remember { mutableStateOf(110.dp) }
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(state.properties, query) {
        val q = query.trim().lowercase()
        state.properties.values
            .filter { q.isEmpty() || q in it.name.lowercase() || q in it.display.lowercase() || q in "0x%04x".format(it.code) }
            .groupBy { it.meta.category }
            .toSortedMap(compareBy { it.ordinal })
    }

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            Modifier.fillMaxSize().hazeSource(haze),
            contentPadding = PaddingValues(top = headerHeight + 12.dp, bottom = 48.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { InfoCard(state) }
            item { Actions(state, actions) }
            if (state.properties.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search ${state.properties.size} settings") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = {
                            AnimatedContent(query.isNotEmpty(), transitionSpec = { motion.morph() }, label = "clear-search") { clearable ->
                                if (clearable) {
                                    val interaction = remember { MutableInteractionSource() }
                                    IconButton(onClick = { query = "" }, Modifier.pressScale(interaction), interactionSource = interaction) {
                                        Icon(Icons.Rounded.Close, "Clear search")
                                    }
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(50),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Palette.Accent,
                            unfocusedBorderColor = Palette.Hairline,
                            focusedContainerColor = Palette.Surface,
                            unfocusedContainerColor = Palette.Surface,
                            cursorColor = Palette.Accent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
            if (state.loadingProperties && state.properties.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Palette.Accent, trackColor = Palette.AccentSoft, strokeWidth = 2.5.dp)
                    }
                }
            }
            groups.forEach { (category, props) ->
                item(key = category.name) {
                    Section(category.label) {
                        props.sortedWith(compareByDescending<CameraProperty> { it.editable }.thenBy { it.meta.deckOrder ?: 99 }.thenBy { it.name })
                            .forEachIndexed { i, prop ->
                                PropertyRow(prop, divider = i > 0) { editing = prop.code }
                            }
                    }
                }
            }
            diagnostics?.let { d -> item { Inspector(d) } }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { headerHeight = with(density) { it.height.toDp() } }
                .hazeEffect(haze, GlassStyle)
                .drawBehind { drawLine(Palette.Hairline, Offset(0f, size.height), Offset(size.width, size.height), 1f) }
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", actions.back)
            Spacer(Modifier.width(12.dp))
            Text("Camera", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        }

        Toast(state.message, actions.dismissMessage, haze, Modifier.align(Alignment.BottomCenter))
    }

    editing?.let { code ->
        state.properties[code]?.let { prop ->
            ModalBottomSheet(
                onDismissRequest = { editing = null },
                sheetState = sheet,
                containerColor = Palette.Raised,
            ) {
                val apply: (PropValue) -> Unit = { value ->
                    actions.setProperty(code, value)
                    // Leaving composition at once would cut the sheet and its scrim; slide down first.
                    scope.launch { sheet.hide() }.invokeOnCompletion { editing = null }
                }
                val form = prop.desc.form
                when {
                    prop.desc.choices.isNotEmpty() -> ChoiceSheet(prop, apply)
                    form is PropForm.Range -> RangeSheet(prop, form, apply)
                    prop.desc.current is PropValue.Str -> TextSheet(prop, apply)
                    prop.desc.current is PropValue.Num -> NumberSheet(prop, apply)
                    else -> RawSheet(prop)
                }
            }
        }
    }
}

@Composable
private fun InfoCard(state: UiState) {
    val info = state.info ?: return
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.RadiusLg)).background(Palette.Surface).padding(20.dp),
    ) {
        Text(info.manufacturer.removeSuffix(" Corporation"), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        Text(info.model, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Fact("Battery", state.battery?.let { "$it%" } ?: "—")
            Fact("Shots left", state.shotsLeft?.let { "%,d".format(it) } ?: "—")
            Fact("Free", state.freeBytes?.let { sizeLabel(it) } ?: "—")
        }
        Spacer(Modifier.height(14.dp))
        // Each item stays whole when the line wraps (no orphaned "driver"), and a dot never starts a line.
        val facts = listOf("Firmware ${info.firmware}", "Serial ${info.serialNumber}", "${info.driver} driver")
            .joinToString("\u00A0 ·  ") { it.replace(' ', '\u00A0') }
        Text(facts, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = MaterialTheme.typography.titleMedium.tabular())
        Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}

@Composable
private fun Actions(state: UiState, actions: SettingsActions) {
    Section(null) {
        if (Capability.ClockSync in state.capabilities) {
            val clock = state.properties[StandardProps.DateTime]?.display
            ActionRow(Icons.Rounded.Schedule, "Set camera clock to phone time", clock?.let { "Camera says $it" }, actions.syncClock, divider = false)
        }
        ActionRow(Icons.AutoMirrored.Rounded.ReceiptLong, "Connection log", "Every command sent to the camera", actions.showLog, divider = Capability.ClockSync in state.capabilities)
        if (Capability.FormatCard in state.capabilities) {
            var confirm by remember { mutableStateOf(false) }
            ActionRow(Icons.Rounded.DeleteForever, "Format memory card", "Erases every photo and video on the card", { confirm = true }, divider = true, danger = true)
            if (confirm) {
                AlertDialog(
                    onDismissRequest = { confirm = false },
                    containerColor = Palette.Raised,
                    title = { Text("Format the memory card?", style = MaterialTheme.typography.titleLarge) },
                    text = {
                        Text(
                            "Every photo and video on the camera's card is erased, including ${state.photos.size} files not all saved to this phone. This can't be undone.",
                            color = Palette.TextSecondary,
                        )
                    },
                    confirmButton = { TextButton(onClick = { confirm = false; actions.formatCards() }) { Text("Format", color = Palette.Danger) } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = Palette.Text) } },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dimens.Radius)).background(Palette.Surface)) { content() }
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    divider: Boolean,
    danger: Boolean = false,
) {
    val tint = if (danger) Palette.Danger else Palette.Accent
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, pressed = 0.98f)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .drawDivider(divider)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(Dimens.IconSm))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) Palette.Danger else Palette.Text)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary) }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Palette.Muted)
    }
}

private fun Modifier.drawDivider(show: Boolean) = if (!show) this else drawBehind {
    drawLine(Palette.Hairline, Offset(16.dp.toPx(), 0f), Offset(size.width, 0f), 1f)
}

@Composable
private fun PropertyRow(prop: CameraProperty, divider: Boolean, onEdit: () -> Unit) {
    val canEdit = prop.editable && prop.desc.current !is PropValue.Arr
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, pressed = 0.98f)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .drawDivider(divider)
            .clickable(interaction, ripple(), enabled = canEdit, role = Role.Button, onClickLabel = "Change ${prop.name}", onClick = onEdit)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(prop.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text("0x%04X".format(prop.code), style = MaterialTheme.typography.labelMedium, color = Palette.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            prop.display,
            style = MaterialTheme.typography.bodyMedium,
            color = if (canEdit) Palette.Accent else Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(max = 180.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (canEdit) {
            Icon(Icons.Rounded.ChevronRight, null, tint = Palette.Muted, modifier = Modifier.size(Dimens.Icon))
        } else if (!prop.editable) {
            Icon(Icons.Rounded.Lock, "Read only", tint = Palette.Muted.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun ChoiceSheet(prop: CameraProperty, onChoose: (PropValue) -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(prop.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        LazyColumn(Modifier.fillMaxWidth().height((prop.desc.choices.size * 52).coerceAtMost(460).dp)) {
            items(prop.desc.choices) { value ->
                val on = value == prop.desc.current
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .pressScale(interaction, pressed = 0.98f)
                        .fillMaxWidth()
                        .height(52.dp)
                        .semantics { selected = on }
                        .clickable(interaction, ripple(), role = Role.RadioButton) { onChoose(value) }
                        .padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(prop.format(value), style = MaterialTheme.typography.bodyLarge, color = if (on) Palette.Accent else Palette.Text, modifier = Modifier.weight(1f))
                    if (on) Icon(Icons.Rounded.Check, "Current", tint = Palette.Accent)
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(prop: CameraProperty) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(prop.name, style = MaterialTheme.typography.titleLarge)
        Text("Now ${prop.display}", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
    }
}

/** For numeric ranges too long to list: a slider snapped to the camera's step. */
@Composable
private fun RangeSheet(prop: CameraProperty, form: PropForm.Range, onApply: (PropValue) -> Unit) {
    val min = form.min.asLong ?: 0
    val max = form.max.asLong ?: 0
    val step = (form.step.asLong ?: 1).coerceAtLeast(1)
    var value by remember { mutableStateOf((prop.desc.current.asLong ?: min).coerceIn(min, max)) }
    Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        SheetTitle(prop)
        Text(prop.format(PropValue.Num(value)), style = MaterialTheme.typography.headlineSmall.tabular(), color = Palette.Accent, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> value = (min + ((v - min) / step).roundToLong() * step).coerceIn(min, max) },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(thumbColor = Palette.Accent, activeTrackColor = Palette.Accent, inactiveTrackColor = Palette.AccentSoft),
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(prop.format(PropValue.Num(min)), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            Text(prop.format(PropValue.Num(max)), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        AccentButton("Set", { onApply(PropValue.Num(value)) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
    }
}

/** Free text, such as Artist or Copyright. PTP strings hold up to 254 characters. */
@Composable
private fun TextSheet(prop: CameraProperty, onApply: (PropValue) -> Unit) {
    var text by remember { mutableStateOf((prop.desc.current as? PropValue.Str)?.value.orEmpty()) }
    Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
        SheetTitle(prop)
        OutlinedTextField(
            value = text,
            onValueChange = { if (it.length <= 254) text = it },
            singleLine = false,
            shape = RoundedCornerShape(14.dp),
            supportingText = { Text("${text.length} / 254", style = LocalTextStyle.current.tabular()) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        AccentButton("Save", { onApply(PropValue.Str(text)) }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp))
    }
}

/** A number the camera accepts without listing its options. */
@Composable
private fun NumberSheet(prop: CameraProperty, onApply: (PropValue) -> Unit) {
    var text by remember { mutableStateOf(prop.desc.current.asLong?.toString().orEmpty()) }
    val parsed = text.trim().toLongOrNull()
    Column(Modifier.navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
        SheetTitle(prop)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            isError = text.isNotBlank() && parsed == null,
            supportingText = { Text(if (parsed != null) "Shows as ${prop.format(PropValue.Num(parsed))}" else "Whole number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        AccentButton("Set", { parsed?.let { onApply(PropValue.Num(it)) } }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), enabled = parsed != null)
    }
}

@Composable
private fun RawSheet(prop: CameraProperty) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 24.dp)) {
        SheetTitle(prop)
        Text(prop.display, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Palette.TextSecondary, modifier = Modifier.padding(horizontal = 24.dp))
    }
}

/** The raw capability lists, for figuring out what a model supports over Wi-Fi. */
@Composable
private fun Inspector(d: Diagnostics) {
    var open by rememberSaveable { mutableStateOf(false) }
    val motion = Motion.current
    val interaction = remember { MutableInteractionSource() }
    // One chevron turning over, rather than two icons swapping.
    val turn by animateFloatAsState(if (open) 180f else 0f, motion.move(), label = "inspector-chevron")
    Section("Everything the camera reports") {
        Row(
            Modifier
                .pressScale(interaction, pressed = 0.98f)
                .fillMaxWidth()
                .clickable(interaction, ripple(), role = Role.Button) { open = !open }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("${d.operations.size} operations  ·  ${d.properties.size} properties  ·  ${d.events.size} events", style = MaterialTheme.typography.bodyMedium)
                Text("Vendor extension ${d.vendorExtension}", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            Icon(Icons.Rounded.ExpandMore, if (open) "Hide" else "Show", tint = Palette.Muted, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(open, enter = motion.expand, exit = motion.collapse) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                listOf("Operations" to d.operations, "Properties" to d.properties, "Events" to d.events).forEach { (title, lines) ->
                    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.Accent, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    lines.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = Palette.TextSecondary) }
                }
            }
        }
    }
}
