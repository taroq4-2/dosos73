package com.os73.dosos73

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.CancellationSignal
import android.os.IBinder
import android.os.PowerManager
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class PlaybackPhase { PREPARING, PLAYING, PAUSED, SUSPENDED, FINISHED, ERROR }

internal data class LocalPlaybackState(val file: SavedFile, val phase: PlaybackPhase, val duration: Int = 0,
    val position: Int = 0, val videoWidth: Int = 0, val videoHeight: Int = 0) {
    val active get() = phase != PlaybackPhase.FINISHED && phase != PlaybackPhase.ERROR
}

internal object LocalPlayback {
    private val updates = MutableStateFlow<LocalPlaybackState?>(null)
    val state = updates.asStateFlow()
    private var service: LocalPlaybackService? = null
    private data class VideoOutput(val uri: String, val owner: Any, val surface: Surface)
    private var output: VideoOutput? = null

    fun allowed(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "content" || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return false
        val path = uri.pathSegments
        val localRow = when (uri.authority) {
            "media" -> isLocalVolume(path.firstOrNull()) &&
                path.lastOrNull()?.toLongOrNull()?.let { it > 0 } == true &&
                (path.size == 3 && path[1] in setOf("downloads", "file") ||
                    path.size == 4 && path[1] in setOf("audio", "video", "images") && path[2] == "media")
            "com.android.externalstorage.documents" -> DocumentsContract.isDocumentUri(context, uri)
            else -> false
        }
        if (!localRow) return false
        val flags = context.packageManager.resolveContentProvider(requireNotNull(uri.authority), 0)?.applicationInfo?.flags ?: return false
        return flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }

    private val removableVolume = Regex("[a-fA-F0-9]{4}-[a-fA-F0-9]{4}")
    private fun isLocalVolume(name: String?) = name in setOf("external", "external_primary", "internal") ||
        name?.matches(removableVolume) == true

    fun start(context: Context, file: SavedFile): Boolean {
        if (file.kind !in setOf(Kind.AUDIO, Kind.VIDEO) || !allowed(context, Uri.parse(file.uri))) return false
        return try {
            ContextCompat.startForegroundService(context, Intent(context, LocalPlaybackService::class.java)
                .putExtra("uri", file.uri).putExtra("name", file.name.take(240)).putExtra("mime", file.mime)
                .putExtra("position", updates.value?.takeIf { it.file.uri == file.uri &&
                    it.phase == PlaybackPhase.SUSPENDED }?.position ?: 0))
            true
        } catch (_: IllegalStateException) { false }
        catch (_: SecurityException) { false }
    }

    fun attach(owner: LocalPlaybackService) { service = owner }
    fun detach(owner: LocalPlaybackService) { if (service === owner) service = null }
    fun publish(state: LocalPlaybackState?) { updates.value = state }
    fun pause() { service?.pause() }
    fun resume(context: Context) {
        val bookmark = updates.value?.takeIf { it.phase == PlaybackPhase.SUSPENDED }
        if (bookmark != null) {
            if (!start(context, bookmark.file)) updates.value = bookmark.copy(phase = PlaybackPhase.ERROR)
        } else service?.play()
    }
    fun stop() { if (service != null) service?.finish() else updates.value = null }
    fun seek(position: Int) {
        val bookmark = updates.value?.takeIf { it.phase == PlaybackPhase.SUSPENDED }
        if (bookmark != null) updates.value = bookmark.copy(position = position.coerceIn(0, bookmark.duration))
        else service?.seek(position)
    }
    fun position(): Int = updates.value?.takeIf { it.phase == PlaybackPhase.SUSPENDED }?.position
        ?: service?.position() ?: 0
    internal fun hasService() = service != null
    fun videoSurface(uri: String): Surface? = output?.takeIf { it.uri == uri && it.surface.isValid }?.surface

    fun attachVideo(uri: String, owner: Any, surface: Surface) {
        output = VideoOutput(uri, owner, surface)
        service?.updateSurface()
    }

    fun detachVideo(owner: Any) {
        if (output?.owner !== owner) return
        output = null
        service?.updateSurface()
    }
}

class LocalPlaybackService : Service() {
    companion object {
        @Volatile internal var pauseTimeoutMillisForTests: Long? = null
        internal const val ID = 76
        private const val CHANNEL = "local_playback"
        private const val TOGGLE = "com.os73.dosos73.PLAYBACK_TOGGLE"
        private const val STOP = "com.os73.dosos73.PLAYBACK_STOP"
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private lateinit var session: MediaSession
    private var player: MediaPlayer? = null
    private var current: LocalPlaybackState? = null
    private var opening: Job? = null
    private var sourceCancellation: CancellationSignal? = null
    private var idleTimeout: Job? = null
    private var focusHeld = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(audioAttributes).setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS) finish()
            if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) pause()
        }.build()
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { pause() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        LocalPlayback.attach(this)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "dosos73.local").apply {
            setSessionActivity(openApp())
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = play()
                override fun onPause() = pause()
                override fun onStop() = finish()
                override fun onSeekTo(pos: Long) = seek(pos.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            })
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            TOGGLE -> if (current?.phase == PlaybackPhase.PLAYING) pause() else play()
            STOP -> finish()
            else -> {
                val uri = intent?.getStringExtra("uri")
                val name = intent?.getStringExtra("name")
                val mime = intent?.getStringExtra("mime")
                if (uri == null || name == null || mime == null) finish()
                else load(SavedFile(uri, name, mime, 0, 0), intent.getIntExtra("position", 0))
            }
        }
        return START_NOT_STICKY
    }

    private fun load(file: SavedFile, resumePosition: Int) {
        releasePlayback()
        current = LocalPlaybackState(file, PlaybackPhase.PREPARING)
        val uri = Uri.parse(file.uri)
        try {
            startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            session.isActive = true
            session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, file.name).build())
            update(PlaybackPhase.PREPARING)
        } catch (_: RuntimeException) {
            finish(PlaybackPhase.ERROR)
            return
        }
        val signal = CancellationSignal()
        sourceCancellation = signal
        idleTimeout = scope.launch { delay(30_000); finish(PlaybackPhase.ERROR) }
        opening = scope.launch {
            var input: AssetFileDescriptor? = null
            try {
                withContext(Dispatchers.IO) {
                    require(file.kind in setOf(Kind.VIDEO, Kind.AUDIO) && LocalPlayback.allowed(this@LocalPlaybackService, uri))
                    input = contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    check(OsConstants.S_ISREG(Os.fstat(checkNotNull(input).fileDescriptor).st_mode))
                }
                val media = MediaPlayer()
                player = media
                media.setAudioAttributes(audioAttributes)
                media.setWakeMode(this@LocalPlaybackService, PowerManager.PARTIAL_WAKE_LOCK)
                media.setDataSource(checkNotNull(input))
                media.setOnVideoSizeChangedListener { source, width, height ->
                    if (player === source && width > 0 && height > 0) {
                        current = current?.copy(videoWidth = width, videoHeight = height)
                        LocalPlayback.publish(current)
                    }
                }
                media.setOnPreparedListener { prepared ->
                    if (player === prepared) {
                        val duration = prepared.duration.coerceAtLeast(0)
                        current = current?.copy(duration = duration)
                        session.setMetadata(MediaMetadata.Builder()
                            .putString(MediaMetadata.METADATA_KEY_TITLE, file.name)
                            .putLong(MediaMetadata.METADATA_KEY_DURATION, duration.toLong()).build())
                        update(PlaybackPhase.PAUSED)
                        if (resumePosition in 1 until duration) {
                            prepared.setOnSeekCompleteListener {
                                if (player === it) { it.setOnSeekCompleteListener(null); play() }
                            }
                            prepared.seekTo(resumePosition.toLong(), MediaPlayer.SEEK_CLOSEST)
                        } else play()
                    }
                }
                media.setOnCompletionListener { if (player === it) finish(PlaybackPhase.FINISHED) }
                media.setOnErrorListener { failed, _, _ ->
                    if (player === failed) finish(PlaybackPhase.ERROR)
                    true
                }
                updateSurface()
                media.prepareAsync()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { finish(PlaybackPhase.ERROR) }
            finally { runCatching { input?.close() } }
        }
    }

    internal fun updateSurface() {
        val file = current?.file ?: return
        if (file.kind == Kind.VIDEO) runCatching { player?.setSurface(LocalPlayback.videoSurface(file.uri)) }
    }

    internal fun play() {
        val media = player ?: return
        if (current?.phase != PlaybackPhase.PAUSED) return
        try {
            focusHeld = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!focusHeld) {
                finish(PlaybackPhase.ERROR)
                return
            }
            idleTimeout?.cancel()
            media.start()
            update(PlaybackPhase.PLAYING)
        } catch (_: RuntimeException) { finish(PlaybackPhase.ERROR) }
    }

    internal fun pause() {
        if (current?.phase != PlaybackPhase.PLAYING) return
        try {
            player?.pause()
            abandonFocus()
            update(PlaybackPhase.PAUSED)
            val timeout = if (BuildConfig.DEBUG) pauseTimeoutMillisForTests ?: 60_000 else 60_000
            idleTimeout = scope.launch { delay(timeout); suspendPlayback() }
        } catch (_: RuntimeException) { finish(PlaybackPhase.ERROR) }
    }

    internal fun seek(position: Int) {
        val state = current ?: return
        if (state.phase == PlaybackPhase.PLAYING || state.phase == PlaybackPhase.PAUSED) {
            runCatching { player?.seekTo(position.coerceIn(0, state.duration).toLong(), MediaPlayer.SEEK_CLOSEST) }
            update(state.phase)
        }
    }

    internal fun position(): Int = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** Retain one small process-local bookmark, with no player, service or wake lock. */
    private fun suspendPlayback() {
        val bookmark = current?.copy(phase = PlaybackPhase.SUSPENDED, position = position()) ?: return
        current = null
        releasePlayback()
        LocalPlayback.publish(bookmark)
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun update(phase: PlaybackPhase) {
        val next = current?.copy(phase = phase) ?: return
        current = next
        LocalPlayback.publish(next)
        val state = when (phase) {
            PlaybackPhase.PREPARING -> PlaybackState.STATE_BUFFERING
            PlaybackPhase.PLAYING -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(state, position().toLong(), if (phase == PlaybackPhase.PLAYING) 1f else 0f).build())
        notifications.notify(ID, notification())
    }

    internal fun finish(phase: PlaybackPhase? = null) {
        LocalPlayback.publish(if (phase == null) null else current?.copy(phase = phase))
        current = null
        releasePlayback()
        session.isActive = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun abandonFocus() {
        if (focusHeld) audio.abandonAudioFocusRequest(focusRequest)
        focusHeld = false
    }

    private fun releasePlayback() {
        sourceCancellation?.cancel()
        sourceCancellation = null
        opening?.cancel()
        opening = null
        idleTimeout?.cancel()
        idleTimeout = null
        player?.release()
        player = null
        abandonFocus()
    }

    override fun onDestroy() {
        if (current != null) LocalPlayback.publish(null)
        releasePlayback()
        unregisterReceiver(noisy)
        session.release()
        scope.cancel()
        LocalPlayback.detach(this)
        super.onDestroy()
    }

    private fun openApp() = PendingIntent.getActivity(this, ID, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun notification(): Notification {
        val playing = current?.phase == PlaybackPhase.PLAYING
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_quick_download)
            .setContentTitle(current?.file?.name)
            .setContentText(getString(R.string.playback_channel))
            .setContentIntent(openApp())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
        fun action(command: String, icon: Int, label: Int, request: Int) = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon), getString(label),
            PendingIntent.getService(this, request, Intent(this, LocalPlaybackService::class.java).setAction(command), PendingIntent.FLAG_IMMUTABLE)
        ).build()
        val style = Notification.MediaStyle().setMediaSession(session.sessionToken)
        if (current?.phase != PlaybackPhase.PREPARING) {
            builder.addAction(action(TOGGLE, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) R.string.pause else R.string.playback_play, 7601))
            style.setShowActionsInCompactView(0, 1)
        } else style.setShowActionsInCompactView(0)
        return builder.addAction(action(STOP, android.R.drawable.ic_menu_close_clear_cancel, R.string.playback_stop, 7602))
            .setStyle(style).build()
    }

}
