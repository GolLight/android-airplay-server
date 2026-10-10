package io.github.jqssun.airplay.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
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
        fun onEnded()
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var surface: Surface? = null
    private var media: DlnaMedia? = null
    private var image: Bitmap? = null
    private var state = "NO_MEDIA"
    private var volume = 100
    private var muted = false
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
        state = "TRANSITIONING"
        publish()
        when (media.type) {
            DlnaMediaType.IMAGE -> loadImage(media, token, autoplay)
            DlnaMediaType.VIDEO -> preparePlayer(media, autoplay)
            DlnaMediaType.AUDIO -> {
                preparePlayer(media, autoplay)
                if (media.artUri.isNotBlank()) loadArtwork(media, token)
            }
        }
    }

    fun resume() = main.post {
        if (media?.type == DlnaMediaType.IMAGE) {
            state = "PLAYING"
            publish()
            return@post
        }
        player?.play()
        if (player != null) state = "PLAYING"
        publish()
    }

    fun pause() = main.post {
        player?.pause()
        if (player != null) state = "PAUSED_PLAYBACK"
        publish()
    }

    fun stop() = main.post {
        generation.incrementAndGet()
        stopInternal(notify = true)
    }

    fun seek(positionMs: Long) = main.post {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        publish()
    }

    fun setSurface(value: Surface) = main.post {
        surface = value
        player?.setVideoSurface(value)
    }

    fun clearSurface(value: Surface) = main.post {
        if (surface === value) {
            surface = null
            player?.setVideoSurface(null)
        }
    }

    fun setVolume(percent: Int) = main.post {
        volume = percent.coerceIn(0, 100)
        applyVolume()
        publish()
    }

    fun setMute(value: Boolean) = main.post {
        muted = value
        applyVolume()
        publish()
    }

    fun snapshot(): DlnaPlayerSnapshot = latestSnapshot

    private fun currentSnapshot(): DlnaPlayerSnapshot = DlnaPlayerSnapshot(
        media = media,
        state = state,
        positionMs = player?.currentPosition ?: 0L,
        durationMs = player?.duration?.takeIf { it > 0L } ?: 0L,
        volume = volume,
        muted = muted,
        image = image,
        videoReady = state == "PLAYING" || state == "PAUSED_PLAYBACK",
    )

    fun release() = main.post {
        generation.incrementAndGet()
        stopInternal(notify = false)
        listener = null
    }

    private fun preparePlayer(media: DlnaMedia, autoplay: Boolean) {
        val built = ExoPlayer.Builder(context).build()
        player = built
        surface?.let { built.setVideoSurface(it) }
        built.volume = volume / 100f
        applyVolume()
        built.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                listener?.onFirstVideoFrame()
                publish()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    listener?.onVideoSize(videoSize.width, videoSize.height)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
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

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state = if (isPlaying) "PLAYING" else if (built.playbackState == Player.STATE_READY) "PAUSED_PLAYBACK" else state
                publish()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                state = "STOPPED"
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
        main.removeCallbacks(tick)
        main.post(tick)
    }

    private fun loadImage(media: DlnaMedia, token: Int, autoplay: Boolean) {
        thread(name = "dlna-image", isDaemon = true) {
            try {
                val decoded = downloadBitmap(media.uri)
                main.post {
                    if (generation.get() != token || this.media !== media) {
                        decoded.recycle()
                        return@post
                    }
                    image = decoded
                    state = if (autoplay) "PLAYING" else "STOPPED"
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
        player?.release()
        player = null
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
        private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    }
}
