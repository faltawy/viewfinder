package app.viewfinder.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every packet-level step, for working out what a new camera model does differently. */
@Composable
fun LogScreen(lines: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }

    Column(Modifier.fillMaxSize().background(Palette.Background)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text("Connection log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            RoundIconButton(Icons.Rounded.Share, "Share log", {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
                }
                context.startActivity(Intent.createChooser(send, "Share log"))
            })
        }
        if (lines.isEmpty()) {
            Text("Nothing yet. Connect to a camera first.", color = Palette.Muted, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 12.dp)) {
            itemsIndexed(lines) { _, line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = if ("✕" in line) Palette.Danger else Palette.Text.copy(alpha = 0.85f),
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}
