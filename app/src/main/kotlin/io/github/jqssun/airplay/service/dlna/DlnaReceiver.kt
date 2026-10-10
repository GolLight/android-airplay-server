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
                val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY_BYTES) ?: 0
                val body = readBytes(input, length).toString(StandardCharsets.UTF_8)
                val output = BufferedOutputStream(client.getOutputStream())
                when {
                    method == "GET" && path == "/description.xml" -> respondXml(output, deviceDescription())
                    method == "GET" && path.startsWith("/scpd/") -> respondXml(output, scpd(path.substringAfterLast('/').substringBeforeLast('.')))
                    method == "POST" && path.startsWith("/upnp/control/") -> handleSoap(output, path.substringAfterLast('/'), headers, body)
                    method == "SUBSCRIBE" && path.startsWith("/upnp/event/") -> handleSubscribe(output, path.substringAfterLast('/'), headers)
                    method == "UNSUBSCRIBE" && path.startsWith("/upnp/event/") -> handleUnsubscribe(output, headers)
                    else -> respond(output, "404 Not Found", "text/plain", "Not found")
                }
                output.flush()
            } catch (e: Exception) {
                if (running.get()) Log.d(TAG, "DLNA HTTP request failed", e)
            }
        }
    }

    private fun handleSoap(output: BufferedOutputStream, service: String, headers: Map<String, String>, body: String) {
        val action = headers["soapaction"]?.trim('"')?.substringAfterLast('#')
            ?: tag(body, "Action")?.let { it.substringAfterLast(':') }
            ?: ""
        val response = try {
            when (service to action) {
                "AVTransport" to "SetAVTransportURI" -> {
                    val uri = tag(body, "CurrentURI").orEmpty()
                    val metadata = tag(body, "CurrentURIMetaData").orEmpty()
                    val media = parseMedia(uri, metadata)
                    if (media.uri.isBlank() || !callback.onSetMedia(media)) return fault(output, 701, "Transition not available")
                    currentMedia = media
                    transportState = "STOPPED"
                    soapResponse(service, "SetAVTransportURI")
                }
                "AVTransport" to "Play" -> {
                    if (currentMedia == null) return fault(output, 702, "No content")
                    callback.onPlay(); transportState = "PLAYING"; soapResponse(service, "Play")
                }
                "AVTransport" to "Pause" -> { callback.onPause(); transportState = "PAUSED_PLAYBACK"; soapResponse(service, "Pause") }
                "AVTransport" to "Stop" -> { callback.onStop(); transportState = "STOPPED"; soapResponse(service, "Stop") }
                "AVTransport" to "Seek" -> {
                    val target = tag(body, "Target") ?: "00:00:00"
                    callback.onSeek(parseTime(target)); soapResponse(service, "Seek")
                }
                "AVTransport" to "GetTransportInfo" -> soapResponse(service, "GetTransportInfo", "<CurrentTransportState>$transportState</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>")
                "AVTransport" to "GetPositionInfo" -> {
                    val s = callback.snapshot()
                    soapResponse(service, "GetPositionInfo", "<Track>1</Track><TrackDuration>${formatTime(s.durationMs)}</TrackDuration><TrackMetaData></TrackMetaData><TrackURI>${xml(s.media?.uri.orEmpty())}</TrackURI><RelTime>${formatTime(s.positionMs)}</RelTime><AbsTime>NOT_IMPLEMENTED</AbsTime><RelCount>0</RelCount><AbsCount>0</AbsCount>")
                }
                "AVTransport" to "GetMediaInfo" -> {
                    val s = callback.snapshot()
                    soapResponse(service, "GetMediaInfo", "<NrTracks>1</NrTracks><MediaDuration>${formatTime(s.durationMs)}</MediaDuration><CurrentURI>${xml(s.media?.uri.orEmpty())}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData><NextURI></NextURI><NextURIMetaData></NextURIMetaData><PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium><WriteStatus>NOT_IMPLEMENTED</WriteStatus>")
                }
                "AVTransport" to "GetDeviceCapabilities" -> soapResponse(service, "GetDeviceCapabilities", "<PlayMedia>NETWORK,UNKNOWN</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia><RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>")
                "AVTransport" to "GetTransportSettings" -> soapResponse(service, "GetTransportSettings", "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>")
                "AVTransport" to "GetCurrentTransportActions" -> soapResponse(service, "GetCurrentTransportActions", "<Actions>Play,Pause,Stop,Seek</Actions>")
                "RenderingControl" to "GetVolume" -> soapResponse(service, "GetVolume", "<CurrentVolume>$volume</CurrentVolume>")
                "RenderingControl" to "SetVolume" -> {
                    val value = tag(body, "DesiredVolume")?.toIntOrNull()?.coerceIn(0, 100) ?: volume
                    volume = value; callback.onSetVolume(value); soapResponse(service, "SetVolume")
                }
                "RenderingControl" to "GetMute" -> soapResponse(service, "GetMute", "<CurrentMute>${if (muted) 1 else 0}</CurrentMute>")
                "RenderingControl" to "SetMute" -> {
                    muted = tag(body, "DesiredMute") == "1"; callback.onSetMute(muted); soapResponse(service, "SetMute")
                }
                "ConnectionManager" to "GetProtocolInfo" -> soapResponse(service, "GetProtocolInfo", "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/*:*,http-get:*:audio/*:*,http-get:*:image/*:*</Sink>")
                "ConnectionManager" to "GetCurrentConnectionIDs" -> soapResponse(service, "GetCurrentConnectionIDs", "<ConnectionIDs>0</ConnectionIDs>")
                "ConnectionManager" to "GetCurrentConnectionInfo" -> soapResponse(service, "GetCurrentConnectionInfo", "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID><Direction>Input</Direction><Status>OK</Status>")
                else -> return fault(output, 401, "Invalid Action")
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
        val sid = headers["sid"]?.takeIf { it.startsWith("uuid:") } ?: "uuid:${UUID.randomUUID()}"
        val callbackUrl = headers["callback"]?.let { Regex("<([^>]+)>").find(it)?.groupValues?.get(1) }
        if (callbackUrl != null) subscriptions[sid] = Subscription(sid, service, callbackUrl)
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
            val event = when (subscription.service) {
                "AVTransport" -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\"><TransportState val=\"${transportState}\"/><CurrentTrackURI val=\"${xml(snapshot.media?.uri.orEmpty())}\"/><RelativeTimePosition val=\"${formatTime(snapshot.positionMs)}\"/></InstanceID></Event>"
                "RenderingControl" -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\"><Volume channel=\"Master\" val=\"$volume\"/><Mute channel=\"Master\" val=\"${if (muted) 1 else 0}\"/></InstanceID></Event>"
                else -> "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/CM/\"><ConnectionManagerState val=\"OK\"/></Event>"
            }
            val bytes = event.toByteArray(StandardCharsets.UTF_8)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(url.host, if (url.port > 0) url.port else 80), 3000)
                val out = socket.getOutputStream()
                out.write("NOTIFY ${url.path.ifEmpty { "/" }} HTTP/1.1\r\nHOST: ${url.host}:${if (url.port > 0) url.port else 80}\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: ${subscription.sid}\r\nSEQ: ${subscription.sequence++}\r\nCONTENT-LENGTH: ${bytes.size}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                out.write(bytes); out.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "DLNA event failed", e)
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
        val type = when {
            mime.startsWith("image/", true) || cleanUri.substringBefore('?').substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS -> DlnaMediaType.IMAGE
            mime.startsWith("audio/", true) || cleanUri.substringBefore('?').substringAfterLast('.').lowercase() in AUDIO_EXTENSIONS -> DlnaMediaType.AUDIO
            else -> DlnaMediaType.VIDEO
        }
        return DlnaMedia(cleanUri, type, mime, title, artist, album, decodeXml(art))
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
        action("GetMute", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("CurrentMute", "out", "Mute")),
        action("SetMute", arg("InstanceID", "in", "A_ARG_TYPE_InstanceID") + arg("Channel", "in", "A_ARG_TYPE_Channel") + arg("DesiredMute", "in", "Mute")),
    ).joinToString("") + scpdStateTable("Volume", "ui2", "Mute", "boolean")

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

    private fun parseHeaders(text: String): Map<String, String> = text.lineSequence()
        .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
        .toMap()

    private fun tag(xml: String, name: String, caseInsensitive: Boolean = false): String? {
        val flag = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL) else setOf(RegexOption.DOT_MATCHES_ALL)
        return Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", flag).find(xml)?.groupValues?.get(1)?.trim()
            ?.let(::decodeXml)
    }

    private fun decodeXml(value: String): String = value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

    private fun parseTime(value: String): Long = runCatching {
        val parts = value.trim().split(':')
        if (parts.size != 3) 0L else ((parts[0].toLong() * 3600 + parts[1].toLong() * 60) * 1000 + parts[2].toDouble().toLong() * 1000)
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
