package com.os73.dosos73

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Page(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}

@Composable
internal fun Brand(compact: Boolean = false) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (compact)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                BrandMark(Modifier.size(40.dp, 28.dp))
                Wordmark(25)
            }
        else
            Column(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandMark(Modifier.size(100.dp, 70.dp))
                Spacer(Modifier.height(7.dp))
                Wordmark(36)
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.app_name),
                    fontSize = 10.sp,
                    color = Neon.Secondary,
                    letterSpacing = 0.3.sp,
                )
            }
    }
}

@Composable
private fun Wordmark(size: Int) {
    val art = BrandArtwork.get(androidx.compose.ui.platform.LocalContext.current.resources)
    androidx.compose.foundation.Canvas(Modifier.size((size * 4.8f).dp, (size * 1.05f).dp)) {
        drawImage(
            art,
            srcOffset = androidx.compose.ui.unit.IntOffset(207, 710),
            srcSize = androidx.compose.ui.unit.IntSize(825, 157),
            dstSize =
                androidx.compose.ui.unit.IntSize(this.size.width.toInt(), this.size.height.toInt()),
            blendMode = androidx.compose.ui.graphics.BlendMode.Lighten,
        )
    }
}

@Composable
internal fun IconAction(
    kind: Glyph,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Neon.Secondary,
) {
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Symbol(kind, label, color = color)
    }
}

@Composable
internal fun GradientButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: Glyph? = null,
) {
    Button(
        onClick,
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (enabled) Brush.horizontalGradient(listOf(Neon.ActionBlue, Neon.Purple))
                else Brush.linearGradient(listOf(Neon.Raised, Neon.Raised))
            ),
        enabled = enabled,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                contentColor = Color.White,
            ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
    ) {
        icon?.let {
            Symbol(it, modifier = Modifier.size(20.dp), color = Color.White)
            Spacer(Modifier.width(7.dp))
        }
        Text(label)
    }
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Neon.Radius)
            .background(Neon.Surface)
            .border(1.dp, Neon.Line, Neon.Radius)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
internal fun EmptyState(icon: Glyph, title: Int, body: Int) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(72.dp).background(Neon.Surface, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(icon, modifier = Modifier.size(30.dp), color = Neon.Cyan)
        }
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(body),
            color = Neon.Secondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
internal fun HomeScreen(
    screen: ScreenState,
    transfer: TransferState,
    customFolder: Boolean,
    onEdit: (String) -> Unit,
    onSelect: (Choice) -> Unit,
    onAnalyze: () -> Unit,
    onCancelAnalysis: () -> Unit,
    onPaste: () -> Unit,
    onSettings: () -> Unit,
    onFiles: () -> Unit,
    onAudio: () -> Unit,
    onVideo: () -> Unit,
    onFolder: () -> Unit,
    onDefaultFolder: () -> Unit,
    onStart: () -> Unit,
    onSource: (String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Page {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconAction(
                Glyph.SETTINGS,
                stringResource(R.string.settings),
                onSettings,
                Modifier.testTag("settings"),
            )
        }
        Brand()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                Modifier.fillMaxWidth()
                    .heightIn(min = 62.dp)
                    .clip(RoundedCornerShape(34.dp))
                    .background(Brush.horizontalGradient(listOf(Color(0xFF173656), Neon.Raised)))
                    .border(1.dp, Color(0xFF1C4665), RoundedCornerShape(34.dp))
                    .padding(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconAction(
                    Glyph.LINK,
                    stringResource(R.string.paste),
                    onPaste,
                    Modifier.testTag("paste"),
                    Color(0xFFD4E5FF),
                )
                BasicTextField(
                    screen.url,
                    onEdit,
                    modifier = Modifier.weight(1f).padding(horizontal = 5.dp).testTag("url"),
                    singleLine = true,
                    enabled = !screen.busy,
                    textStyle =
                        TextStyle(
                            color = Neon.Text,
                            fontSize = 14.sp,
                            textDirection = TextDirection.Ltr,
                        ),
                    cursorBrush = Brush.linearGradient(listOf(Neon.Cyan, Neon.Cyan)),
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onAnalyze() }),
                    decorationBox = { inner ->
                        if (screen.url.isEmpty())
                            Text(
                                stringResource(R.string.url_placeholder),
                                color = Neon.Secondary,
                                fontSize = 14.sp,
                                textAlign = TextAlign.End,
                            )
                        inner()
                    },
                )
                GradientButton(
                    stringResource(
                        if (screen.analysis != null) R.string.start_short
                        else R.string.download_action
                    ),
                    if (screen.analysis != null) onStart else onAnalyze,
                    Modifier.testTag(if (screen.analysis != null) "download" else "analyze"),
                    !screen.busy,
                    Glyph.DOWNLOAD,
                )
            }
        }
        if (screen.url.isNotEmpty())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onEdit("") }, enabled = !screen.busy) {
                    Text(stringResource(R.string.clear))
                }
            }
        if (screen.busy) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth(),
                color = Neon.Cyan,
                trackColor = Neon.Raised,
            )
            TextButton(onCancelAnalysis) { Text(stringResource(R.string.cancel)) }
        }
        screen.error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        screen.analysis?.let { analysis ->
            Panel {
                Text(
                    analysis.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(Kind.VIDEO, Kind.AUDIO, Kind.FILE)
                        .filter { kind -> analysis.choices.any { it.kind == kind } }
                        .forEach { kind ->
                            NeonChip(
                                selected = screen.selected?.kind == kind,
                                onClick = {
                                    analysis.choices.firstOrNull { it.kind == kind }?.let(onSelect)
                                },
                                label = {
                                    Text(
                                        stringResource(
                                            when (kind) {
                                                Kind.VIDEO -> R.string.video
                                                Kind.AUDIO -> R.string.audio
                                                else -> R.string.file
                                            }
                                        )
                                    )
                                },
                            )
                        }
                }
                ChoiceMenu(
                    stringResource(R.string.quality),
                    screen.selected
                        ?.quality
                        ?.let { if (it == "Original") stringResource(R.string.original) else it }
                        .orEmpty(),
                ) { close ->
                    analysis.choices
                        .filter { it.kind == screen.selected?.kind }
                        .distinctBy { it.quality }
                        .forEach { choice ->
                            DropdownMenuItem(
                                {
                                    Text(
                                        if (choice.quality == "Original")
                                            stringResource(R.string.original)
                                        else choice.quality
                                    )
                                },
                                {
                                    onSelect(choice)
                                    close()
                                },
                            )
                        }
                }
                ChoiceMenu(
                    stringResource(R.string.format),
                    screen.selected?.extension?.uppercase().orEmpty(),
                ) { close ->
                    analysis.choices
                        .filter {
                            it.kind == screen.selected?.kind &&
                                it.quality == screen.selected.quality
                        }
                        .forEach { choice ->
                            DropdownMenuItem(
                                { Text(choice.extension.uppercase()) },
                                {
                                    onSelect(choice)
                                    close()
                                },
                            )
                        }
                }
                Text(
                    stringResource(
                        R.string.file_size_value,
                        analysis.sizeBytes?.let { bytesLabel(it, locale) }
                            ?: stringResource(R.string.unknown_size),
                    ),
                    color = Neon.Secondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onFolder) {
                    Symbol(Glyph.FOLDER)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            if (customFolder) R.string.custom_folder else R.string.default_folder
                        )
                    )
                }
                if (customFolder)
                    TextButton(onDefaultFolder) { Text(stringResource(R.string.default_folder)) }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("YouTube", "TikTok", "Instagram", "X").forEach { name ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(49.dp)
                                .clip(CircleShape)
                                .background(Neon.Surface)
                                .clickable { onSource(name) },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (name) {
                                "YouTube" ->
                                    Box(
                                        Modifier.size(29.dp, 22.dp)
                                            .background(
                                                Color(0xFFFF163D),
                                                RoundedCornerShape(7.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Symbol(
                                            Glyph.PLAY,
                                            modifier = Modifier.size(15.dp),
                                            color = Color.White,
                                        )
                                    }
                                "Instagram" ->
                                    Box(
                                        Modifier.size(28.dp)
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(
                                                        Color(0xFFB22BFA),
                                                        Color(0xFFFF395E),
                                                        Color(0xFFFFC46B),
                                                    )
                                                ),
                                                RoundedCornerShape(8.dp),
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Symbol(
                                            Glyph.IMAGE,
                                            modifier = Modifier.size(20.dp),
                                            color = Color.White,
                                        )
                                    }
                                "TikTok" ->
                                    Symbol(
                                        Glyph.AUDIO,
                                        modifier = Modifier.size(27.dp),
                                        color = Neon.Cyan,
                                    )
                                else ->
                                    Text(
                                        "X",
                                        fontSize = 25.sp,
                                        color = Neon.Cyan,
                                        fontWeight = FontWeight.Medium,
                                    )
                            }
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(name, fontSize = 11.sp)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconAction(
                        Glyph.MORE,
                        stringResource(R.string.more),
                        onSettings,
                        Modifier.background(Neon.Surface, CircleShape),
                    )
                    Text(stringResource(R.string.more), fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.quick_tools),
            color = Neon.Cyan,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile(
                R.string.extract_audio,
                R.string.from_video,
                Glyph.VIDEO,
                Neon.Purple,
                onAudio,
                Modifier.weight(1f),
            )
            ToolTile(
                R.string.download_audio,
                R.string.from_media,
                Glyph.AUDIO,
                Color(0xFF0087A8),
                onAudio,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolTile(
                R.string.settings,
                R.string.customize_app,
                Glyph.SETTINGS,
                Color(0xFF00A787),
                onSettings,
                Modifier.weight(1f),
            )
            ToolTile(
                R.string.saved_downloads,
                R.string.manage_files,
                Glyph.LINK,
                Neon.Purple,
                onFiles,
                Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.notice),
            style = MaterialTheme.typography.labelSmall,
            color = Neon.Muted,
        )
    }
}

@Composable
private fun ToolTile(
    title: Int,
    subtitle: Int,
    icon: Glyph,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .heightIn(min = 115.dp)
            .clip(Neon.Radius)
            .background(Brush.linearGradient(listOf(tint.copy(alpha = 0.23f), Neon.Surface)))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.align(Alignment.End)
                .size(38.dp)
                .background(
                    Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.45f))),
                    RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(icon, color = Color.White)
        }
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(subtitle),
            style = MaterialTheme.typography.labelSmall,
            color = Neon.Secondary,
        )
    }
}

@Composable
internal fun ChoiceMenu(label: String, value: String, items: @Composable (() -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            { expanded = true },
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text(label, Modifier.weight(1f))
            Text(value)
            Text(stringResource(R.string.dropdown_indicator))
        }
        DropdownMenu(expanded, { expanded = false }) { items { expanded = false } }
    }
}

@Composable
internal fun TransferCard(
    state: TransferState,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onRetry: () -> Unit,
    onDetails: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Panel(Modifier.testTag("transfer")) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier.size(58.dp, 72.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Neon.Purple.copy(alpha = 0.5f), Neon.Raised)
                            )
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(
                        if (Transfers.currentTask?.choice?.kind == Kind.AUDIO) Glyph.AUDIO
                        else Glyph.VIDEO,
                        modifier = Modifier.size(29.dp),
                        color = Neon.Cyan,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        state.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (state.active) {
                        if (state.percent != null)
                            LinearProgressIndicator(
                                progress = { state.percent },
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                                color = Neon.Cyan,
                                trackColor = Neon.Raised,
                            )
                        else
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape),
                                color = Neon.Cyan,
                                trackColor = Neon.Raised,
                            )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (state.bytes > 0)
                                state.total?.let {
                                    stringResource(
                                        R.string.transfer_amount,
                                        bytesLabel(state.bytes, locale),
                                        bytesLabel(it, locale),
                                    )
                                } ?: bytesLabel(state.bytes, locale)
                            else state.detail,
                            color = Neon.Secondary,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        state.bytesPerSecond
                            ?.takeIf { it > 0 && state.phase != Phase.PAUSED }
                            ?.let {
                                Text(
                                    stringResource(R.string.speed_value, bytesLabel(it, locale)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Neon.Secondary,
                                )
                            }
                    }
                }
                if (
                    state.phase in setOf(Phase.DOWNLOADING, Phase.PAUSED) &&
                        Transfers.currentTask?.analysis?.direct == true
                )
                    IconAction(
                        if (state.phase == Phase.PAUSED) Glyph.PLAY else Glyph.PAUSE,
                        stringResource(
                            if (state.phase == Phase.PAUSED) R.string.resume else R.string.pause
                        ),
                        onPause,
                    )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(phaseText(state.phase)),
                style = MaterialTheme.typography.bodySmall,
                color = if (state.phase == Phase.COMPLETE) Color(0xFF25D6A2) else Neon.Secondary,
            )
            Row {
                IconAction(Glyph.INFO, stringResource(R.string.details), onDetails)
                if (state.active) IconAction(Glyph.CLOSE, stringResource(R.string.cancel), onCancel)
                if (state.phase == Phase.COMPLETE)
                    TextButton(onOpen) { Text(stringResource(R.string.open)) }
                if (state.phase in setOf(Phase.FAILED, Phase.CANCELLED))
                    TextButton(onRetry) { Text(stringResource(R.string.retry)) }
            }
        }
        state.error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun NeonChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        leadingIcon = leadingIcon,
        shape = RoundedCornerShape(28.dp),
        border = null,
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = Neon.Surface,
                selectedContainerColor = Neon.ActionBlue,
                labelColor = Neon.Secondary,
                selectedLabelColor = Color.White,
                iconColor = Neon.Secondary,
                selectedLeadingIconColor = Color.White,
            ),
    )
}
