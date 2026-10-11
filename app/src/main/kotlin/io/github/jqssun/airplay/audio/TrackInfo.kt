package io.github.jqssun.airplay.audio

import android.graphics.Bitmap

data class TrackInfo(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val durationMs: Long = 0,
    val coverArt: Bitmap? = null,
    val lyrics: String = "",
    // Live AirPlay packets describe the current line, not a future LRC cue.
    val liveLyrics: Boolean = false,
    val airPlayIdentityPacket: Boolean = false,
) {
    companion object {
        fun fromDmap(map: Map<String, Any>, existingArt: Bitmap? = null): TrackInfo {
            // DMAP data is often wrapped in mlit container
            val flat = _flatten(map)
            return TrackInfo(
                title = flat["minm"] as? String ?: "",
                artist = flat["asar"] as? String ?: "",
                album = flat["asal"] as? String ?: "",
                genre = flat["asgn"] as? String ?: "",
                durationMs = (flat["astm"] as? Long) ?: 0L,
                coverArt = existingArt,
                lyrics = listOf("asly", "lyrics", "lyric", "lrc")
                    .asSequence().mapNotNull { flat[it] as? String }
                    .firstOrNull { it.isNotBlank() }.orEmpty(),
                airPlayIdentityPacket = flat.containsKey("asdk"),
            )
        }

        @Suppress("UNCHECKED_CAST")
        private fun _flatten(map: Map<String, Any>): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            for ((k, v) in map) {
                if (v is Map<*, *>) {
                    result.putAll(_flatten(v as Map<String, Any>))
                } else {
                    result[k] = v
                }
            }
            return result
        }
    }
}
