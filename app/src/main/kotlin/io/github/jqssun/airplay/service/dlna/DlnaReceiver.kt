package io.github.jqssun.airplay.service.dlna

import android.util.Log
import io.github.jqssun.airplay.renderer.DlnaMedia
import io.github.jqssun.airplay.renderer.DlnaMediaType
import io.github.jqssun.airplay.renderer.DlnaPlayerSnapshot
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Small self-contained UPnP MediaRenderer. It deliberately avoids adding a DLNA dependency. */
class DlnaReceiver(
    private val friendlyName: String,
    private val callback: Callback,
    private val uuid: String,
) {
    interface Callback {
        fun onSetMedia(media: DlnaMedia): Boolean
        fun onPlay()
        fun onPause()
        fun onStop()
        fun onSeek(positionMs: Long)
        fun onSetVolume(percent: Int)
        fun onSetMute(muted: Boolean)
        fun snapshot(): DlnaPlayerSnapshot
        fun log(message: String)
    }

    private val running = AtomicBoolean(false)
    private var httpServer: ServerSocket? = null
    private var httpThread: Thread? = null
    private var ssdpSocket: MulticastSocket? = null
    private var ssdpThread: Thread? = null
    private var httpPort = 0
    @Volatile private var ipAddress = "127.0.0.1"
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dlna-events").apply { isDaemon = true }
    }
    private val eventTaskQueued = AtomicBoolean(false)
    @Volatile private var currentMedia: DlnaMedia? = null
    @Volatile private var transportState = "NO_MEDIA"
    @Volatile private var volume = 100
    @Volatile private var muted = false
    private val uuidValue = uuid.removePrefix("uuid:")

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            ipAddress = localIpv4Address()
            // Listen on all local interfaces so a Wi-Fi address change does not invalidate
            // the HTTP endpoint; LOCATION is refreshed from the current IPv4 address.
            httpServer = ServerSocket(0, 32)
            httpPort = httpServer!!.localPort
            httpThread = thread(name = "dlna-http", isDaemon = true) { acceptLoop() }
            tryStartSsdp()
            callback.log("DLNA renderer started at $ipAddress:$httpPort")
        } catch (e: Exception) {
            running.set(false)
            httpServer?.close()
            httpServer = null
            callback.log("DLNA start failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        sendNotify(alive = false)
        subscriptions.clear()
        eventExecutor.shutdownNow()
        ssdpSocket?.close()
        ssdpSocket = null
        httpServer?.close()
        httpServer = null
        callback.log("DLNA renderer stopped")
    }

    fun updateSnapshot(snapshot: DlnaPlayerSnapshot) {
        currentMedia = snapshot.media
        transportState = snapshot.state
        volume = snapshot.volume
        muted = snapshot.muted
        if (running.get()) notifySubscribers()
    }

    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = httpServer?.accept() ?: break
                thread(name = "dlna-http-client", isDaemon = true) { handleHttp(socket) }
            } catch (_: Exception) {
                if (running.get()) callback.log("DLNA HTTP accept failed")
            }
        }
    }

    private fun tryStartSsdp() {
        try {
            val socket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(SSDP_PORT))
                joinGroup(InetAddress.getByName(SSDP_ADDRESS))
            }
            ssdpSocket = socket
            ssdpThread = thread(name = "dlna-ssdp", isDaemon = true) { ssdpLoop(socket) }
            sendNotify(alive = true)
        } catch (e: Exception) {
            callback.log("DLNA SSDP unavailable: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun ssdpLoop(socket: MulticastSocket) {
        val buffer = ByteArray(8192)
        while (running.get()) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val request = String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
                if (request.startsWith("M-SEARCH", ignoreCase = true)) {
                    val headers = parseHeaders(request.substringAfter("\r\n", ""))
                    val target = headers["st"] ?: "ssdp:all"
                    sendSearchResponse(packet.address, packet.port, target)
                }
            } catch (_: Exception) {
                if (running.get()) callback.log("DLNA SSDP listener stopped unexpectedly")
                break
            }
        }
    }

    private fun sendSearchResponse(address: InetAddress, port: Int, target: String) {
        val values = responseTargets(target)
        if (values.isEmpty()) return
        values.forEach { (st, usn) ->
            val body = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("DATE: 0\r\n")
                append("EXT:\r\n")
                append("LOCATION: $location\r\n")
                append("SERVER: Android/12 UPnP/1.1 MirrorReceiver/1.0\r\n")
                append("ST: $st\r\n")
                append("USN: $usn\r\n\r\n")
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            DatagramSocket().use { it.send(DatagramPacket(bytes, bytes.size, address, port)) }
        }
    }

    private fun responseTargets(target: String): List<Pair<String, String>> {
        val root = "uuid:$uuidValue::upnp:rootdevice"
        val device = "uuid:$uuidValue::urn:schemas-upnp-org:device:MediaRenderer:1"
        val services = listOf(
            "uuid:$uuidValue::urn:schemas-upnp-org:service:AVTransport:1",
            "uuid:$uuidValue::urn:schemas-upnp-org:service:RenderingControl:1",
            "uuid:$uuidValue::urn:schemas-upnp-org:service:ConnectionManager:1",
        )
        return when {
            target.equals("ssdp:all", true) -> listOf("upnp:rootdevice" to root, "uuid:$uuidValue" to "uuid:$uuidValue", "urn:schemas-upnp-org:device:MediaRenderer:1" to device) +
                services.map { it.substringAfter("::") to it }
            target.equals("upnp:rootdevice", true) -> listOf("upnp:rootdevice" to root)
            target.equals("uuid:$uuidValue", true) -> listOf("uuid:$uuidValue" to "uuid:$uuidValue")
            target.contains("MediaRenderer", true) -> listOf("urn:schemas-upnp-org:device:MediaRenderer:1" to device)
            target.contains("AVTransport", true) -> listOf("urn:schemas-upnp-org:service:AVTransport:1" to services[0])
            target.contains("RenderingControl", true) -> listOf("urn:schemas-upnp-org:service:RenderingControl:1" to services[1])
            target.contains("ConnectionManager", true) -> listOf("urn:schemas-upnp-org:service:ConnectionManager:1" to services[2])
            else -> emptyList()
        }
    }

    private fun sendNotify(alive: Boolean) {
        val socket = ssdpSocket ?: return
        val nts = if (alive) "ssdp:alive" else "ssdp:byebye"
        val locationLine = if (alive) "LOCATION: $location\r\n" else ""
        val targets = responseTargets("ssdp:all")
        targets.forEach { (nt, usn) ->
            val body = buildString {
                append("NOTIFY * HTTP/1.1\r\n")
                append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                append("CACHE-CONTROL: max-age=1800\r\n")
                append("NT: $nt\r\n")
                append("NTS: $nts\r\n")
                append("USN: $usn\r\n")
                append(locationLine)
                append("SERVER: Android/12 UPnP/1.1 MirrorReceiver/1.0\r\n\r\n")
            }
            runCatching {
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT))
            }
        }
    }

    private fun handleHttp(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            try {
                val input = BufferedInputStream(client.getInputStream())
                val first = readLine(input) ?: return
                val parts = first.split(' ', limit = 3)
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1].substringBefore('?')
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val body = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                    readChunkedBytes(input).toString(StandardCharsets.UTF_8)
                } else {
                    val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                    readBytes(input, length).toString(StandardCharsets.UTF_8)
                }
                val output = BufferedOutputStream(client.getOutputStream())
                when {
                    method == "GET" && path == "/description.xml" -> respondXml(output, deviceDescription())
                    method == "GET" && path.startsWith("/scpd/") -> respondXml(output, scpd(path.substringAfterLast('/').substringBeforeLast('.')))
                    method == "POST" && path.startsWith("/upnp/control/") -> handleSoap(output, path.substringAfterLast('/'), headers, body)
                    method == "SUBSCRIBE" && path.startsWith("/upnp/event/") -> handleSubscribe(output, path.substringAfterLast('/'), headers)
                    method == "UNSUBSCRIBE" && path.startsWith("/upnp/event/") -> handleUnsubscribe(output, headers)
                    else -> {
                        Log.i(TAG, "DLNA unhandled request method=$method path=$path bytes=${body.length}")
                        respond(output, "404 Not Found", "text/plain", "Not found")
                    }
                }
                output.flush()
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "DLNA HTTP request failed", e)
            }
        }
    }

    private fun handleSoap(output: BufferedOutputStream, service: String, headers: Map<String, String>, body: String) {
        val serviceName = when (service.lowercase()) {
            "avtransport", "avtransport1" -> "AVTransport"
            "renderingcontrol", "renderingcontrol1" -> "RenderingControl"
            "connectionmanager", "connectionmanager1" -> "ConnectionManager"
            else -> service
        }
        val action = headers["soapaction"]?.trim()?.trim('"', '\'')?.substringAfterLast('#')
            ?: Regex("<(?:[A-Za-z0-9_]+:)?Body[^>]*>\\s*<(?:[A-Za-z0-9_]+:)?([A-Za-z0-9_]+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(body)?.groupValues?.getOrNull(1)
            ?: tag(body, "Action", caseInsensitive = true)?.let { it.substringAfterLast(':') }
            ?: ""
        if (serviceName == "RenderingControl" || serviceName == "AVTransport") {
            val level = if (action.equals("GetPositionInfo", true) || action.equals("GetTransportInfo", true)) Log.DEBUG else Log.INFO
            Log.println(
                level,
                TAG,
                "DLNA SOAP service=$serviceName action=$action bodyBytes=${body.toByteArray(StandardCharsets.UTF_8).size}",
            )
        }
        val response = try {
            when (serviceName to action) {
                "AVTransport" to "SetAVTransportURI" -> {
                    val uri = tag(body, "CurrentURI").orEmpty()
                    val metadata = tag(body, "CurrentURIMetaData").orEmpty()
                    val media = parseMedia(uri, metadata)
                    callback.log("DLNA media ${media.type}: title=${media.title.ifBlank { "(untitled)" }}, duration=${formatTime(media.durationMs)}, lyrics=${media.lyrics.length} chars, lyricsUri=${media.lyricsUri.isNotBlank()}")
                    if (media.uri.isBlank() || !callback.onSetMedia(media)) return fault(output, 701, "Transition not available")
                    currentMedia = media
                    transportState = "STOPPED"
                    soapResponse(serviceName, "SetAVTransportURI")
                }
                "AVTransport" to "Play" -> {
                    if (currentMedia == null) return fault(output, 702, "No content")
                    Log.i(TAG, "DLNA Play uri=${currentMedia?.uri?.take(160)}")
                    callback.onPlay(); transportState = "PLAYING"; soapResponse(serviceName, "Play")
                }
                "AVTransport" to "Pause" -> { callback.onPause(); transportState = "PAUSED_PLAYBACK"; soapResponse(serviceName, "Pause") }
                "AVTransport" to "Stop" -> { callback.onStop(); transportState = "STOPPED"; soapResponse(serviceName, "Stop") }
                "AVTransport" to "Seek" -> {
                    val unit = tag(body, "Unit", caseInsensitive = true).orEmpty()
                    val target = tag(body, "Target") ?: "00:00:00"
                    val positionMs = parseTime(target)
                    Log.i(TAG, "DLNA Seek unit=$unit target=$target positionMs=$positionMs")
                    callback.onSeek(positionMs); soapResponse(serviceName, "Seek")
                }
                "AVTransport" to "GetTransportInfo" -> {
                    val snapshot = callback.snapshot()
                    val state = snapshot.state.takeIf { it != "NO_MEDIA" }.orEmpty().ifBlank { transportState }
                    Log.d(TAG, "DLNA GetTransportInfo state=$state")
                    soapResponse(serviceName, "GetTransportInfo", "<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>${if (state == "PLAYING") 1 else 0}</CurrentSpeed>")
                }
                "AVTransport" to "GetPositionInfo" -> {
                    val s = callback.snapshot()
                    // The player snapshot is published on Android's main
                    // thread. During the short handoff after SetAVTransportURI
                    // it can still be one tick behind, while currentMedia is
                    // already known to the SOAP thread. Use both so the
                    // sender does not see a transient zero-length track.
                    val media = s.media ?: currentMedia
                    val durationMs = s.durationMs.takeIf { it > 0L }
                        ?: media?.durationMs?.takeIf { it > 0L }
                        ?: 0L
                    val position = formatTime(s.positionMs.coerceIn(0L, durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE))
                    val duration = formatTime(durationMs)
                    Log.d(TAG, "DLNA GetPositionInfo state=${s.state} position=$position duration=$duration")
                    soapResponse(serviceName, "GetPositionInfo", "<Track>1</Track><TrackDuration>$duration</TrackDuration><TrackMetaData></TrackMetaData><TrackURI>${xml(media?.uri.orEmpty())}</TrackURI><RelTime>$position</RelTime><AbsTime>$position</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>")
                }
                "AVTransport" to "GetMediaInfo" -> {
                    val s = callback.snapshot()
                    val media = s.media ?: currentMedia
                    val durationMs = s.durationMs.takeIf { it > 0L }
                        ?: media?.durationMs?.takeIf { it > 0L }
                        ?: 0L
                    soapResponse(serviceName, "GetMediaInfo", "<NrTracks>1</NrTracks><MediaDuration>${formatTime(durationMs)}</MediaDuration><CurrentURI>${xml(media?.uri.orEmpty())}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData><NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>")
                }
                "AVTransport" to "GetDeviceCapabilities" -> soapResponse(serviceName, "GetDeviceCapabilities", "<PlayMedia>NETWORK,UNKNOWN</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>")
                "AVTransport" to "GetTransportSettings" -> soapResponse(serviceName, "GetTransportSettings", "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>")
                "AVTransport" to "GetCurrentTransportActions" -> soapResponse(serviceName, "GetCurrentTransportActions", "<Actions>Play,Pause,Stop,Seek</Actions>")
                "RenderingControl" to "GetVolume" -> {
                    Log.i(TAG, "DLNA GetVolume -> $volume")
                    soapResponse(serviceName, "GetVolume", "<CurrentVolume>$volume</CurrentVolume>")
                }
                "RenderingControl" to "SetVolume" -> {
                    val raw = tag(body, "DesiredVolume", caseInsensitive = true)
                    val value = raw?.toIntOrNull()?.coerceIn(0, 100) ?: volume
                    Log.i(TAG, "DLNA SetVolume desired=$raw applied=$value previous=$volume")
                    volume = value
                    callback.onSetVolume(value)
                    soapResponse(serviceName, "SetVolume")
                }
                "RenderingControl" to "GetVolumeDB" -> {
                    val db = if (volume == 0) -3000 else -3000 + volume * 30
                    soapResponse(serviceName, "GetVolumeDB", "<CurrentVolumeDB>$db</CurrentVolumeDB>")
                }
                "RenderingControl" to "SetVolumeDB" -> {
                    val raw = tag(body, "DesiredVolume", caseInsensitive = true) ?: tag(body, "DesiredVolumeDB", caseInsensitive = true)
                    val db = raw?.toIntOrNull()
                    val value = db?.let { ((it + 3000) / 30).coerceIn(0, 100) } ?: volume
                    Log.i(TAG, "DLNA SetVolumeDB desired=$raw applied=$value previous=$volume")
                    volume = value
                    callback.onSetVolume(value)
                    soapResponse(serviceName, "SetVolumeDB")
                }
                "RenderingControl" to "GetMute" -> soapResponse(serviceName, "GetMute", "<CurrentMute>${if (muted) 1 else 0}</CurrentMute>")
                "RenderingControl" to "SetMute" -> {
                    muted = tag(body, "DesiredMute", caseInsensitive = true) == "1"
                    Log.i(TAG, "DLNA SetMute applied=$muted")
                    callback.onSetMute(muted)
                    soapResponse(serviceName, "SetMute")
                }
                "ConnectionManager" to "GetProtocolInfo" -> soapResponse(serviceName, "GetProtocolInfo", "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink>")
                "ConnectionManager" to "GetCurrentConnectionIDs" -> soapResponse(serviceName, "GetCurrentConnectionIDs", "<ConnectionIDs>0</ConnectionIDs>")
                "ConnectionManager" to "GetCurrentConnectionInfo" -> soapResponse(serviceName, "GetCurrentConnectionInfo", "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>")
                else -> {
                    return fault(output, 401, "Invalid Action")
                }
            }
        } catch (_: HandledSoapException) {
            return
        } catch (e: Exception) {
            callback.log("DLNA SOAP $action failed: ${e.message ?: e.javaClass.simpleName}")
            return fault(output, 501, "Action Failed")
        }
        respond(output, "200 OK", "text/xml; charset=\"utf-8\"", response)
        notifySubscribers()
    }

    private fun handleSubscribe(output: BufferedOutputStream, service: String, headers: Map<String, String>) {
        val normalizedService = service.substringBefore('.').replaceFirstChar { it.uppercase() }
        val sid = headers["sid"]?.takeIf { it.startsWith("uuid:") } ?: "uuid:${UUID.randomUUID()}"
        val callbackUrl = headers["callback"]?.let { Regex("<([^>]+)>").find(it)?.groupValues?.get(1) }
        if (callbackUrl != null) {
            subscriptions[sid] = Subscription(sid, normalizedService, callbackUrl)
            Log.i(TAG, "DLNA SUBSCRIBE service=$normalizedService sid=$sid callback=$callbackUrl")
        } else {
            Log.i(TAG, "DLNA SUBSCRIBE renewal service=$normalizedService sid=$sid")
        }
        val extra = "SID: $sid\r\nTIMEOUT: Second-1800\r\n"
        respond(output, "200 OK", "text/plain", "", extra)
        if (callbackUrl != null) scheduleEventDispatch()
    }

    private fun handleUnsubscribe(output: BufferedOutputStream, headers: Map<String, String>) {
        headers["sid"]?.let { subscriptions.remove(it) }
        respond(output, "200 OK", "text/plain", "")
    }

    private fun notifySubscribers() {
        scheduleEventDispatch()
    }

    private fun scheduleEventDispatch() {
        if (!running.get() || subscriptions.isEmpty() || !eventTaskQueued.compareAndSet(false, true)) return
        runCatching {
            eventExecutor.execute {
                try {
                    subscriptions.values.toList().forEach(::sendEvent)
                } finally {
                    eventTaskQueued.set(false)
                }
            }
        }.onFailure { eventTaskQueued.set(false) }
    }

    private fun sendEvent(subscription: Subscription) {
        try {
            val url = java.net.URL(subscription.callback)
            val snapshot = callback.snapshot()
            val media = snapshot.media ?: currentMedia
            val durationMs = snapshot.durationMs.takeIf { it > 0L }
                ?: media?.durationMs?.takeIf { it > 0L }
                ?: 0L
            val positionMs = snapshot.positionMs.coerceIn(
                0L,
                durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE,
            )
            val lastChange = when (subscription.service) {
                "AVTransport" -> """
                    <Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/">
                      <InstanceID val="0">
                        <TransportState val="${xml(snapshot.state.takeIf { it != "NO_MEDIA" }.orEmpty().ifBlank { transportState })}"/>
                        <TransportStatus val="OK"/>
                        <CurrentTrackURI val="${xml(media?.uri.orEmpty())}"/>
                        <CurrentTrackDuration val="${formatTime(durationMs)}"/>
                        <RelativeTimePosition val="${formatTime(positionMs)}"/>
                        <AbsoluteTimePosition val="${formatTime(positionMs)}"/>
                      </InstanceID>
                    </Event>
                """.trimIndent()
                "RenderingControl" -> """
                    <Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/">
                      <InstanceID val="0">
                        <Volume channel="Master" val="$volume"/>
                        <Mute channel="Master" val="${if (muted) 1 else 0}"/>
                      </InstanceID>
                    </Event>
                """.trimIndent()
                else -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/CM/\"><InstanceID val=\"0\"><ConnectionManagerState val=\"OK\"/></InstanceID></Event>"
            }
            val event = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange xmlns=\"urn:schemas-upnp-org:metadata-1-0/${if (subscription.service == "AVTransport") "AVT" else if (subscription.service == "RenderingControl") "RCS" else "CM"}/\">$lastChange</LastChange></e:property></e:propertyset>"
            val bytes = event.toByteArray(StandardCharsets.UTF_8)
            Log.d(TAG, "DLNA event service=${subscription.service} sid=${subscription.sid} position=${formatTime(positionMs)} duration=${formatTime(durationMs)}")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(url.host, if (url.port > 0) url.port else 80), 3000)
                val out = socket.getOutputStream()
                out.write("NOTIFY ${url.path.ifEmpty { "/" }} HTTP/1.1\r\nHOST: ${url.host}:${if (url.port > 0) url.port else 80}\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: ${subscription.sid}\r\nSEQ: ${subscription.sequence++}\r\nCONTENT-LENGTH: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write(bytes); out.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "DLNA event failed", e)
            subscriptions.remove(subscription.sid)
        }
    }

    private fun parseMedia(uri: String, metadata: String): DlnaMedia {
        val cleanUri = decodeXml(uri.trim())
        val mime = tag(metadata, "format", caseInsensitive = true).orEmpty()
            .ifBlank { protocolInfoMime(metadata) }
        val title = tag(metadata, "title", caseInsensitive = true).orEmpty()
        val artist = tag(metadata, "artist", caseInsensitive = true).orEmpty()
        val album = tag(metadata, "album", caseInsensitive = true).orEmpty()
        val art = tag(metadata, "albumArtURI", caseInsensitive = true).orEmpty()
        val lyricsUri = listOf(
            "lyricsURI", "lyricsUri", "lyricsURL", "lyricsUrl",
            "lyricURI", "lyricUri", "lyricURL", "lyricUrl",
            "lrcURI", "lrcUri", "lrcURL", "lrcUrl",
        )
            .asSequence().mapNotNull { tag(metadata, it, caseInsensitive = true) }.firstOrNull().orEmpty()
        val lyrics = listOf("lyrics", "lyric", "unsyncedLyrics", "syncLyrics", "lrc", "lyricText")
            .asSequence().mapNotNull { tag(metadata, it, caseInsensitive = true) }.firstOrNull().orEmpty()
        // Most DLNA senders put the VOD duration on the DIDL-Lite <res>
        // element. ExoPlayer may not know it yet when the controller first
        // calls GetPositionInfo, so retain this value as an early fallback.
        val durationMs = listOfNotNull(
            attribute(metadata, "res", "duration"),
            tag(metadata, "duration", caseInsensitive = true),
        ).asSequence()
            .map(::parseTime)
            .firstOrNull { it > 0L }
            ?: 0L
        val type = when {
            mime.startsWith("image/", true) || cleanUri.substringBefore('?').substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS -> DlnaMediaType.IMAGE
            mime.startsWith("audio/", true) || cleanUri.substringBefore('?').substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS -> DlnaMediaType.AUDIO
            else -> DlnaMediaType.VIDEO
        }
        return DlnaMedia(
            uri = cleanUri,
            type = type,
            mimeType = mime,
            title = title,
            artist = artist,
            album = album,
            artUri = decodeXml(art),
            lyricsUri = resolveUri(cleanUri, decodeXml(lyricsUri)),
            lyrics = lyrics,
            durationMs = durationMs,
        )
    }

    private fun deviceDescription(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><specVersion><major>1</major><minor>0</minor></specVersion>
        <device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>${xml(friendlyName)}</friendlyName>
        <manufacturer>Open source</manufacturer><manufacturerURL>https://github.com/jqssun/android-airplay-server</manufacturerURL>
        <modelName>Mirror Receiver</modelName><UDN>${xml("uuid:$uuidValue")}</UDN>
        <serviceList>
        <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><serviceId>urn:upnp-org:serviceId:AVTransport</serviceId><SCPDURL>/scpd/AVTransport.xml</SCPDURL><controlURL>/upnp/control/AVTransport</controlURL><eventSubURL>/upnp/event/AVTransport</eventSubURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId><SCPDURL>/scpd/RenderingControl.xml</SCPDURL><controlURL>/upnp/control/RenderingControl</controlURL><eventSubURL>/upnp/event/RenderingControl</eventSubURL></service>
        <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><SCPDURL>/scpd/ConnectionManager.xml</SCPDURL><controlURL>/upnp/control/ConnectionManager</controlURL><eventSubURL>/upnp/event/ConnectionManager</eventSubURL></service>
        </serviceList></device></root>
    """.trimIndent()

    private fun scpd(name: String): String = when (name) {
        "AVTransport" -> scpdAvTransport()
        "RenderingControl" -> scpdRenderingControl()
        "ConnectionManager" -> scpdConnectionManager()
        else -> "<scpd/>"
    }

    private fun scpdAvTransport() = scpdHeader() + listOf(
        action("SetAVTransportURI", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("CurrentURI", "in", "AVTransportURI") + arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")),
        action("Play", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Speed", "in", "TransportPlaySpeed")),
        action("Pause", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")),
        action("Stop", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")),
        action("Seek", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Unit", "in", "A_ARG_TYPE_SeekMode") + arg("Target", "in", "A_ARG_TYPE_SeekTarget")),
        action("GetTransportInfo", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("CurrentTransportState", "out", "TransportState") + arg("CurrentTransportStatus", "out", "TransportStatus") + arg("CurrentSpeed", "out", "TransportPlaySpeed")),
        action("GetPositionInfo", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Track", "out", "NumberOfTracks") + arg("TrackDuration", "out", "CurrentTrackDuration") + arg("TrackMetaData", "out", "CurrentTrackMetaData") + arg("TrackURI", "out", "AVTransportURI") + arg("RelTime", "out", "RelativeTimePosition") + arg("AbsTime", "out", "AbsoluteTimePosition") + arg("RelCount", "out", "RelativeCounterPosition") + arg("AbsCount", "out", "AbsoluteCounterPosition")),
        action("GetMediaInfo", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("NrTracks", "out", "NumberOfTracks") + arg("MediaDuration", "out", "MediaDuration") + arg("CurrentURI", "out", "AVTransportURI") + arg("CurrentURIMetaData", "out", "AVTransportURIMetaData") + arg("NextURI", "out", "NextAVTransportURI") + arg("NextURIMetaData", "out", "NextAVTransportURIMetaData") + arg("PlayMedium", "out", "PlaybackStorageMedium") + arg("RecordMedium", "out", "RecordStorageMedium") + arg("WriteStatus", "out", "RecordMediumWriteStatus")),
        action("GetDeviceCapabilities", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("PlayMedia", "out", "PossiblePlaybackStorageMedia")),
        action("GetTransportSettings", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("PlayMode", "out", "CurrentPlayMode") + arg("RecQualityMode", "out", "CurrentRecordQualityMode")),
        action("GetCurrentTransportActions", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Actions", "out", "CurrentTransportActions")),
    ).joinToString("") + scpdStateTable("TransportState", "string", "TransportStatus", "string")

    private fun scpdRenderingControl() = scpdHeader() + listOf(
        action("GetVolume", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("CurrentVolume", "out", "Volume")),
        action("SetVolume", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("DesiredVolume", "in", "Volume")),
        action("GetVolumeDB", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("CurrentVolumeDB", "out", "VolumeDB")),
        action("SetVolumeDB", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("DesiredVolumeDB", "in", "VolumeDB")),
        action("GetMute", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("CurrentMute", "out", "Mute")),
        action("SetMute", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("DesiredMute", "in", "Mute")),
    ).joinToString("") + scpdStateTable("Volume", "ui2", "VolumeDB", "i4", "Mute", "boolean")

    private fun scpdConnectionManager() = scpdHeader() + listOf(
        action("GetProtocolInfo", arg("Source", "out", "SourceProtocolInfo") + arg("Sink", "out", "SinkProtocolInfo")),
        action("GetCurrentConnectionIDs", arg("ConnectionIDs", "out", "CurrentConnectionIDs")),
        action("GetCurrentConnectionInfo", arg("ConnectionID", "in", "A_ARG_TYPE_ConnectionID") + arg("RcsID", "out", "A_ARG_TYPE_RcsID") + arg("AVTransportID", "out", "A_ARG_TYPE_AVTransportID") + arg("ProtocolInfo", "out", "A_ARG_TYPE_ProtocolInfo") + arg("PeerConnectionManager", "out", "A_ARG_TYPE_ConnectionManager") + arg("PeerConnectionID", "out", "A_ARG_TYPE_ConnectionID") + arg("Direction", "out", "A_ARG_TYPE_Direction") + arg("Status", "out", "A_ARG_TYPE_ConnectionStatus")),
    ).joinToString("") + scpdStateTable("SourceProtocolInfo", "string", "SinkProtocolInfo", "string")

    private fun action(name: String, arguments: String): String =
        "<action><name>$name</name><argumentList>$arguments</argumentList></action>"

    private fun arg(name: String, direction: String, relatedStateVariable: String): String =
        "<argument><name>$name</name><direction>$direction</direction><relatedStateVariable>$relatedStateVariable</relatedStateVariable></argument>"

    private fun scpdStateTable(vararg state: String): String = buildString {
        append("</actionList><serviceStateTable>")
        state.asList().chunked(2).forEach { (name, type) ->
            append("<stateVariable sendEvents=\"yes\"><name>$name</name><dataType>$type</dataType></stateVariable>")
        }
        append("</serviceStateTable></scpd>")
    }

    private fun scpdHeader() = "<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>"

    private fun soapResponse(service: String, action: String, values: String = ""): String {
        val serviceType = when (service) {
            "RenderingControl" -> "urn:schemas-upnp-org:service:RenderingControl:1"
            "ConnectionManager" -> "urn:schemas-upnp-org:service:ConnectionManager:1"
            else -> "urn:schemas-upnp-org:service:AVTransport:1"
        }
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"$serviceType\">$values</u:${action}Response></s:Body></s:Envelope>"
    }

    private fun protocolInfoMime(metadata: String): String =
        Regex("""protocolInfo\s*=\s*"[^:]*:[^:]*:([^:"]+):""", RegexOption.IGNORE_CASE)
            .find(metadata)?.groupValues?.getOrNull(1).orEmpty()

    private fun fault(output: BufferedOutputStream, code: Int, description: String): Nothing {
        val body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>$code</errorCode><errorDescription>$description</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
        respond(output, "500 Internal Server Error", "text/xml; charset=\"utf-8\"", body)
        throw HandledSoapException
    }

    private fun respondXml(output: BufferedOutputStream, body: String) = respond(output, "200 OK", "text/xml; charset=\"utf-8\"", body)

    private fun respond(output: BufferedOutputStream, status: String, contentType: String, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        output.write("HTTP/1.1 $status\r\nCONTENT-TYPE: $contentType\r\nCONTENT-LENGTH: ${bytes.size}\r\nCONNECTION: close\r\n$extraHeaders\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(StandardCharsets.ISO_8859_1.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_HEADER_LINE) return null
        }
        return out.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun readBytes(input: InputStream, count: Int): ByteArray {
        val result = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(result, read, count - read)
            if (n < 0) break
            read += n
        }
        return if (read == count) result else result.copyOf(read)
    }

    private fun readChunkedBytes(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val line = readLine(input) ?: break
            val size = line.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IllegalArgumentException("invalid chunk size")
            if (size == 0) {
                while (readLine(input)?.isNotEmpty() == true) Unit
                break
            }
            if (output.size() + size > MAX_BODY_BYTES) throw IllegalArgumentException("request body too large")
            output.write(readBytes(input, size))
            // Consume the CRLF following the chunk payload.
            readLine(input)
        }
        return output.toByteArray()
    }

    private fun parseHeaders(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
        .toMap()

    private fun tag(xml: String, name: String, caseInsensitive: Boolean = false): String? {
        val flag = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL) else setOf(RegexOption.DOT_MATCHES_ALL)
        return Regex("<(?:[A-Za-z0-9_.-]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?$name>", flag).find(xml)?.groupValues?.get(1)?.trim()
            ?.let(::decodeXml)
    }

    private fun attribute(xml: String, element: String, name: String): String? =
        Regex(
            "<(?:[A-Za-z0-9_.-]+:)?$element\\b[^>]*\\b$name\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(xml)?.groupValues?.getOrNull(1)?.trim()?.let(::decodeXml)

    private fun decodeXml(value: String): String = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun resolveUri(base: String, value: String): String {
        if (value.isBlank()) return ""
        return runCatching { URL(URL(base), value).toString() }.getOrDefault(value)
    }

    private fun parseTime(value: String): Long = runCatching {
        val parts = value.trim().split(':')
        if (parts.size != 3) 0L else {
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val seconds = parts[2].toDouble()
            ((hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()).coerceAtLeast(0L)
        }
    }.getOrDefault(0L)

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%02d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    }

    private fun localIpv4Address(): String {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { iface ->
            if (iface.isUp && !iface.isLoopback) iface.inetAddresses.toList().forEach { address ->
                if (address is Inet4Address && !address.isLoopbackAddress) return address.hostAddress
            }
        }
        return "127.0.0.1"
    }

    private val location: String
        get() {
            ipAddress = localIpv4Address()
            return "http://$ipAddress:$httpPort/description.xml"
        }

    private data class Subscription(val sid: String, val service: String, val callback: String, var sequence: Long = 0)
    private object HandledSoapException : RuntimeException()

    companion object {
        private const val TAG = "DlnaReceiver"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val MAX_BODY_BYTES = 1_048_576
        private const val MAX_HEADER_LINE = 16_384
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")
    }
}
