package com.example.data.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import com.example.data.model.MediaPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class AudioRepeatMode {
    OFF,
    REPEAT_ALL,
    REPEAT_ONE
}

data class AudioTrackItem(
    val id: String,
    val title: String,
    val artist: String = "",
    val file: File? = null,
    val uri: Uri? = null,
    val formatBadge: String = ""
)

data class DragonAudioPlayerState(
    val isVisible: Boolean = false,
    val isExpanded: Boolean = false,
    val isPreparing: Boolean = false,
    val isPlaying: Boolean = false,
    val currentTrack: AudioTrackItem? = null,
    val queue: List<AudioTrackItem> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val repeatMode: AudioRepeatMode = AudioRepeatMode.OFF,
    val qualityPreset: AudioQualityPreset = AudioQualityPreset.HI_FI,
    val albumArt: Bitmap? = null,
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 0,
    val bitsPerSample: Int = 0,
    val errorMessage: String? = null
) {
    val hasPrevious: Boolean
        get() = queue.size > 1 && (currentIndex > 0 || repeatMode == AudioRepeatMode.REPEAT_ALL)

    val hasNext: Boolean
        get() = queue.size > 1 && (currentIndex < queue.lastIndex || repeatMode == AudioRepeatMode.REPEAT_ALL)

    val qualityBadgeText: String
        get() = buildString {
            val fmt = currentTrack?.formatBadge.orEmpty()
            if (fmt.isNotBlank()) append(fmt)
            if (sampleRateHz >= 48_000) {
                if (isNotEmpty()) append(" ")
                val khz = if (sampleRateHz % 1000 == 0) "${sampleRateHz / 1000}kHz" else String.format(Locale.US, "%.1fkHz", sampleRateHz / 1000f)
                append(khz)
            } else if (bitrateKbps > 0) {
                if (isNotEmpty()) append(" ")
                append("${bitrateKbps}k")
            }
        }
}

/**
 * High-fidelity, lightweight process-wide audio playback engine backing the minimalistic compact audio panel.
 * Integrates hardware-accelerated [DragonAudioDspEngine] (32-bit float DynamicsProcessing 10-band EQ,
 * multi-band clarity, true-peak anti-clipping limiter, and stereo soundstage), native un-resampled 1.0x
 * audio pass-through, anti-pop smooth volume ramping, headphone disconnect protection, and cover art extraction.
 */
object DragonAudioPlayerEngine {

    private val EXTRA_AUDIO_EXTENSIONS = setOf(
        ".3ga", ".mka", ".weba", ".oga", ".caf", ".ac3", ".m4b", ".m4p", ".mp2"
    )

    private val SPEED_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _stateFlow = MutableStateFlow(DragonAudioPlayerState())
    val stateFlow: StateFlow<DragonAudioPlayerState> = _stateFlow.asStateFlow()

    private val dspEngine = DragonAudioDspEngine()
    private var presetLoaded = false

    private var appContext: Context? = null
    private var mediaPlayer: MediaPlayer? = null
    private var positionTickerJob: Job? = null
    private var metadataJob: Job? = null
    private var fadeJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var resumeAfterTransientFocusLoss: Boolean = false
    private var prepareToken: Int = 0
    private var hasModifiedPlaybackSpeed: Boolean = false
    private var noisyReceiverRegistered: Boolean = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                resumeAfterTransientFocusLoss = false
                pauseInternal()
            }
        }
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeAfterTransientFocusLoss = false
                pauseInternal()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    fadeJob?.cancel()
                    runCatching { mediaPlayer?.setVolume(0.28f, 0.28f) }
                } else {
                    resumeAfterTransientFocusLoss = _stateFlow.value.isPlaying
                    pauseInternal()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.let { startSmoothVolumeRamp(it, from = 0.28f, to = 1.0f, durationMs = 140L) }
                if (resumeAfterTransientFocusLoss) {
                    resumeAfterTransientFocusLoss = false
                    resumeInternal()
                }
            }
        }
    }

    fun isAudioExtension(ext: String): Boolean {
        val clean = ext.trim().lowercase(Locale.US).removePrefix(".")
        if (clean.isEmpty()) return false
        val dotExt = ".$clean"
        return dotExt in MediaPlatform.AUDIO_EXTENSIONS || dotExt in EXTRA_AUDIO_EXTENSIONS
    }

    fun isAudioFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "")
        return isAudioExtension(ext)
    }

    fun isAudioFile(file: File): Boolean {
        if (!file.isFile) return false
        return isAudioFileName(file.name)
    }

    fun isAudioMimeOrName(mimeType: String?, fileName: String?): Boolean {
        if (mimeType?.startsWith("audio/", ignoreCase = true) == true) return true
        if (!fileName.isNullOrBlank() && isAudioFileName(fileName)) return true
        return false
    }

    private fun ensurePresetLoaded(context: Context) {
        if (!presetLoaded) {
            presetLoaded = true
            val saved = DragonAudioDspEngine.loadSavedPreset(context)
            _stateFlow.update { it.copy(qualityPreset = saved) }
        }
    }

    fun playFile(
        context: Context,
        file: File,
        siblingAudioFiles: List<File> = emptyList()
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        ensurePresetLoaded(ctx)

        val queueFiles = if (siblingAudioFiles.isNotEmpty()) {
            val normalized = siblingAudioFiles.filter { it.exists() && it.isFile }
            if (normalized.any { it.absolutePath == file.absolutePath }) normalized else listOf(file) + normalized
        } else {
            val parentFiles = runCatching {
                file.parentFile?.listFiles()
                    ?.filter { it.isFile && isAudioFileName(it.name) }
                    ?.sortedBy { it.name.lowercase(Locale.US) }
                    .orEmpty()
            }.getOrDefault(emptyList())
            if (parentFiles.any { it.absolutePath == file.absolutePath }) parentFiles else listOf(file)
        }

        val queue = queueFiles.map { f ->
            AudioTrackItem(
                id = f.absolutePath,
                title = f.nameWithoutExtension.ifBlank { f.name },
                file = f,
                formatBadge = f.extension.uppercase(Locale.US)
            )
        }
        val startIndex = queue.indexOfFirst { it.id == file.absolutePath }.coerceAtLeast(0)
        playQueueAt(ctx, queue, startIndex)
    }

    fun playUri(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        queue: List<AudioTrackItem> = emptyList(),
        startIndex: Int = 0
    ) {
        val ctx = context.applicationContext
        appContext = ctx
        ensurePresetLoaded(ctx)
        if (queue.isNotEmpty()) {
            playQueueAt(ctx, queue, startIndex.coerceIn(0, queue.lastIndex))
            return
        }
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: resolveDisplayName(ctx, uri)
        val ext = resolvedName.substringAfterLast('.', "").uppercase(Locale.US)
        val title = resolvedName.substringBeforeLast('.', resolvedName).ifBlank { "Audio Track" }
        val item = AudioTrackItem(
            id = uri.toString(),
            title = title,
            uri = uri,
            formatBadge = ext.takeIf { it.length in 2..5 } ?: "AUDIO"
        )
        playQueueAt(ctx, listOf(item), 0)
    }

    fun playQueueAt(context: Context, queue: List<AudioTrackItem>, index: Int) {
        if (queue.isEmpty()) return
        val ctx = context.applicationContext
        appContext = ctx
        ensurePresetLoaded(ctx)
        val safeIndex = index.coerceIn(0, queue.lastIndex)
        val track = queue[safeIndex]

        val myToken = ++prepareToken
        stopPositionTicker()
        releaseMediaPlayerOnly()

        _stateFlow.update { prev ->
            prev.copy(
                isVisible = true,
                isPreparing = true,
                isPlaying = false,
                currentTrack = track,
                queue = queue,
                currentIndex = safeIndex,
                positionMs = 0L,
                durationMs = 0L,
                albumArt = null,
                bitrateKbps = 0,
                sampleRateHz = 0,
                bitsPerSample = 0,
                errorMessage = null
            )
        }

        loadTrackMetadataAsync(ctx, track, myToken)

        scope.launch {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                withContext(Dispatchers.IO) {
                    val f = track.file
                    val u = track.uri
                    when {
                        f != null && f.exists() -> mp.setDataSource(f.absolutePath)
                        u != null -> mp.setDataSource(ctx, u)
                        else -> throw IllegalStateException("Audio source unavailable")
                    }
                    mp.prepare()
                }

                if (myToken != prepareToken) {
                    runCatching { mp.release() }
                    return@launch
                }

                mediaPlayer = mp
                hasModifiedPlaybackSpeed = false

                // Attach hardware-accelerated Hi-Fi DSP chain directly to this MediaPlayer's audio session
                val sessionId = runCatching { mp.audioSessionId }.getOrDefault(0)
                dspEngine.attachToSession(sessionId, _stateFlow.value.qualityPreset)

                mp.setOnCompletionListener {
                    handleTrackCompleted()
                }
                mp.setOnErrorListener { _, what, extra ->
                    _stateFlow.update {
                        it.copy(
                            isPreparing = false,
                            isPlaying = false,
                            errorMessage = "Playback error ($what/$extra)"
                        )
                    }
                    stopPositionTicker()
                    true
                }

                // Preserve native un-resampled audio path at 1.0x; only engage pitch-preserving timestretch if != 1.0x
                val targetSpeed = _stateFlow.value.playbackSpeed
                if (kotlin.math.abs(targetSpeed - 1.0f) >= 0.01f) {
                    applySpeedToPlayer(mp, targetSpeed)
                }

                requestAudioFocus(ctx)
                registerBecomingNoisyReceiver(ctx)

                // Soft anti-pop volume fade-in from 0.0 -> 1.0
                runCatching { mp.setVolume(0.05f, 0.05f) }
                mp.start()
                startSmoothVolumeRamp(mp, from = 0.05f, to = 1.0f, durationMs = 75L)

                val dur = runCatching { mp.duration.toLong().coerceAtLeast(0L) }.getOrDefault(0L)
                _stateFlow.update {
                    it.copy(
                        isPreparing = false,
                        isPlaying = true,
                        durationMs = if (dur > 0L) dur else it.durationMs,
                        positionMs = 0L,
                        errorMessage = null
                    )
                }
                startPositionTicker()
            } catch (e: Exception) {
                runCatching { mp.release() }
                if (myToken == prepareToken) {
                    _stateFlow.update {
                        it.copy(
                            isPreparing = false,
                            isPlaying = false,
                            errorMessage = e.message ?: "Unable to play this audio format"
                        )
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val state = _stateFlow.value
        if (state.isPreparing) return
        val mp = mediaPlayer
        if (mp == null) {
            val ctx = appContext ?: return
            if (state.queue.isNotEmpty() && state.currentIndex in state.queue.indices) {
                playQueueAt(ctx, state.queue, state.currentIndex)
            }
            return
        }
        if (state.isPlaying) {
            resumeAfterTransientFocusLoss = false
            pauseInternal()
        } else {
            resumeInternal()
        }
    }

    private fun pauseInternal() {
        fadeJob?.cancel()
        runCatching {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        }
        stopPositionTicker()
        _stateFlow.update { it.copy(isPlaying = false) }
    }

    private fun resumeInternal() {
        val ctx = appContext
        val mp = mediaPlayer ?: return
        if (ctx != null) {
            requestAudioFocus(ctx)
            registerBecomingNoisyReceiver(ctx)
        }
        runCatching {
            mp.setVolume(0.1f, 0.1f)
            mp.start()
            startSmoothVolumeRamp(mp, from = 0.1f, to = 1.0f, durationMs = 70L)
            _stateFlow.update { it.copy(isPlaying = true, errorMessage = null) }
            startPositionTicker()
        }
    }

    fun seekTo(targetMs: Long) {
        val mp = mediaPlayer ?: return
        val dur = _stateFlow.value.durationMs
        val clamped = targetMs.coerceIn(0L, if (dur > 0L) dur else targetMs.coerceAtLeast(0L))
        runCatching {
            val wasPlaying = mp.isPlaying
            if (wasPlaying) {
                mp.setVolume(0.15f, 0.15f)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mp.seekTo(clamped, MediaPlayer.SEEK_CLOSEST)
            } else {
                mp.seekTo(clamped.toInt())
            }
            if (wasPlaying) {
                startSmoothVolumeRamp(mp, from = 0.15f, to = 1.0f, durationMs = 60L)
            }
            _stateFlow.update { it.copy(positionMs = clamped) }
        }
    }

    fun seekBy(deltaMs: Long) {
        seekTo(_stateFlow.value.positionMs + deltaMs)
    }

    fun skipNext() {
        val ctx = appContext ?: return
        val state = _stateFlow.value
        if (state.queue.isEmpty()) return
        if (state.queue.size == 1) {
            seekBy(10_000L)
            return
        }
        val nextIndex = if (state.currentIndex + 1 < state.queue.size) {
            state.currentIndex + 1
        } else if (state.repeatMode == AudioRepeatMode.REPEAT_ALL) {
            0
        } else {
            return
        }
        playQueueAt(ctx, state.queue, nextIndex)
    }

    fun skipPrevious() {
        val ctx = appContext ?: return
        val state = _stateFlow.value
        if (state.queue.isEmpty()) return
        if (state.positionMs > 3_500L) {
            seekTo(0L)
            return
        }
        if (state.queue.size == 1) {
            seekBy(-10_000L)
            return
        }
        val prevIndex = if (state.currentIndex > 0) {
            state.currentIndex - 1
        } else if (state.repeatMode == AudioRepeatMode.REPEAT_ALL) {
            state.queue.lastIndex
        } else {
            0
        }
        playQueueAt(ctx, state.queue, prevIndex)
    }

    fun cyclePlaybackSpeed() {
        val current = _stateFlow.value.playbackSpeed
        val idx = SPEED_STEPS.indexOfFirst { kotlin.math.abs(it - current) < 0.05f }
        val nextSpeed = SPEED_STEPS[(idx + 1) % SPEED_STEPS.size]
        _stateFlow.update { it.copy(playbackSpeed = nextSpeed) }
        mediaPlayer?.let { mp -> applySpeedToPlayer(mp, nextSpeed) }
    }

    fun cycleQualityPreset() {
        val next = _stateFlow.value.qualityPreset.next()
        setQualityPreset(next)
    }

    fun setQualityPreset(preset: AudioQualityPreset) {
        _stateFlow.update { it.copy(qualityPreset = preset) }
        appContext?.let { DragonAudioDspEngine.savePreset(it, preset) }
        dspEngine.updatePreset(preset)
    }

    fun cycleRepeatMode() {
        val next = when (_stateFlow.value.repeatMode) {
            AudioRepeatMode.OFF -> AudioRepeatMode.REPEAT_ALL
            AudioRepeatMode.REPEAT_ALL -> AudioRepeatMode.REPEAT_ONE
            AudioRepeatMode.REPEAT_ONE -> AudioRepeatMode.OFF
        }
        _stateFlow.update { it.copy(repeatMode = next) }
    }

    fun toggleExpanded() {
        _stateFlow.update { it.copy(isExpanded = !it.isExpanded) }
    }

    fun setExpanded(expanded: Boolean) {
        _stateFlow.update { it.copy(isExpanded = expanded) }
    }

    fun stopAndClose() {
        prepareToken++
        metadataJob?.cancel()
        metadataJob = null
        fadeJob?.cancel()
        fadeJob = null
        stopPositionTicker()
        releaseMediaPlayerOnly()
        unregisterBecomingNoisyReceiver()
        abandonAudioFocus()
        resumeAfterTransientFocusLoss = false
        val savedPreset = _stateFlow.value.qualityPreset
        _stateFlow.value = DragonAudioPlayerState(qualityPreset = savedPreset)
    }

    private fun handleTrackCompleted() {
        val state = _stateFlow.value
        when (state.repeatMode) {
            AudioRepeatMode.REPEAT_ONE -> {
                seekTo(0L)
                resumeInternal()
            }
            AudioRepeatMode.REPEAT_ALL -> {
                if (state.queue.size > 1) {
                    val next = (state.currentIndex + 1) % state.queue.size
                    appContext?.let { playQueueAt(it, state.queue, next) }
                } else {
                    seekTo(0L)
                    resumeInternal()
                }
            }
            AudioRepeatMode.OFF -> {
                if (state.currentIndex + 1 < state.queue.size) {
                    appContext?.let { playQueueAt(it, state.queue, state.currentIndex + 1) }
                } else {
                    stopPositionTicker()
                    _stateFlow.update {
                        it.copy(
                            isPlaying = false,
                            positionMs = it.durationMs
                        )
                    }
                }
            }
        }
    }

    private fun applySpeedToPlayer(mp: MediaPlayer, speed: Float) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Only touch PlaybackParams if speed != 1.0f or if we previously modified it
            if (!hasModifiedPlaybackSpeed && kotlin.math.abs(speed - 1.0f) < 0.01f) return
            hasModifiedPlaybackSpeed = true
            runCatching {
                val wasPlaying = mp.isPlaying
                val params = runCatching { mp.playbackParams }.getOrElse { PlaybackParams() }
                params.speed = speed
                params.pitch = 1.0f
                params.audioFallbackMode = PlaybackParams.AUDIO_FALLBACK_MODE_DEFAULT
                mp.playbackParams = params
                if (!wasPlaying && mp.isPlaying) {
                    mp.pause()
                }
            }
        }
    }

    private fun startSmoothVolumeRamp(mp: MediaPlayer, from: Float, to: Float, durationMs: Long) {
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val steps = 5
            val stepDelay = (durationMs / steps).coerceAtLeast(10L)
            for (i in 1..steps) {
                if (mediaPlayer !== mp) return@launch
                val v = from + (to - from) * (i.toFloat() / steps.toFloat())
                runCatching { mp.setVolume(v, v) }
                if (i < steps) delay(stepDelay)
            }
        }
    }

    private fun startPositionTicker() {
        stopPositionTicker()
        positionTickerJob = scope.launch {
            while (isActive) {
                val mp = mediaPlayer
                if (mp != null) {
                    val pos = runCatching { mp.currentPosition.toLong().coerceAtLeast(0L) }.getOrNull()
                    val dur = runCatching { mp.duration.toLong().coerceAtLeast(0L) }.getOrNull()
                    val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                    if (pos != null) {
                        _stateFlow.update { prev ->
                            prev.copy(
                                positionMs = pos,
                                durationMs = if (dur != null && dur > 0L) dur else prev.durationMs,
                                isPlaying = playing
                            )
                        }
                    }
                    if (!playing) break
                } else {
                    break
                }
                delay(250L)
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = null
    }

    private fun releaseMediaPlayerOnly() {
        fadeJob?.cancel()
        fadeJob = null
        dspEngine.release()
        val old = mediaPlayer
        mediaPlayer = null
        if (old != null) {
            runCatching {
                if (old.isPlaying) old.stop()
            }
            runCatching { old.reset() }
            runCatching { old.release() }
        }
    }

    private fun registerBecomingNoisyReceiver(context: Context) {
        if (noisyReceiverRegistered) return
        runCatching {
            ContextCompat.registerReceiver(
                context.applicationContext,
                becomingNoisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            noisyReceiverRegistered = true
        }
    }

    private fun unregisterBecomingNoisyReceiver() {
        if (!noisyReceiverRegistered) return
        val ctx = appContext ?: return
        runCatching { ctx.unregisterReceiver(becomingNoisyReceiver) }
        noisyReceiverRegistered = false
    }

    private fun requestAudioFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            runCatching { am.requestAudioFocus(req) }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                am.requestAudioFocus(
                    audioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }
    }

    private fun abandonAudioFocus() {
        val ctx = appContext ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
        } else {
            @Suppress("DEPRECATION")
            runCatching { am.abandonAudioFocus(audioFocusListener) }
        }
    }

    private fun loadTrackMetadataAsync(context: Context, track: AudioTrackItem, token: Int) {
        metadataJob?.cancel()
        metadataJob = scope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val f = track.file
                val u = track.uri
                when {
                    f != null && f.exists() -> retriever.setDataSource(f.absolutePath)
                    u != null -> retriever.setDataSource(context, u)
                    else -> return@launch
                }
                val metaTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                val metaArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                        ?.trim()?.takeIf { it.isNotEmpty() }
                    ?: ""
                val bitrateBps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toIntOrNull() ?: 0
                val durationMeta = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                var sampleRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                } else 0
                val bitsPerSample = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
                } else 0

                if (sampleRate <= 0) {
                    sampleRate = extractSampleRateViaMediaExtractor(context, track)
                }

                val artBytes = runCatching { retriever.embeddedPicture }.getOrNull()
                val artBitmap = if (artBytes != null && artBytes.isNotEmpty()) {
                    decodeScaledArtwork(artBytes, 160)
                } else null

                withContext(Dispatchers.Main.immediate) {
                    if (token == prepareToken) {
                        _stateFlow.update { prev ->
                            val updatedTrack = prev.currentTrack?.copy(
                                title = metaTitle ?: prev.currentTrack.title,
                                artist = metaArtist
                            )
                            prev.copy(
                                currentTrack = updatedTrack,
                                albumArt = artBitmap,
                                bitrateKbps = if (bitrateBps > 0) (bitrateBps / 1000) else prev.bitrateKbps,
                                sampleRateHz = if (sampleRate > 0) sampleRate else prev.sampleRateHz,
                                bitsPerSample = if (bitsPerSample > 0) bitsPerSample else prev.bitsPerSample,
                                durationMs = if (prev.durationMs <= 0L && durationMeta > 0L) durationMeta else prev.durationMs
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                // Metadata is optional; filename fallback is already displayed
            } finally {
                runCatching { retriever.release() }
            }
        }
    }

    private fun extractSampleRateViaMediaExtractor(context: Context, track: AudioTrackItem): Int {
        val extractor = MediaExtractor()
        return try {
            val f = track.file
            val u = track.uri
            when {
                f != null && f.exists() -> extractor.setDataSource(f.absolutePath)
                u != null -> extractor.setDataSource(context, u, null)
                else -> return 0
            }
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        return format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                }
            }
            0
        } catch (_: Exception) {
            0
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeScaledArtwork(bytes: ByteArray, reqSizePx: Int): Bitmap? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while ((opts.outWidth / sample) > reqSizePx * 2 || (opts.outHeight / sample) > reqSizePx * 2) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
        }.getOrNull()
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "Audio Track" } ?: "Audio Track"
    }

    fun formatDuration(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
