package app.viewfinder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NetworkWifi1Bar
import androidx.compose.material.icons.rounded.NetworkWifi2Bar
import androidx.compose.material.icons.rounded.NetworkWifi3Bar
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SignalWifi4Bar
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.viewfinder.Connection
import app.viewfinder.NearbyWifi
import app.viewfinder.Stage
import app.viewfinder.UiState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * One glass card holds every step: pick a network, type its password, watch the connection, see what went
 * wrong. The card changes shape on a spring between steps, and every part that loads in reserves its space
 * first, so nothing on the screen jumps.
 */
@Composable
fun ConnectScreen(
    state: UiState,
    onChange: (name: String, password: String, address: String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    onShowLog: () -> Unit,
    onScan: () -> Unit,
    onLearn: () -> Unit,
) {
    val haze = rememberHazeState()
    val motion = Motion.current
    // Connected keeps the finished timeline up until the gallery arrives, rather than flashing the form.
    val busy = state.connection is Connection.Connecting || state.connection is Connection.Connected

    // Joining a Wi-Fi by name needs the "nearby devices" permission (location before Android 13).
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onConnect() }
    val connect = {
        if (state.wifiName.isNotBlank()) {
            permission.launch(
                if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            )
        } else {
            onConnect()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackground(Modifier.hazeSource(haze))

        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(40.dp))
            ViewfinderMark(
                when (state.connection) {
                    is Connection.Connecting -> MarkState.Searching
                    // Momentary: the gallery takes over at once, already reading the card.
                    is Connection.Connected -> MarkState.Working
                    is Connection.Failed -> MarkState.Lost
                    Connection.Idle -> MarkState.Idle
                },
                Modifier.size(56.dp).sharedMark(),
            )
            Spacer(Modifier.height(28.dp))
            Text("Viewfinder", style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(
                "Full-size photos, RAW files and a live remote for your camera, over its own Wi-Fi.",
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.TextSecondary,
            )
            Spacer(Modifier.height(28.dp))

            AnimatedContent(
                targetState = busy,
                transitionSpec = { motion.swap() },
                label = "connect-card",
                modifier = Modifier
                    .fillMaxWidth()
                    .glass(haze, RoundedCornerShape(Dimens.RadiusLg)),
            ) { busy ->
                Box(Modifier.fillMaxWidth().padding(20.dp)) {
                    if (busy) {
                        Progress((state.connection as? Connection.Connecting)?.stage, (state.connection as? Connection.Connecting)?.detail, title(state), onCancel)
                    } else {
                        Form(state, onChange, connect, onScan)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HowTo(haze)
            Spacer(Modifier.height(12.dp))
            LearnCard(haze, onLearn)

            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                val interaction = remember { MutableInteractionSource() }
                Text(
                    "Connection log",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.TextSecondary,
                    modifier = Modifier
                        .pressScale(interaction)
                        .clip(RoundedCornerShape(50))
                        .clickable(interaction, ripple(), role = Role.Button, onClick = onShowLog)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private fun title(state: UiState): String = when {
    state.lastCamera != null -> "Reconnecting to ${state.lastCamera}"
    state.wifiName.isNotBlank() -> "Connecting to ${state.wifiName}"
    else -> "Connecting"
}

@Composable
private fun Form(
    state: UiState,
    onChange: (String, String, String) -> Unit,
    connect: () -> Unit,
    onScan: () -> Unit,
) {
    val motion = Motion.current
    var showPassword by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(state.cameraAddress.isNotEmpty()) }
    val failed = state.connection as? Connection.Failed

    Column {
        Text("Connect", style = MaterialTheme.typography.titleLarge)

        AnimatedVisibility(failed != null, enter = motion.expand, exit = motion.collapse) {
            failed?.let { ErrorNote(it.message, Modifier.padding(top = 12.dp)) }
        }

        Spacer(Modifier.height(12.dp))
        Nearby(state, onScan) { ssid -> onChange(ssid, state.wifiPassword, state.cameraAddress) }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.wifiName,
            onValueChange = { onChange(it, state.wifiPassword, state.cameraAddress) },
            label = { Text("Camera Wi-Fi name") },
            placeholder = { Text("D5600_1234567") },
            leadingIcon = { Icon(Icons.Rounded.Wifi, null) },
            supportingText = { Text("Leave empty if the phone already joined it") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(state.wifiName.isNotBlank(), enter = motion.expand, exit = motion.collapse) {
            OutlinedTextField(
                value = state.wifiPassword,
                onValueChange = { onChange(state.wifiName, it, state.cameraAddress) },
                label = { Text("Wi-Fi password") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                trailingIcon = {
                    val interaction = remember { MutableInteractionSource() }
                    IconButton(onClick = { showPassword = !showPassword }, Modifier.pressScale(interaction), interactionSource = interaction) {
                        AnimatedContent(showPassword, transitionSpec = { motion.morph() }, label = "password-eye") { shown ->
                            Icon(
                                if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                if (shown) "Hide password" else "Show password",
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        val addressInteraction = remember { MutableInteractionSource() }
        // One chevron turning over, rather than two icons swapping.
        val turn by animateFloatAsState(if (advanced) 180f else 0f, motion.move(), label = "address-chevron")
        Row(
            Modifier
                .pressScale(addressInteraction)
                .clip(RoundedCornerShape(50))
                .clickable(addressInteraction, ripple(), role = Role.Button, onClickLabel = if (advanced) "Hide camera address" else "Show camera address") { advanced = !advanced }
                .heightIn(min = Dimens.Touch)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Camera address", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)
            Icon(Icons.Rounded.ExpandMore, null, tint = Palette.TextSecondary, modifier = Modifier.graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(advanced, enter = motion.expand, exit = motion.collapse) {
            OutlinedTextField(
                value = state.cameraAddress,
                onValueChange = { onChange(state.wifiName, state.wifiPassword, it) },
                label = { Text("IP address") },
                placeholder = { Text("Found automatically") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(14.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        AccentButton(if (failed != null) "Try again" else "Connect to camera", connect, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ErrorNote(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.Radius))
            .background(Palette.Danger.copy(alpha = 0.12f))
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(14.dp),
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Palette.Danger, modifier = Modifier.size(Dimens.Icon))
        Spacer(Modifier.width(12.dp))
        Text(message, color = Palette.Text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Palette.Accent,
    unfocusedBorderColor = Palette.Text.copy(alpha = 0.18f),
    focusedLabelColor = Palette.Accent,
    cursorColor = Palette.Accent,
    focusedContainerColor = Palette.Background.copy(alpha = 0.35f),
    unfocusedContainerColor = Palette.Background.copy(alpha = 0.25f),
    unfocusedLeadingIconColor = Palette.TextSecondary,
    focusedLeadingIconColor = Palette.Accent,
    unfocusedSupportingTextColor = Palette.Muted,
    focusedSupportingTextColor = Palette.Muted,
)

/** Listing Wi-Fi networks needs precise location on every Android version, even with "Nearby devices". */
private fun scanPermissions(): Array<String> =
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

private enum class NearbyView { Permission, Loading, Empty, List }

/**
 * Nearby Wi-Fi networks, cameras first. Tapping one fills in the name, so only the password is typed,
 * and only the first time: the app remembers it per network.
 */
@Composable
private fun Nearby(state: UiState, onScan: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val motion = Motion.current
    fun granted() = scanPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = granted()
        if (allowed) onScan()
    }
    LaunchedEffect(Unit) { if (allowed) onScan() else ask.launch(scanPermissions()) }

    // The header keeps one height whether it shows the spinner or the refresh button.
    Row(Modifier.height(Dimens.Touch), verticalAlignment = Alignment.CenterVertically) {
        Text("Nearby networks", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.weight(1f))
        Box(Modifier.size(Dimens.Touch), contentAlignment = Alignment.Center) {
            AnimatedContent(state.scanning, transitionSpec = { motion.morph() }, label = "scan-button") { scanning ->
                when {
                    scanning -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent, trackColor = Palette.AccentSoft)
                    allowed -> {
                        val interaction = remember { MutableInteractionSource() }
                        IconButton(onClick = onScan, Modifier.pressScale(interaction), interactionSource = interaction) {
                            Icon(Icons.Rounded.Refresh, "Scan again", tint = Palette.TextSecondary)
                        }
                    }
                    else -> Spacer(Modifier.size(Dimens.Touch))
                }
            }
        }
    }

    val view = when {
        !allowed -> NearbyView.Permission
        state.nearby.isEmpty() && (state.scanning || !state.scanned) -> NearbyView.Loading
        state.nearby.isEmpty() -> NearbyView.Empty
        else -> NearbyView.List
    }
    AnimatedContent(view, transitionSpec = { motion.swap() }, label = "nearby") { shown ->
        when (shown) {
            NearbyView.Permission -> Column {
                Text(
                    "Android only lets apps list Wi-Fi networks with location access. Viewfinder uses it for the list and never reads where you are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(8.dp))
                QuietButton("Allow", { ask.launch(scanPermissions()) }, icon = Icons.Rounded.Wifi)
            }
            NearbyView.Loading -> Placeholders()
            NearbyView.Empty -> Text(
                "No networks found yet. Turn on the camera's Wi-Fi, then scan again.",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Muted,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            NearbyView.List -> Networks(state, onPick)
        }
    }
}

private val RowHeight = Dimens.Touch + 12.dp

/** Three breathing rows the size of real ones, so the list fills in rather than pushing the form down. */
@Composable
private fun Placeholders() {
    val pulse = rememberPulse(0.3f, 0.7f, label = "placeholders")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Background.copy(alpha = 0.3f))
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        repeat(3) { i ->
            Row(Modifier.fillMaxWidth().height(RowHeight).padding(horizontal = 14.dp).alpha(pulse), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(Dimens.Icon).clip(CircleShape).background(Palette.Text.copy(alpha = 0.12f)))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.width(if (i == 1) 120.dp else 168.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Text.copy(alpha = 0.12f)))
            }
        }
    }
}

@Composable
private fun Networks(state: UiState, onPick: (String) -> Unit) {
    val motion = Motion.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val cameras = state.nearby.filter { it.isCamera }
    val others = state.nearby.filterNot { it.isCamera }
    val shown = cameras + if (showAll || cameras.isEmpty()) others.take(if (showAll) others.size else 4) else emptyList()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Background.copy(alpha = 0.3f))
            .animateContentSize(motion.move()),
    ) {
        shown.forEachIndexed { i, net ->
            NetworkRow(net, picked = net.ssid == state.wifiName, onPick)
            if (i < shown.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 48.dp).height(1.dp).background(Palette.Hairline))
        }
        if (others.isNotEmpty() && cameras.isNotEmpty() || others.size > 4) {
            val interaction = remember { MutableInteractionSource() }
            Text(
                if (showAll) "Show fewer" else "Show all ${state.nearby.size} networks",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextSecondary,
                modifier = Modifier
                    .pressScale(interaction, pressed = 0.98f)
                    .fillMaxWidth()
                    .clickable(interaction, ripple(), role = Role.Button) { showAll = !showAll }
                    .heightIn(min = Dimens.Touch)
                    .padding(horizontal = 14.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun NetworkRow(net: NearbyWifi, picked: Boolean, onPick: (String) -> Unit) {
    val motion = Motion.current
    val tint by animateColorAsState(if (picked) Palette.AccentSoft else Palette.AccentSoft.copy(alpha = 0f), motion.enter(), label = "picked")
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .pressScale(interaction, pressed = 0.98f)
            .fillMaxWidth()
            .height(RowHeight)
            .background(tint)
            .semantics { selected = picked }
            .clickable(interaction, ripple(), role = Role.RadioButton, onClickLabel = "Use ${net.ssid}") { onPick(net.ssid) }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (net.isCamera) Icons.Rounded.PhotoCamera else wifiIcon(net.bars),
            if (net.isCamera) "Camera" else "Signal ${net.bars} of 3",
            tint = if (net.isCamera) Palette.Accent else Palette.TextSecondary,
            modifier = Modifier.size(Dimens.Icon),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(net.ssid, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            if (net.isCamera) Text("Looks like a camera", style = MaterialTheme.typography.bodySmall, color = Palette.Accent)
        }
        // One fixed slot on the right: the lock and the check swap in place.
        Box(Modifier.size(Dimens.Icon), contentAlignment = Alignment.Center) {
            AnimatedContent(picked, transitionSpec = { motion.morph() }, label = "row-mark") { isPicked ->
                when {
                    isPicked -> Icon(Icons.Rounded.Check, "Selected", tint = Palette.Accent, modifier = Modifier.size(Dimens.Icon))
                    net.secured -> Icon(Icons.Rounded.Lock, "Password protected", tint = Palette.Muted, modifier = Modifier.size(14.dp))
                    else -> Spacer(Modifier.size(Dimens.Icon))
                }
            }
        }
    }
}

private fun wifiIcon(bars: Int) = when (bars) {
    3 -> Icons.Rounded.SignalWifi4Bar
    2 -> Icons.Rounded.NetworkWifi3Bar
    1 -> Icons.Rounded.NetworkWifi2Bar
    else -> Icons.Rounded.NetworkWifi1Bar
}

/**
 * A three-step timeline: Wi-Fi, camera, session. Each step's mark changes in place; the rail fills as it
 * goes. A null [current] stage means every step is done.
 */
@Composable
private fun Progress(current: Stage?, detail: String?, title: String, onCancel: () -> Unit) {
    val motion = Motion.current
    Column(Modifier.animateContentSize(motion.move())) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Spacer(Modifier.height(18.dp))
        Stage.entries.forEach { stage ->
            val done = current == null || stage.ordinal < current.ordinal
            val active = stage == current
            Row(verticalAlignment = Alignment.Top) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                        AnimatedContent(if (done) 2 else if (active) 1 else 0, transitionSpec = { motion.morph() }, label = "stage-mark") { mark ->
                            when (mark) {
                                2 -> Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.Accent), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Check, "Done", tint = Palette.OnAccent, modifier = Modifier.size(16.dp))
                                }
                                1 -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Palette.Accent, trackColor = Palette.AccentSoft)
                                else -> Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Text.copy(alpha = 0.25f)))
                            }
                        }
                    }
                    if (stage != Stage.entries.last()) {
                        val rail by animateColorAsState(if (done) Palette.Accent else Palette.Text.copy(alpha = 0.14f), motion.enter(), label = "rail")
                        Box(Modifier.padding(vertical = 4.dp).width(2.dp).height(22.dp).clip(RoundedCornerShape(1.dp)).background(rail))
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    val labelColor by animateColorAsState(if (done || active) Palette.Text else Palette.Muted, motion.enter(), label = "label")
                    Text(stage.label, style = MaterialTheme.typography.titleMedium, color = labelColor)
                    AnimatedVisibility(active && detail != null, enter = motion.expand, exit = motion.collapse) {
                        AnimatedContent(detail.orEmpty(), transitionSpec = { motion.swap() }, label = "detail") { text ->
                            Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        QuietButton("Cancel", onCancel, Modifier.fillMaxWidth())
    }
}

/** The way into the lessons, which need no camera: here for someone who has the app before they have the knack. */
@Composable
private fun LearnCard(haze: HazeState, onLearn: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .pressScale(interaction, pressed = 0.98f)
            .glass(haze, RoundedCornerShape(Dimens.RadiusLg))
            .clickable(interaction, ripple(), role = Role.Button, onClick = onLearn)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.School, null, tint = Palette.Accent)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("New to photography?", style = MaterialTheme.typography.titleMedium)
            Text("Learn the basics with real photos you can touch. No camera needed.", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Palette.TextSecondary, modifier = Modifier.size(Dimens.IconSm))
    }
}

@Composable
private fun HowTo(haze: HazeState) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(haze, RoundedCornerShape(Dimens.RadiusLg))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("On the D5600", style = MaterialTheme.typography.titleMedium)
        Step(1, "Setup menu › Connect to smart device › Wi-Fi connection › Establish Wi-Fi connection.")
        Step(2, "The camera shows its network name and password. Pick it above and type the password, or join in Android's Wi-Fi settings.")
        Step(3, "Tap Connect. The first time, the camera may ask you to confirm.")
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row {
        Box(Modifier.size(24.dp).clip(CircleShape).background(Palette.AccentSoft), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelSmall, color = Palette.Accent)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
    }
}
