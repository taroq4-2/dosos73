package com.os73.dosos73

import android.graphics.ImageDecoder
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
internal fun OfflinePreview(item: SavedFile, onDismiss: () -> Unit, onExternal: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(item.uri) { Uri.parse(item.uri) }
    val state by LocalPlayback.state.collectAsStateWithLifecycle()
    val playback = state?.takeIf { it.file.uri == item.uri }
    val local = remember(context, uri) { LocalPlayback.allowed(context, uri) }
    val playable = item.kind == Kind.AUDIO || item.kind == Kind.VIDEO
    val image = item.mime.startsWith("image/")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.preview_local), style = MaterialTheme.typography.bodySmall)
                when {
                    image && local -> ImagePreview(uri)
                    playable && local -> {
                        Text(stringResource(R.string.playback_background), style = MaterialTheme.typography.bodySmall)
                        if (item.kind == Kind.VIDEO) LocalVideoSurface(item.uri, playback)
                        PlaybackControls(item, playback)
                    }
                    image || playable -> Text(stringResource(R.string.playback_local_required))
                    else -> Text(stringResource(R.string.preview_unsupported))
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        dismissButton = { TextButton({
            if (playback?.active == true) LocalPlayback.stop()
            onExternal()
        }) { Text(stringResource(R.string.open_external)) } },
    )
}

@Composable
private fun ImagePreview(uri: Uri) {
    val context = LocalContext.current
    var complete by remember(uri) { mutableStateOf(false) }
    val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    val scale = minOf(1f, 1024f / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }.getOrNull()
        }
        complete = true
    }
    val result = bitmap
    when {
        result != null -> Image(result.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 320.dp))
        complete -> Text(stringResource(R.string.preview_unsupported))
        else -> CircularProgressIndicator()
    }
}

@Composable
private fun LocalVideoSurface(uri: String, playback: LocalPlaybackState?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val token = remember(uri) { Any() }
    var holder by remember(uri) { mutableStateOf<SurfaceHolder?>(null) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(240.dp).background(Neon.Background),
        contentAlignment = Alignment.Center) {
    val ratio = if (playback != null && playback.videoWidth > 0 && playback.videoHeight > 0)
        playback.videoWidth.toFloat() / playback.videoHeight else 16f / 9f
    val width = minOf(maxWidth, maxHeight * ratio)
    AndroidView(
        factory = { context -> SurfaceView(context).apply {
            this.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(created: SurfaceHolder) { holder = created }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(destroyed: SurfaceHolder) {
                    if (holder === destroyed) holder = null
                    LocalPlayback.detachVideo(token)
                }
            })
        } },
        modifier = Modifier.size(width, width / ratio),
    )
    }
    DisposableEffect(uri, lifecycle, holder) {
        fun attach() {
            holder?.surface?.takeIf { it.isValid }?.let { LocalPlayback.attachVideo(uri, token, it) }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> attach()
                Lifecycle.Event.ON_PAUSE -> LocalPlayback.detachVideo(token)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            LocalPlayback.detachVideo(token)
        }
    }
}

@Composable
private fun PlaybackControls(item: SavedFile, playback: LocalPlaybackState?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var failed by remember(item.uri) { mutableStateOf(false) }
    var position by remember(item.uri) { mutableFloatStateOf(0f) }
    var dragged by remember(item.uri) { mutableStateOf<Float?>(null) }
    val active = playback?.active == true
    val playing = playback?.phase == PlaybackPhase.PLAYING
    val preparing = playback?.phase == PlaybackPhase.PREPARING
    LaunchedEffect(lifecycle, item.uri, playback?.phase) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            position = if (active) LocalPlayback.position().toFloat() else 0f
            while (playing) {
                delay(500)
                position = LocalPlayback.position().toFloat()
            }
        }
    }
    if (preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
    val duration = playback?.duration?.takeIf { active && it > 0 }
    if (duration != null) {
        val elapsed = playbackTime((dragged ?: position).toInt())
        val total = playbackTime(duration)
        val label = stringResource(R.string.playback_seek)
        val time = stringResource(R.string.playback_time, elapsed, total)
        Text(time, style = MaterialTheme.typography.bodySmall)
        Slider((dragged ?: position).coerceIn(0f, duration.toFloat()), { dragged = it },
            modifier = Modifier.testTag("playback_seek").semantics {
                contentDescription = label
                stateDescription = time
            },
            valueRange = 0f..duration.toFloat(), onValueChangeFinished = {
                dragged?.let { LocalPlayback.seek(it.toInt()); position = it }
                dragged = null
            })
    }
    if (failed || playback?.phase == PlaybackPhase.ERROR) Text(stringResource(R.string.preview_unsupported))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button({
            failed = false
            when {
                playing -> LocalPlayback.pause()
                active -> LocalPlayback.resume(context)
                else -> failed = !LocalPlayback.start(context, item)
            }
        }, enabled = !preparing) {
            Text(stringResource(if (playing) R.string.pause else R.string.playback_play))
        }
        if (active) TextButton(LocalPlayback::stop) { Text(stringResource(R.string.playback_stop)) }
    }
}

@Composable
internal fun NowPlaying(onOpen: (SavedFile) -> Unit) {
    val context = LocalContext.current
    val state by LocalPlayback.state.collectAsStateWithLifecycle()
    val playback = state?.takeIf { it.active } ?: return
    val playing = playback.phase == PlaybackPhase.PLAYING
    Surface(color = Neon.Surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ onOpen(playback.file) }, Modifier.weight(1f)) {
                Text(playback.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playback.phase != PlaybackPhase.PREPARING)
                IconAction(if (playing) Glyph.PAUSE else Glyph.PLAY,
                    stringResource(if (playing) R.string.pause else R.string.playback_play),
                    if (playing) LocalPlayback::pause else { { LocalPlayback.resume(context) } })
            IconAction(Glyph.CLOSE, stringResource(R.string.playback_stop), LocalPlayback::stop)
        }
    }
}

internal fun playbackTime(milliseconds: Int): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
