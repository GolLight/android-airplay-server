package io.github.jqssun.airplay.audio

/**
 * A normalized result from an AirPlay DMAP metadata packet.
 *
 * The transport is shared by music apps, but the live lyric convention is
 * sender-specific. Keeping that convention behind an adapter prevents it from
 * changing the generic track metadata path.
 */
sealed interface AirPlayMetadataUpdate {
    data class Track(val info: TrackInfo) : AirPlayMetadataUpdate
    data class LiveLyric(val text: String, val track: TrackInfo) : AirPlayMetadataUpdate
}

interface AirPlayMetadataAdapter {
    fun adapt(incoming: TrackInfo, current: TrackInfo): AirPlayMetadataUpdate?
}

/** NetEase AirPlay sends the current lyric in minm and artist/song in asar. */
class NetEaseAirPlayMetadataAdapter : AirPlayMetadataAdapter {
    override fun adapt(incoming: TrackInfo, current: TrackInfo): AirPlayMetadataUpdate? {
        val lyricArtist = "${current.artist}/${current.title}"
        if (current.title.isNotBlank() && current.artist.isNotBlank() && incoming.artist == lyricArtist) {
            return lyricUpdate(incoming, current)
        }
        val separator = incoming.artist.lastIndexOf('/')
        if (separator <= 0 || separator == incoming.artist.lastIndex) return null
        val track = incoming.copy(
            artist = incoming.artist.substring(0, separator).trim(),
            title = incoming.artist.substring(separator + 1).trim(),
            lyrics = "",
        )
        if (track.title.isBlank() || track.artist.isBlank()) return null

        // NetEase can connect in the middle of a song. Its first packet may
        // include `asdk`/`astm` and still carry the lyric in `minm`, so those
        // fields cannot be used to reject this path. The distinguishing shape
        // is artist/song in `asar` while `minm` differs from the song title.
        // Keep ordinary metadata (where minm is the song title) on the fallback
        // path. When a known artist itself contains '/', do not reinterpret its
        // normal title update as a bootstrap lyric packet.
        if (incoming.title.isBlank() || incoming.title == track.title ||
            (current.artist.isNotBlank() && incoming.artist == current.artist)) return null
        return lyricUpdate(incoming, track)
    }
}

/** QQ Music AirPlay uses title — artist asar while sending a lyric in minm. */
class QqMusicAirPlayMetadataAdapter : AirPlayMetadataAdapter {
    override fun adapt(incoming: TrackInfo, current: TrackInfo): AirPlayMetadataUpdate? {
        if (incoming.title.isBlank()) return null
        val qqArtist = listOf(
            "${current.title} — ${current.artist}",
            "${current.title} - ${current.artist}",
        )
        if (current.title.isNotBlank() && current.artist.isNotBlank() && incoming.artist.trim() in qqArtist) {
            return lyricUpdate(incoming, current)
        }
        // Only bootstrap from QQ's spaced em dash. A plain hyphen is ambiguous
        // in normal artist names, and is accepted only against a known track.
        val separator = incoming.artist.indexOf(" — ")
        if (separator <= 0 || incoming.artist == current.artist) return null
        val track = incoming.copy(
            title = incoming.artist.substring(0, separator).trim(),
            artist = incoming.artist.substring(separator + 3).trim(),
            lyrics = "",
        )
        if (track.title.isBlank() || track.artist.isBlank()) return null

        // QQ can send the new identity in `asar` before it replaces `minm`.
        // In that transition `minm` is still the previous song title. Treat
        // it as an identity packet instead of displaying the stale title as
        // the first lyric line. The duration field may already be present,
        // so it must not be used to reject this bootstrap packet.
        if (current.title.isNotBlank() && incoming.title == current.title &&
            track.title != current.title) {
            return AirPlayMetadataUpdate.Track(track)
        }
        return lyricUpdate(incoming, track)
    }
}

private fun lyricUpdate(incoming: TrackInfo, track: TrackInfo): AirPlayMetadataUpdate =
    if (incoming.title == track.title) AirPlayMetadataUpdate.Track(track)
    else AirPlayMetadataUpdate.LiveLyric(incoming.title, track)

/** Handles the standard title/artist/album fields used by other AirPlay senders. */
class StandardAirPlayMetadataAdapter : AirPlayMetadataAdapter {
    override fun adapt(incoming: TrackInfo, current: TrackInfo): AirPlayMetadataUpdate? {
        if (incoming.title.isBlank() && incoming.artist.isBlank()) return null
        return AirPlayMetadataUpdate.Track(incoming)
    }
}

/**
 * Ordered AirPlay metadata pipeline. Sender-specific adapters run first, then
 * the standards-based fallback handles ordinary track updates.
 */
class AirPlayMetadataPipeline(
    private val adapters: List<AirPlayMetadataAdapter> = listOf(
        NetEaseAirPlayMetadataAdapter(),
        QqMusicAirPlayMetadataAdapter(),
        StandardAirPlayMetadataAdapter(),
    ),
) {
    fun adapt(incoming: TrackInfo, current: TrackInfo): AirPlayMetadataUpdate? =
        adapters.asSequence().mapNotNull { it.adapt(incoming, current) }.firstOrNull()
}
