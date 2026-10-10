package io.github.jqssun.airplay.renderer

import android.graphics.Bitmap

enum class DlnaMediaType { VIDEO, AUDIO, IMAGE }

data class DlnaMedia(
    val uri: String,
    val type: DlnaMediaType,
    val mimeType: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artUri: String = "",
)

data class DlnaPlayerSnapshot(
    val media: DlnaMedia? = null,
    val state: String = "NO_MEDIA",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int = 100,
    val muted: Boolean = false,
    val image: Bitmap? = null,
    val videoReady: Boolean = false,
)
