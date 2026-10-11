package io.github.jqssun.airplay.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Media3-backed player used only by the DLNA session. */
class DlnaMediaPlayer(private val context: Context) {
    interface Listener {
        fun onSnapshot(snapshot: DlnaPlayerSnapshot)
        fun onFirstVideoFrame()
        fun onVideoSize(width: Int, height: Int)
        fun onImageReady(bitmap: Bitmap)
        fun onArtworkReady(bitmap: Bitmap)
        fun onLyricsReady(text: String)
        fun onEnded()
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var videoPipeline: VideoPipeline? = null
    private var videoPreview: Bitmap? = null
    private var directVideoSurface: Surface? = null
    private var firstVideoFrameReady = false
    private var playRequested = false
    private var media: DlnaMedia? = null
    private var image: Bitmap? = null
    private var state = "NO_MEDIA"
    private var volume = 100
    private var muted = false
    private var pendingSeekMs: Long? = null
    private val generation = AtomicInteger(0)
    private var listener: Listener? = null
    @Volatile private var latestSnapshot = DlnaPlayerSnapshot()

    private val tick = object : Runnable {
        override fun run() {
            publish()
            if (player != null) main.postDelayed(this, 500L)
        }
    }

    fun setListener(value: Listener?) {
        listener = value
    }

    fun setMedia(media: DlnaMedia) = main.post {
        loadMedia(media, autoplay = false)
    }

    fun play(media: DlnaMedia) = main.post {
        loadMedia(media, autoplay = true)
    }

    private fun loadMedia(media: DlnaMedia, autoplay: Boolean) {
        val token = generation.incrementAndGet()
        stopInternal(notify = false)
        this.media = media
        this.image = null
        playRequested = autoplay
        state = "TRANSITIONING"
        listener?.onLyricsReady("")
        publish()
        when (media.type) {
            DlnaMediaType.IMAGE -> loadImage(media, token)
            DlnaMediaType.VIDEO -> preparePlayer(media, autoplay)
            DlnaMediaType.AUDIO -> {
                preparePlayer(media, autoplay)
                if (media.artUri.isNotBlank()) loadArtwork(media, token)
                when {
                    media.lyrics.isNotBlank() -> listener?.onLyricsReady(media.lyrics)
                    media.lyricsUri.isNotBlank() -> loadLyrics(media, token)
                }
            }
        }
    }

    fun resume() = main.post {
        playRequested = true
        if (media?.type == DlnaMediaType.IMAGE) {
            state = if (image != null) "PLAYING" else "TRANSITIONING"
            publish()
            return@post
        }
        player?.play()
        if (player != null) state = if (player?.playbackState == Player.STATE_READY) "PLAYING" else "TRANSITIONING"
        publish()
    }

    fun pause() = main.post {
        playRequested = false
        player?.pause()
        if (player != null) state = "PAUSED_PLAYBACK"
        publish()
    }

    fun stop() = main.post {
        generation.incrementAndGet()
        stopInternal(notify = true)
    }

    fun seek(positionMs: Long) = main.post {
        val duration = resolvedDurationMs()
        val target = positionMs.coerceAtLeast(0L).let { value ->
            if (duration > 0L) value.coerceAtMost(duration) else value
        }
        val built = player
        if (built != null) {
            built.seekTo(target)
        } else if (media?.type == DlnaMediaType.AUDIO || media?.type == DlnaMediaType.VIDEO) {
            // A sender may issue the initial REL_TIME=00:00:00 immediately
            // after SetAVTransportURI, before ExoPlayer has been created.
            // Keep it until the player is prepared so a later seek is never
            // silently discarded during the transition window.
            pendingSeekMs = target
        }
        Log.i(TAG, "DLNA seek position=$target duration=$duration playerReady=${built != null}")
        publish()
    }

    /** Seek relative to the player's current position on the player thread. */
    fun seekBy(deltaMs: Long) = main.post {
        val built = player ?: return@post
        val current = built.currentPosition
        val duration = resolvedDurationMs()
        val target = (current + deltaMs).coerceAtLeast(0L).let { value ->
            if (duration > 0L) value.coerceAtMost(duration) else value
        }
        built.seekTo(target)
        Log.i(TAG, "DLNA relative seek delta=$deltaMs from=$current to=$target duration=$duration")
        publish()
    }

    fun setSurface(value: Surface) = main.post {
        // SurfaceView can report its callback after the Activity has already
        // been stopped. Never retain or hand a released Surface to MediaCodec:
        // Media3 otherwise fails before the first frame with
        // "The surface has been released".
        if (!value.isValid) {
            if (surface === value) {
                surface = null
                player?.let { if (media?.type == DlnaMediaType.VIDEO) prepareBackgroundVideo(it) }
            }
            Log.w(TAG, "Ignoring invalid DLNA video surface")
            return@post
        }
        surface = value
        val built = player
        if (built != null && media?.type == DlnaMediaType.VIDEO && directVideoSurface !== value) {
            directVideoSurface = value
            // The Compose preview covers this handoff. Do not connect EGL to
            // the display: MediaCodec owns its native window directly.
            built.setVideoSurface(value)
            Log.i(TAG, "DLNA video output switched to direct display")
        }
        Log.d(TAG, "DLNA video surface attached valid=true")
    }

    fun clearSurface(value: Surface) = main.post {
        if (surface === value) {
            surface = null
            player?.let { if (media?.type == DlnaMediaType.VIDEO) prepareBackgroundVideo(it) }
            Log.d(TAG, "DLNA video surface detached")
        }
    }

    fun setVolume(percent: Int) = main.post {
        volume = percent.coerceIn(0, 100)
        applyVolume()
        Log.i(TAG, "DLNA player volume=$volume muted=$muted effective=${player?.volume ?: 0f}")
        publish()
    }

    fun setMute(value: Boolean) = main.post {
        muted = value
        applyVolume()
        Log.i(TAG, "DLNA player mute=$muted effective=${player?.volume ?: 0f}")
        publish()
    }

    fun snapshot(): DlnaPlayerSnapshot = latestSnapshot

    private fun currentSnapshot(): DlnaPlayerSnapshot = DlnaPlayerSnapshot(
        media = media,
        state = state,
        positionMs = player?.currentPosition ?: 0L,
        durationMs = resolvedDurationMs(),
        volume = volume,
        muted = muted,
        image = image,
        videoReady = firstVideoFrameReady,
        videoPreview = videoPreview,
    )

    fun release() = main.post {
        generation.incrementAndGet()
        stopInternal(notify = false)
        listener = null
    }

    private fun preparePlayer(media: DlnaMedia, autoplay: Boolean) {
        val built = ExoPlayer.Builder(context).build()
        player = built
        val attachedSurface = surface?.takeIf { it.isValid }
        if (surface != null && attachedSurface == null) {
            Log.w(TAG, "Dropping stale DLNA video surface before prepare")
            surface = null
        }
        if (media.type == DlnaMediaType.VIDEO) {
            if (attachedSurface != null) {
                directVideoSurface = attachedSurface
                built.setVideoSurface(attachedSurface)
            } else {
                prepareBackgroundVideo(built)
            }
        }
        Log.i(TAG, "DLNA prepare uri=${media.uri} mime=${media.mimeType.ifBlank { "unknown" }} autoplay=$autoplay surface=${attachedSurface != null}")
        built.volume = volume / 100f
        applyVolume()
        built.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (player !== built || directVideoSurface?.isValid != true) return
                firstVideoFrameReady = true
                videoPreview = null
                // Foreground output is now direct: no GL work or readback per
                // frame. Release the temporary background decoder surface.
                releaseVideoPipeline(videoPipeline)
                videoPipeline = null
                publish()
                listener?.onFirstVideoFrame()
                Log.i(TAG, "DLNA foreground first frame ready; background pipeline released")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (player !== built) return
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoPipeline?.setVideoSize(videoSize.width, videoSize.height)
                    listener?.onVideoSize(videoSize.width, videoSize.height)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player !== built) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> state = "TRANSITIONING"
                    Player.STATE_READY -> state = if (built.playWhenReady) "PLAYING" else "PAUSED_PLAYBACK"
                    Player.STATE_ENDED -> {
                        state = "STOPPED"
                        listener?.onEnded()
                    }
                }
                publish()
            }

            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (player !== built) return
                // Progressive files and HLS often expose their duration only
                // after the first timeline update. Publish immediately so a
                // DLNA controller can enable its progress bar without waiting
                // for the next 500 ms tick.
                applyPendingSeek(built)
                publish()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player !== built) return
                state = if (isPlaying) "PLAYING" else if (built.playbackState == Player.STATE_READY) "PAUSED_PLAYBACK" else state
                publish()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (player !== built) return
                state = "STOPPED"
                Log.e(
                    TAG,
                    "DLNA player error uri=${this@DlnaMediaPlayer.media?.uri} code=${error.errorCodeName}(${error.errorCode}) message=${error.message}",
                    error,
                )
                listener?.onError(error.message ?: "DLNA playback error")
                publish()
            }
        })
        val metadata = MediaMetadata.Builder()
            .setTitle(media.title)
            .setArtist(media.artist)
            .setAlbumTitle(media.album)
            .build()
        built.setMediaItem(MediaItem.Builder().setUri(Uri.parse(media.uri)).setMediaMetadata(metadata).build())
        built.playWhenReady = autoplay
        built.prepare()
        applyPendingSeek(built)
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun applyPendingSeek(built: ExoPlayer) {
        val target = pendingSeekMs ?: return
        if (player !== built) return
        pendingSeekMs = null
        val duration = resolvedDurationMs()
        built.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
        Log.i(TAG, "DLNA pending seek applied position=${built.currentPosition} duration=$duration")
    }

    private fun resolvedDurationMs(): Long {
        val playerDuration = player?.duration?.takeIf { it > 0L }
        return playerDuration ?: media?.durationMs?.takeIf { it > 0L } ?: 0L
    }

    private fun prepareBackgroundVideo(built: ExoPlayer) {
        if (videoPipeline != null && directVideoSurface == null) return
        val token = generation.get()
        val previousPipeline = videoPipeline
        val pipeline = VideoPipeline()
        videoPipeline = pipeline
        directVideoSurface = null
        firstVideoFrameReady = false
        videoPreview = null
        val size = built.videoSize
        if (size.width > 0 && size.height > 0) pipeline.setVideoSize(size.width, size.height)
        pipeline.onFirstFrameCaptured = { preview ->
            main.post {
                if (generation.get() == token && player === built && videoPipeline === pipeline && directVideoSurface == null) {
                    videoPreview = preview
                    firstVideoFrameReady = true
                    publish()
                    listener?.onFirstVideoFrame()
                } else preview.recycle()
            }
        }
        pipeline.onFrameCaptureFailed = {
            main.post {
                if (generation.get() == token && player === built && videoPipeline === pipeline && directVideoSurface == null) {
                    listener?.onError("DLNA first-frame preview failed")
                }
            }
        }
        thread(name = "dlna-video-prepare", isDaemon = true) {
            pipeline.start()
            main.post {
                if (generation.get() != token || player !== built || videoPipeline !== pipeline || directVideoSurface != null) {
                    releaseVideoPipeline(pipeline)
                    releaseVideoPipeline(previousPipeline)
                    return@post
                }
                val input = pipeline.inputSurface
                if (input == null) {
                    state = "STOPPED"
                    releaseVideoPipeline(previousPipeline)
                    listener?.onError("DLNA video output initialization failed")
                    return@post
                }
                built.setVideoSurface(input)
                releaseVideoPipeline(previousPipeline)
            }
        }
    }

    private fun releaseVideoPipeline(pipeline: VideoPipeline?) {
        if (pipeline != null) thread(name = "dlna-video-release", isDaemon = true) { pipeline.release() }
    }

    private fun loadImage(media: DlnaMedia, token: Int) {
        thread(name = "dlna-image", isDaemon = true) {
            try {
                val decoded = downloadBitmap(media.uri)
                main.post {
                    if (generation.get() != token || this.media !== media) {
                        decoded.recycle()
                        return@post
                    }
                    image = decoded
                    state = if (playRequested) "PLAYING" else "STOPPED"
                    listener?.onImageReady(decoded)
                    publish()
                }
            } catch (e: Exception) {
                main.post {
                    if (generation.get() == token) {
                        state = "STOPPED"
                        listener?.onError("DLNA image failed: ${e.message ?: "unknown error"}")
                        publish()
                    }
                }
            }
        }
    }

    private fun loadArtwork(media: DlnaMedia, token: Int) {
        thread(name = "dlna-artwork", isDaemon = true) {
            runCatching { downloadBitmap(media.artUri) }
                .onSuccess { decoded ->
                    main.post {
                        if (generation.get() != token || this.media !== media) {
                            decoded.recycle()
                            return@post
                        }
                        listener?.onArtworkReady(decoded)
                    }
                }
        }
    }

    private fun loadLyrics(media: DlnaMedia, token: Int) {
        thread(name = "dlna-lyrics", isDaemon = true) {
            runCatching { downloadText(media.lyricsUri) }
                .onSuccess { text ->
                    main.post {
                        if (generation.get() == token && this.media === media) {
                            listener?.onLyricsReady(text)
                        }
                    }
                }
                .onFailure { error ->
                    Log.d(TAG, "DLNA lyrics download failed: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    private fun downloadText(uri: String): String {
        val connection = (URL(uri).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    connection.contentLengthLong.takeIf { it > 0L }?.toInt()?.coerceAtMost(MAX_LYRICS_BYTES) ?: 16 * 1024
                )
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_LYRICS_BYTES) throw IllegalStateException("lyrics too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val charset = Regex("charset\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE)
                .find(connection.contentType.orEmpty())?.groupValues?.getOrNull(1)
                ?.trim()?.trim('"', '\'')
                ?.let { runCatching { Charset.forName(it) }.getOrNull() }
                ?: Charsets.UTF_8
            String(bytes, charset)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadBitmap(uri: String): Bitmap {
        val connection = (URL(uri).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        return try {
            connection.connect()
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) throw IllegalStateException("image too large")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream(
                    connection.contentLengthLong.takeIf { it > 0L }?.toInt()?.coerceAtMost(MAX_IMAGE_BYTES) ?: 32 * 1024
                )
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) throw IllegalStateException("image too large")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("invalid image")
            val sample = calculateSample(bounds.outWidth, bounds.outHeight)
            val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IllegalStateException("image decode failed")
        } finally {
            connection.disconnect()
        }
    }

    private fun calculateSample(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > 4096 || height / sample > 4096) sample *= 2
        return sample
    }

    private fun applyVolume() {
        player?.volume = if (muted) 0f else volume / 100f
    }

    private fun stopInternal(notify: Boolean) {
        main.removeCallbacks(tick)
        val previousPlayer = player
        player = null
        previousPlayer?.release()
        releaseVideoPipeline(videoPipeline)
        videoPipeline = null
        directVideoSurface = null
        videoPreview = null
        firstVideoFrameReady = false
        playRequested = false
        pendingSeekMs = null
        image?.let { if (!it.isRecycled) it.recycle() }
        image = null
        media = null
        state = "NO_MEDIA"
        if (notify) publish()
    }

    private fun publish() {
        val snapshot = currentSnapshot()
        latestSnapshot = snapshot
        listener?.onSnapshot(snapshot)
    }

    companion object {
        private const val TAG = "DlnaMediaPlayer"
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
        private const val MAX_LYRICS_BYTES = 512 * 1024
    }
}
