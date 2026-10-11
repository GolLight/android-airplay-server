package io.github.jqssun.airplay.audio

/** Received live lines; timestamps are receipt positions, not sender cue times. */
class AirPlayLyricHistory {
    private val lines = mutableListOf<Pair<Long, String>>()
    private var previousPayload = ""

    fun clear() {
        lines.clear()
        previousPayload = ""
    }

    fun add(positionMs: Long, rawText: String): String {
        val payload = rawText.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (payload.isBlank()) return toLrc()
        val position = positionMs.coerceAtLeast(0L)
        // Ordinary RAOP progress corrections can move backwards by hundreds
        // of milliseconds. Do not delete/reinsert lines on duplicate packets.
        // A substantial backwards jump is a seek and replaces the old tail.
        if (lines.lastOrNull()?.first?.let { it - position > 2_000L } == true) {
            lines.removeAll { it.first > position }
            previousPayload = ""
        }
        if (payload == previousPayload) return toLrc()
        if (payload.length >= 3 && previousPayload.startsWith(payload) &&
            lines.lastOrNull()?.first?.let { position - it in -2_000L..1_000L } == true) return toLrc()
        // Compare whole packets, not just the last stored suffix. A sender
        // may send A, then A+B, then A+B+C without newline separators.
        val freshText = if (previousPayload.length >= 4 && payload.startsWith(previousPayload)) {
            payload.removePrefix(previousPayload).trim()
        } else payload
        val fragments = freshText.split('\n').map(String::trim).filter(String::isNotBlank)
        val last = lines.lastOrNull()
        if (fragments.size == 1 && last != null &&
            (fragments[0] == last.second || (fragments[0].length >= 3 && last.second.startsWith(fragments[0]) &&
                position - last.first in -2_000L..1_000L))) return toLrc()
        previousPayload = payload
        fragments.forEach { fragment ->
            val previous = lines.lastOrNull()?.second
            when {
                fragment == previous -> Unit
                previous != null && previous.length >= 4 && fragment.startsWith(previous) -> {
                    fragment.removePrefix(previous).trim().takeIf(String::isNotBlank)?.let {
                        lines.add(position to it)
                    }
                }
                previous != null && fragment.length >= 3 && previous.startsWith(fragment) &&
                    position - lines.last().first in -2_000L..1_000L -> Unit
                else -> lines.add(position to fragment)
            }
        }
        if (lines.size > 500) lines.subList(0, lines.size - 500).clear()
        return toLrc()
    }

    private fun toLrc(): String = lines.joinToString("\n") { (time, text) ->
        val seconds = (time / 1_000 % 60).toString().padStart(2, '0')
        val millis = (time % 1_000).toString().padStart(3, '0')
        "[${time / 60_000}:$seconds.$millis]$text"
    }
}
