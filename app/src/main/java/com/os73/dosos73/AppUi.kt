package com.os73.dosos73

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        }
    }
}

@Composable internal fun Brand() {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Image(painterResource(R.drawable.brand), null, Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)))
        Text("dosos73", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Download open-source OS73", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun HomeScreen(
    screen: ScreenState, transfer: TransferState, customFolder: Boolean,
    onEdit: (String) -> Unit, onSelect: (Choice) -> Unit, onAnalyze: () -> Unit, onCancelAnalysis: () -> Unit,
    onPaste: () -> Unit, onAbout: () -> Unit, onFolder: () -> Unit, onDefaultFolder: () -> Unit,
    onStart: () -> Unit, onCancel: () -> Unit, onOpen: () -> Unit, onNewDownload: () -> Unit,
) {
    val enabled = !screen.busy && !transfer.active
    val choices = screen.analysis?.choices.orEmpty()
    val selected = screen.selected
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onAbout, Modifier.testTag("about")) { Text(stringResource(R.string.about)) }
        }
        Brand()
        Text(stringResource(R.string.title), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.subtitle), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (!transfer.active && transfer.phase != Phase.COMPLETE) {
        OutlinedTextField(screen.url, onEdit, Modifier.fillMaxWidth().testTag("url"), enabled = enabled,
            label = { Text(stringResource(R.string.url)) }, singleLine = true, shape = RoundedCornerShape(18.dp),
            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, disabledContainerColor = Color.White))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onPaste, enabled = enabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.paste)) }
            TextButton({ onEdit("") }, enabled = enabled) { Text(stringResource(R.string.clear)) }
            Button(onAnalyze, enabled = enabled && screen.url.isNotBlank(), modifier = Modifier.testTag("analyze")) { Text(stringResource(R.string.analyze)) }
        }
        if (screen.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.analyzing), Modifier.weight(1f))
                TextButton(onCancelAnalysis) { Text(stringResource(R.string.cancel)) }
            }
        }
        screen.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        if (transfer.phase != Phase.IDLE) TransferCard(transfer, onCancel, onOpen)
        if (transfer.phase == Phase.COMPLETE) OutlinedButton(onNewDownload, Modifier.fillMaxWidth()) { Text(stringResource(R.string.new_download)) }
        if (!transfer.active && transfer.phase != Phase.COMPLETE) {
            screen.analysis?.let { Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Kind.VIDEO, Kind.AUDIO).forEach { kind ->
                    val chosen = selected?.kind == kind
                    val available = choices.firstOrNull { it.kind == kind }
                    FilledTonalButton(onClick = { available?.let(onSelect) }, enabled = enabled && available != null,
                        modifier = Modifier.weight(1f).heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface)) {
                        Text(stringResource(if (kind == Kind.VIDEO) R.string.video else R.string.audio), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            Selection(stringResource(R.string.quality), selected?.quality?.let { qualityName(it) } ?: stringResource(R.string.analyze_first), enabled && selected != null) { close ->
                choices.filter { it.kind == selected?.kind }.map { it.quality }.distinct().forEach { quality ->
                    DropdownMenuItem(text = { Text(qualityName(quality)) }, onClick = { onSelect(choices.first { it.kind == selected?.kind && it.quality == quality }); close() })
                }
            }
            Selection(stringResource(R.string.format), selected?.extension?.uppercase() ?: "—", enabled && selected != null) { close ->
                choices.filter { it.kind == selected?.kind && it.quality == selected.quality }.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice.extension.uppercase()) }, onClick = { onSelect(choice); close() })
                }
            }
            OutlinedCard(onClick = onFolder, enabled = enabled, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.destination), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (customFolder) stringResource(R.string.custom_folder) else "Downloads", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ▾")
                }
            }
            if (customFolder) TextButton(onDefaultFolder, enabled = enabled) { Text(stringResource(R.string.default_folder)) }
            Button(onStart, Modifier.fillMaxWidth().heightIn(min = 62.dp).testTag("download"), enabled = enabled && selected != null,
                shape = RoundedCornerShape(20.dp)) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.start), style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(stringResource(R.string.notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun qualityName(value: String) = if (value == "Original") stringResource(R.string.original) else value

@Composable private fun Selection(label: String, value: String, enabled: Boolean, items: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(onClick = { expanded = true }, enabled = enabled, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("  ▾")
            }
        }
        DropdownMenu(expanded, { expanded = false }) { items { expanded = false } }
    }
}

internal fun bytesLabel(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GiB", bytes.toDouble() / (1024L * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MiB", bytes.toDouble() / (1024 * 1024))
    bytes >= 1024 -> String.format(Locale.US, "%.1f KiB", bytes.toDouble() / 1024)
    else -> "$bytes B"
}

@Composable private fun TransferCard(state: TransferState, cancel: () -> Unit, open: () -> Unit) {
    val complete = state.phase == Phase.COMPLETE
    val color = if (complete) Color(0xFFEAF8F1) else Color.White
    Card(Modifier.fillMaxWidth().testTag("transfer"), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color), border = BorderStroke(1.dp, if (complete) Color(0xFFCEEBDD) else MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_download), null, Modifier.size(32.dp), tint = if (complete) Color(0xFF168348) else MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stringResource(phaseText(state.phase)), style = MaterialTheme.typography.titleMedium,
                        color = if (complete) Color(0xFF137B42) else MaterialTheme.colorScheme.onSurface)
                    Text(state.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.detail.isNotBlank()) Text(state.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.active) Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
                    if (state.percent == null) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 5.dp)
                    else {
                        CircularProgressIndicator(progress = { state.percent }, Modifier.fillMaxSize(), strokeWidth = 5.dp)
                        Text("${(state.percent * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (state.active) {
                if (state.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { state.percent }, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (state.bytes > 0) Text(bytesLabel(state.bytes) + (state.total?.let { " / ${bytesLabel(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        state.bytesPerSecond?.takeIf { it > 0 }?.let { Text("${bytesLabel(it)}/s", style = MaterialTheme.typography.bodySmall) }
                    }
                    OutlinedButton(cancel) { Text(stringResource(R.string.cancel)) }
                }
            }
            state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (complete) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(bytesLabel(state.bytes), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(open) { Text(stringResource(R.string.open)) }
            }
        }
    }
}
