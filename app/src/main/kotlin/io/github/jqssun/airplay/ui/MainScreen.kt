package io.github.jqssun.airplay.ui

import android.app.Activity
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.gestures.BrightnessState
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.SeekGestureState
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalGesture
import io.github.jqssun.airplay.ui.gestures.VideoContentScale
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoPlayerGestures
import io.github.jqssun.airplay.ui.gestures.VolumeAndBrightnessGestureState
import io.github.jqssun.airplay.ui.gestures.VolumeState
import io.github.jqssun.airplay.ui.gestures.ZoomState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.material3.MiniController
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.json.JSONArray
import org.json.JSONObject

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    autoSession: Boolean = false,
    playbackLaunchId: Long = 0L,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit = {},
    onLocalNetwork: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember(autoSession) { mutableStateOf(autoSession) }
    LaunchedEffect(playbackLaunchId) {
        if (playbackLaunchId > 0L) {
            viewModel.updateFromService()
            fullscreen = true
        }
    }
    val pin by viewModel.pinCode.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val dlnaActive by viewModel.dlnaActive.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val videoSessionPending by viewModel.videoSessionPending.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val autoFullscreen by viewModel.pref(Prefs.AUTO_FULLSCREEN).collectAsState()

    // don't use movableContentOf: moving AndroidView across subcomposition boundaries makes it crash on reparent
    val video: @Composable () -> Unit = {
        val aspect by viewModel.videoAspect.collectAsState()
        val resolution by viewModel.videoResolution.collectAsState()
        val mode = LocalView.current.display?.mode
        val bufferSize = if (fullscreen && mirroringActive && !isInPip && isTv() && mode != null) {
            remember(resolution, mode.physicalWidth, mode.physicalHeight) {
                mirrorSurfaceBufferSize(resolution, mode.physicalWidth, mode.physicalHeight)
            }
        } else null
        VideoSurfaceView(
            onSurfaceAvailable = onSurfaceAvailable,
            onSurfaceDestroyed = onSurfaceDestroyed,
            aspectRatio = aspect,
            bufferSizePx = bufferSize
        )
    }

    // fullscreen only once mirroring reports a size: connections rise before the kind is known
    var prevMirroringActive by remember { mutableStateOf(false) }
    LaunchedEffect(mirroringActive, audioOnly, videoPlaybackActive, pin) {
        val justStarted = !prevMirroringActive && mirroringActive
        prevMirroringActive = mirroringActive
        if (justStarted && !audioOnly && !videoPlaybackActive && autoFullscreen && pin == null) {
            fullscreen = true
        }
    }

    // manual fullscreen over the idle preview is deliberate; only undo on disconnect
    LaunchedEffect(connections, dlnaActive, autoSession) {
        if (connections == 0 && !dlnaActive && !autoSession) fullscreen = false
    }

    // leaving video playback must not fall through to a stale mirroring fullscreen
    LaunchedEffect(videoPlaybackActive) {
        if (videoPlaybackActive) fullscreen = false
    }

    LaunchedEffect(autoSession, audioOnly, videoPlaybackActive) {
        if (autoSession && videoPlaybackActive) fullscreen = false
    }

    // Both music protocols share the same full-screen presentation.
    LaunchedEffect(audioOnly, dlnaActive, connections, videoPlaybackActive) {
        if (audioOnly && (dlnaActive || connections > 0)) {
            fullscreen = true
        }
    }

    val activity = LocalContext.current as? Activity
    val videoScreen = videoPlaybackActive || videoSessionPending
    LaunchedEffect(fullscreen, videoScreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen || videoScreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (videoScreen) {
        val videoPlaybackAspect by viewModel.videoPlaybackAspect.collectAsState()
        val videoPreview by viewModel.dlnaVideoPreview.collectAsState()
        val playback: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.Center) {
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    aspectRatio = videoPlaybackAspect
                )
                videoPreview?.let { preview ->
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier.aspectRatio(videoPlaybackAspect, matchHeightConstraintsFirst = videoPlaybackAspect < 1f).fillMaxSize(),
                    )
                }
            }
        }
        if (isInPip) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                playback()
            }
            return
        }
        val overlayTick by viewModel.videoOverlayTick.collectAsState()
        val playing by viewModel.videoPlaying.collectAsState()
        val positionMs by viewModel.videoPositionMs.collectAsState()
        val durationMs by viewModel.videoDurationMs.collectAsState()
        val scrubPositionMs by viewModel.videoScrubPositionMs.collectAsState()
        val downloadProgress by viewModel.videoDownloadProgress.collectAsState()
        val scrubbing = scrubPositionMs != null
        val downloading = downloadProgress != null
        var overlayVisible by remember { mutableStateOf(false) }
        LaunchedEffect(overlayTick, playing, scrubbing, downloading, videoPlaybackActive) {
            // tick 0 = fresh session; a pending session or a running download pins the overlay
            if (!videoPlaybackActive) {
                overlayVisible = true
            } else if (overlayTick == 0L) {
                overlayVisible = false
            } else {
                overlayVisible = true
                if (playing && !scrubbing && !downloading) {
                    delay(VIDEO_OVERLAY_HIDE_MS)
                    overlayVisible = false
                }
            }
        }
        val gestureScope = rememberCoroutineScope()
        val tapGestureState = remember(viewModel) { TapGestureState(viewModel, gestureScope) }
        val seekGestureState = remember(viewModel) { SeekGestureState(viewModel) }
        val context = LocalContext.current
        val volumeState = remember { VolumeState(context) }
        val brightnessState = remember(activity) { activity?.window?.let { BrightnessState(it) } }
        val volumeAndBrightnessGestureState = remember(volumeState, brightnessState) {
            VolumeAndBrightnessGestureState(volumeState, brightnessState, gestureScope)
        }
        val zoomState = remember(viewModel) { ZoomState(viewModel, gestureScope) }
        var controlsLocked by remember { mutableStateOf(false) }
        DisposableEffect(volumeState) { volumeState.handleLifecycle(this) }
        LaunchedEffect(overlayTick) {
            if (overlayTick == 0L) {
                zoomState.reset()
                controlsLocked = false
            }
        }
        DisposableEffect(brightnessState) {
            onDispose { brightnessState?.clearOverride() }
        }
        // orientation follows the video aspect; manual rotate holds until the next video
        LaunchedEffect(videoPlaybackAspect) {
            activity?.requestedOrientation = if (videoPlaybackAspect < 1f) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        DisposableEffect(activity) {
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        val videoPlaybackSize by viewModel.videoPlaybackSize.collectAsState()
        val buffering by viewModel.videoBuffering.collectAsState()
        val videoTitle by viewModel.videoTitle.collectAsState()
        val videoLocation by viewModel.videoLocation.collectAsState()
        val speed by viewModel.videoSpeed.collectAsState()
        val isPipSupported = remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }
        var showSpeedSelector by remember { mutableStateOf(false) }
        BackHandler { viewModel.stopVideoPlayback() }

        val rootFocusRequester = remember { FocusRequester() }
        val playPauseFocusRequester = remember { FocusRequester() }
        val unlockFocusRequester = remember { FocusRequester() }
        var isPlayPauseFocused by remember { mutableStateOf(false) }
        var isUnlockFocused by remember { mutableStateOf(false) }
        LaunchedEffect(overlayVisible, controlsLocked, showSpeedSelector) {
            if (showSpeedSelector) return@LaunchedEffect
            if (!overlayVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsLocked
            val target = if (locked) unlockFocusRequester else playPauseFocusRequester
            target.requestFocusUntilLanded(attempts = 20) { if (locked) isUnlockFocused else isPlayPauseFocused }
        }

        // dpad seeking (controls hidden): accumulate the skipped amount and briefly show it
        var dpadSeekOffsetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekTargetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekActive by remember { mutableStateOf(false) }
        var dpadSeekTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(dpadSeekTick) {
            if (!dpadSeekActive) return@LaunchedEffect
            delay(1000)
            dpadSeekActive = false
        }
        val showDpadSeekFeedback: (Long) -> Unit = { deltaMs ->
            if (!dpadSeekActive) dpadSeekOffsetMs = 0L
            dpadSeekOffsetMs += deltaMs
            dpadSeekTargetMs = (dpadSeekTargetMs.takeIf { dpadSeekActive } ?: positionMs).plus(deltaMs)
                .coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
            dpadSeekActive = true
            dpadSeekTick++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (showSpeedSelector) {
                        false
                    } else {
                        handlePlayerKeyEvent(
                            keyEvent = keyEvent,
                            controlsVisible = overlayVisible,
                            controlsLocked = controlsLocked,
                            isPlayPauseFocused = isPlayPauseFocused,
                            seekIncrementMs = TapGestureState.SEEK_INCREMENT_MS,
                            viewModel = viewModel,
                            showControls = { viewModel.showVideoOverlay() },
                            unlockControls = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            onDpadSeek = showDpadSeekFeedback,
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            VideoContentFrame(
                aspect = videoPlaybackAspect,
                videoSizePx = videoPlaybackSize,
                contentScale = zoomState.contentScale,
                zoom = zoomState.zoom
            ) { sizeModifier ->
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    applyAspectRatio = false,
                    modifier = sizeModifier
                )
            }
            VideoPlayerGestures(
                enabled = videoPlaybackActive,
                locked = controlsLocked,
                onTap = {
                    // a pending session pins the overlay; taps must not unpin it
                    if (!videoPlaybackActive) return@VideoPlayerGestures
                    if (overlayVisible) overlayVisible = false else viewModel.showVideoOverlay()
                },
                tapGestureState = tapGestureState,
                seekGestureState = seekGestureState,
                volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                zoomState = zoomState
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(72.dp))
            }
            DoubleTapIndicator(tapGestureState = tapGestureState)
            val seekAmountMs = seekGestureState.seekAmountMs
            when {
                seekAmountMs != null -> GestureInfoText(
                    info = "${if (seekAmountMs < 0) "-" else "+"}${formatVideoTime(abs(seekAmountMs))}\n" +
                        "[${formatVideoTime(seekGestureState.targetPositionMs ?: 0)}]",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.isZooming -> GestureInfoText(
                    info = "${(zoomState.zoom * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.showContentScaleIndicator -> GestureInfoText(
                    info = stringResource(zoomState.contentScale.nameRes()),
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    PlayPauseButton(
                        playing = playing,
                        onClick = { viewModel.toggleVideoPlayPause() },
                        modifier = Modifier
                            .focusRequester(playPauseFocusRequester)
                            .onFocusChanged { isPlayPauseFocused = it.hasFocus }
                    )
                }
            }
            DpadSeekIndicator(
                visible = dpadSeekActive && dpadSeekOffsetMs != 0L,
                offsetMs = dpadSeekOffsetMs,
                positionMs = dpadSeekTargetMs
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = volumeState.percentage, icon = Icons.AutoMirrored.Rounded.VolumeUp)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = brightnessState?.percentage ?: 0, icon = Icons.Rounded.BrightnessHigh)
            }
            if (controlsLocked) {
                if (overlayVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp)
                    ) {
                        UnlockButton(
                            onClick = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            modifier = Modifier
                                .focusRequester(unlockFocusRequester)
                                .onFocusChanged { isUnlockFocused = it.hasFocus }
                        )
                    }
                }
            } else {
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    VideoControlsTop(
                        title = videoTitle,
                        videoUrl = videoLocation,
                        downloadProgress = downloadProgress,
                        showDownload = durationMs > 0,
                        onBackClick = { viewModel.stopVideoPlayback() },
                        onSpeedClick = {
                            overlayVisible = false
                            showSpeedSelector = true
                        },
                        onDownloadClick = { viewModel.toggleVideoDownload() }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    VideoControlsBottom(
                        positionMs = scrubPositionMs ?: positionMs,
                        durationMs = durationMs,
                        contentScale = zoomState.contentScale,
                        isPipSupported = isPipSupported,
                        onRotateClick = {
                            activity?.let {
                                it.requestedOrientation =
                                    if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                            }
                        },
                        onLockClick = {
                            viewModel.showVideoOverlay()
                            controlsLocked = true
                        },
                        onContentScaleClick = {
                            viewModel.showVideoOverlay()
                            zoomState.switchToNextContentScale()
                        },
                        onPipClick = onPip,
                        onSeek = { seekGestureState.onSeek(it) },
                        onSeekEnd = { seekGestureState.onSeekEnd() },
                        seekBarModifier = Modifier
                            .focusProperties { up = playPauseFocusRequester }
                            .dpadAdjust(
                                onLeft = {
                                    viewModel.seekVideoBy(-TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                },
                                onRight = {
                                    viewModel.seekVideoBy(TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                }
                            )
                    )
                }
            }
            val skipSilence by viewModel.videoSkipSilence.collectAsState()
            PlaybackSpeedSelector(
                show = showSpeedSelector,
                speed = speed,
                skipSilence = skipSilence,
                onSpeedChange = { viewModel.setVideoSpeed(it) },
                onSkipSilenceChange = { viewModel.setVideoSkipSilence(it) },
                onDismiss = { showSpeedSelector = false }
            )
        }
        return
    }

    // pip mode: show only the video surface
    if (isInPip) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            video()
        }
        return
    }

    // restore system bars when exiting pip back to non-fullscreen
    LaunchedEffect(isInPip) {
        if (!isInPip && !fullscreen) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // exit fullscreen while a pin is being shown so the dialog isn't covered
    LaunchedEffect(pin) {
        if (pin != null) fullscreen = false
    }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        if (audioOnly) {
            FullscreenMusic(
                viewModel = viewModel,
                isInPip = isInPip,
                onExitFullscreen = { fullscreen = false },
            )
        } else {
            FullscreenVideo(
                viewModel = viewModel,
                video = video,
                onExitFullscreen = { fullscreen = false },
                onPip = onPip
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                Column {
                    AudioMiniController(
                        viewModel,
                        visible = audioOnly && connections > 0 && !dlnaActive && tab != Tab.OVERVIEW,
                        onClick = { tab = Tab.OVERVIEW }
                    )
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.labelRes)) },
                                modifier = Modifier.dpadFocus()
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                TabContent(
                    tab, viewModel, video,
                    onFullscreen = { fullscreen = true }, onPip = onPip, onLocalNetwork = onLocalNetwork,
                    showAudioMode = audioOnly
                )
            }
        }
    }

    // pin dialog
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(stringResource(R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }

}

@Composable
private fun TabContent(
    tab: Tab,
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean
) {
    when (tab) {
        Tab.OVERVIEW -> OverviewContent(
            viewModel, video,
            onFullscreen = onFullscreen, onPip = onPip, onLocalNetwork = onLocalNetwork, showAudioMode = showAudioMode
        )
        Tab.LOGS -> LogsScreen(viewModel)
        Tab.SETTINGS -> SettingsScreen(viewModel)
    }
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean = false
) {
    val state by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val grant = state != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val idlePreview by viewModel.pref(Prefs.IDLE_PREVIEW).collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val dlnaActive by viewModel.dlnaActive.collectAsState()
    val dlnaImage by viewModel.dlnaImage.collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val tv = isTv()
    val activeConnections = if (dlnaActive) maxOf(1, connections) else connections
    val hasSession = activeConnections > 0
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tv) startFocus.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val connecting = state == ServerState.RUNNING && hasSession &&
                !mirroringActive && !videoPlaybackActive && dlnaImage == null
            if (showAudioMode && state == ServerState.RUNNING && hasSession) {
                NowPlayingContent(viewModel)
            } else {
                if (dlnaImage != null) {
                    Image(
                        bitmap = dlnaImage!!.asImageBitmap(),
                        contentDescription = stringResource(R.string.dlna_image),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else if (state == ServerState.RUNNING && (mirroringActive || idlePreview)) {
                    video()
                }
                if (state != ServerState.RUNNING || !hasSession) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (hasSession) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (grant) stringResource(R.string.local_network_desc) else when (state) {
                                ServerState.STOPPED -> stringResource(R.string.server_stopped)
                                ServerState.RUNNING -> stringResource(R.string.waiting_for_connection)
                                ServerState.ERROR -> stringResource(R.string.error_starting_server)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else if (connecting) {
                    Text(
                        text = stringResource(R.string.waiting_for_playback),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (state == ServerState.RUNNING && mirroringActive) {
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                            Icon(
                                painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.dpadFocus()) {
                            Icon(
                                Icons.Default.Fullscreen, contentDescription = stringResource(R.string.cd_fullscreen),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            if (debugEnabled && hasSession) {
                DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            var showRes by remember { mutableStateOf(false) }
            LaunchedEffect(videoResolution) {
                if (videoResolution.isNotEmpty() && !showAudioMode) {
                    showRes = true
                    delay(5000)
                    showRes = false
                }
            }
            if (!showAudioMode) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showRes && hasSession,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                ) {
                    Text(
                        text = videoResolution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        val statusColor by animateColorAsState(
                            if (grant) MaterialTheme.colorScheme.error else when (state) {
                                ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                                ServerState.ERROR -> MaterialTheme.colorScheme.error
                                ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, label = "status"
                        )
                        Text(
                            text = if (grant) stringResource(R.string.local_network_label) else when (state) {
                                ServerState.RUNNING -> stringResource(R.string.connected_count, activeConnections)
                                ServerState.ERROR -> stringResource(R.string.error_label)
                                ServerState.STOPPED -> stringResource(R.string.stopped_label)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }

                    val settings = localNetwork == LocalNetworkAccess.SETTINGS
                    val (icon, label, action) = when {
                        grant -> Triple(
                            if (settings) Icons.Default.Settings else Icons.Default.LockOpen,
                            if (settings) R.string.btn_open_settings else R.string.btn_allow,
                            onLocalNetwork
                        )
                        state == ServerState.RUNNING -> Triple(Icons.Default.Stop, R.string.btn_stop, viewModel::stopServer)
                        else -> Triple(Icons.Default.PlayArrow, R.string.btn_start, viewModel::startServer)
                    }
                    FilledTonalButton(
                        onClick = action,
                        modifier = Modifier.dpadFocus().focusRequester(startFocus)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenVideo(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onExitFullscreen: () -> Unit,
    onPip: () -> Unit
) {
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    var tapTick by remember { mutableStateOf(0) }
    LaunchedEffect(tapTick) {
        controlsVisible = true
        delay(8000)
        controlsVisible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { tapTick++ } },
        contentAlignment = Alignment.Center
    ) {
        video()
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                    Icon(
                        painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onExitFullscreen, modifier = Modifier.dpadFocus()) {
                    Icon(
                        Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.cd_exit_fullscreen),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (debugEnabled) {
            DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        var showRes by remember { mutableStateOf(false) }
        LaunchedEffect(videoResolution) {
            if (videoResolution.isNotEmpty()) {
                showRes = true
                delay(5000)
                showRes = false
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showRes,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) {
            Text(
                text = videoResolution,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

// dacp scanning is press-and-hold: beginff/beginrew while held, playresume on release
@Composable
private fun HoldScanButton(
    icon: ImageVector,
    contentDescription: String,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onBegin()
                    try {
                        awaitRelease()
                    } finally {
                        onEnd()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription)
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun AudioMiniController(viewModel: MainViewModel, visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    val player = viewModel.dacpPlayer ?: return
    val context = LocalContext.current
    MiniController(
        player,
        bitmapLoader = remember { DataSourceBitmapLoader(context) },
        onClick = onClick
    )
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun FullscreenMusic(
    viewModel: MainViewModel,
    isInPip: Boolean,
    onExitFullscreen: () -> Unit,
) {
    MusicNowPlayingContent(
        viewModel = viewModel,
        fullScreen = true,
        isInPip = isInPip,
        onExitFullscreen = onExitFullscreen,
    )
}

private data class DlnaLyricLine(
    val timestampMs: Long?,
    val text: String,
)

private val DLNA_LRC_TIMESTAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")
private val DLNA_YRC_LINE = Regex("""\{\s*\"t\"\s*:\s*(\d+)\s*,\s*\"c\"\s*:\s*\Q[\E(.*?)\Q]\E\s*\}""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val DLNA_YRC_TEXT = Regex("""\"tx\"\s*:\s*\"((?:\\\\.|[^\"\\\\])*)\"""")

private fun decodeJsonString(value: String): String = runCatching {
    org.json.JSONTokener("\"$value\"").nextValue() as String
}.getOrDefault(value)

private fun findLyricsInJson(value: Any?): String? {
    when (value) {
        is JSONObject -> {
            // NetEase and several DLNA senders wrap LRC/YRC in lrc, yrc or
            // lyrics objects. Prefer the original timed lyric over translation.
            listOf("lrc", "yrc", "lyrics", "lyric", "unsyncedLyrics", "tlyric", "klyric").forEach { key ->
                val child = value.opt(key)
                val found = when (child) {
                    is String -> child.takeIf { it.isNotBlank() }
                    else -> findLyricsInJson(child)
                }
                if (!found.isNullOrBlank()) return found
            }
            val keys = value.keys()
            while (keys.hasNext()) {
                findLyricsInJson(value.opt(keys.next()))?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        is JSONArray -> {
            for (index in 0 until value.length()) {
                findLyricsInJson(value.opt(index))?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
    }
    return null
}

private fun lyricPayload(raw: String): String {
    var value = raw.trim()
    repeat(2) {
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>") && value.length >= 12) {
            value = value.substring(9, value.length - 3).trim()
        }
        // Keep JSON escape sequences intact until JSONObject has decoded the
        // payload; converting \\n inside a JSON string first would invalidate it.
        if (value.contains('<') && value.contains('>')) {
            // Preserve plain-text LRC line breaks; Html.fromHtml() collapses them.
            value = Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        }
    }
    val jsonLyrics = runCatching { findLyricsInJson(JSONObject(value)) }.getOrNull()
        ?: value.takeIf { it.startsWith("[") || it.contains("\"t\"") }
    return (jsonLyrics ?: value).let(::decodeJsonString)
        .replace("\\r\\n", "\n")
        .replace("\\n", "\n")
}

private fun parseDlnaLyrics(raw: String): List<DlnaLyricLine> {
    if (raw.isBlank()) return emptyList()
    // DLNA metadata commonly uses HTML <br> tags or an LRC payload. Decode
    // both forms so the renderer can lay out each lyric line separately.
    val decoded = lyricPayload(raw)
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace('\r', '\n')
        .replace('\u2028', '\n')

    // NetEase's YRC endpoint returns one JSON object per line with a
    // millisecond timestamp and one or more {"tx": "..."} fragments.
    val yrcLines = DLNA_YRC_LINE.findAll(decoded).mapNotNull { match ->
        val timestamp = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
        val text = DLNA_YRC_TEXT.findAll(match.groupValues[2])
            .joinToString(separator = "") { decodeJsonString(it.groupValues[1]) }
            .trim()
        text.takeIf { it.isNotBlank() }?.let { DlnaLyricLine(timestamp, it) }
    }.toList()
    if (yrcLines.isNotEmpty()) return yrcLines.sortedBy { it.timestampMs ?: Long.MAX_VALUE }

    return decoded.lineSequence().flatMap { rawLine ->
        val line = rawLine.trim()
        if (line.isBlank()) return@flatMap emptySequence()
        val timestamps = DLNA_LRC_TIMESTAMP.findAll(line).toList()
        val text = line.replace(DLNA_LRC_TIMESTAMP, "").trim()
        if (text.isBlank()) return@flatMap emptySequence()
        if (timestamps.isEmpty()) {
            sequenceOf(DlnaLyricLine(timestampMs = null, text = text))
        } else {
            timestamps.asSequence().map { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fractionText = match.groupValues[3].padEnd(3, '0').take(3)
                val fractionMs = fractionText.toLongOrNull() ?: 0L
                DlnaLyricLine(
                    timestampMs = minutes * 60_000L + seconds * 1_000L + fractionMs,
                    text = text,
                )
            }
        }
    }.toList()
}

private fun activeDlnaLyricIndex(lines: List<DlnaLyricLine>, positionMs: Long): Int {
    var timed = false
    var active = 0
    var latest = Long.MIN_VALUE
    lines.forEachIndexed { index, line ->
        val timestamp = line.timestampMs ?: return@forEachIndexed
        timed = true
        if (timestamp <= positionMs && timestamp > latest) {
            latest = timestamp
            active = index
        }
    }
    return if (timed) active else -1
}

@Composable
private fun DlnaCoverArt(track: TrackInfo, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (track.coverArt != null) {
            Image(
                bitmap = track.coverArt!!.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_cover_art),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun DlnaVinylArt(
    coverArt: Bitmap?,
    playing: Boolean,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val coverImage = remember(coverArt) { coverArt?.asImageBitmap() }
    var rotation by remember { mutableFloatStateOf(0f) }
    // The composable is also used while the activity is transitioning to
    // PiP/background. Stop the frame clock immediately in those states.
    LaunchedEffect(playing, visible) {
        if (!playing || !visible) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (isActive) {
            val frame = withFrameNanos { it }
            rotation = (rotation + (frame - lastFrame) / 1_000_000_000f * 9f) % 360f
            lastFrame = frame
        }
    }
    Box(
        modifier = modifier.shadow(24.dp, CircleShape).padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The grooves and outer shading are rotationally symmetrical, so keep
        // them in a static layer. This prevents a full Canvas redraw on every
        // animation tick; only the album-art layer below receives rotation.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val radius = size.minDimension / 2f
                    val discBrush = Brush.radialGradient(
                        listOf(Color(0xFF222226), Color(0xFF070709), Color(0xFF161619)),
                    )
                    val ringColors = List(52) { ring ->
                        Color.White.copy(alpha = if (ring % 3 == 0) 0.055f else 0.025f)
                    }
                    val ringStroke = 0.6.dp.toPx()
                    val edgeStroke = 1.dp.toPx()
                    onDrawBehind {
                        drawCircle(discBrush, radius)
                        repeat(52) { ring ->
                            drawCircle(
                                color = ringColors[ring],
                                radius = radius * (0.65f + ring * 0.0065f),
                                style = Stroke(width = ringStroke),
                            )
                        }
                        drawCircle(
                            Color.White.copy(alpha = 0.12f),
                            radius - edgeStroke,
                            style = Stroke(edgeStroke),
                        )
                    }
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.62f)
                .clip(CircleShape)
                .background(Color(0xFF343438)),
            contentAlignment = Alignment.Center,
        ) {
            // Rotate only the cover image. The record grooves, shadow and
            // center background remain cached and visually unchanged.
            Box(
                modifier = Modifier.fillMaxSize().graphicsLayer { rotationZ = rotation },
                contentAlignment = Alignment.Center,
            ) {
                if (coverImage != null) {
                    Image(
                        coverImage,
                        stringResource(R.string.cd_cover_art),
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Icon(Icons.Default.MusicNote, null, Modifier.size(64.dp), tint = Color.White.copy(alpha = 0.65f))
                }
            }
        }
    }
}

@Composable
private fun DlnaTrackMetadata(track: TrackInfo, large: Boolean, light: Boolean = false) {
    val titleColor = if (light) Color.White else LocalContentColor.current
    val secondaryColor = if (light) Color.White.copy(alpha = 0.78f) else MaterialTheme.colorScheme.onSurfaceVariant
    val artistAlbum = listOf(track.artist, track.album)
        .filter(String::isNotBlank)
        .joinToString(" · ")
    Column(verticalArrangement = Arrangement.spacedBy(if (large) 6.dp else 2.dp)) {
        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (artistAlbum.isNotEmpty()) {
            Text(
                text = artistAlbum,
                style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                color = secondaryColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DlnaLyrics(
    lines: List<DlnaLyricLine>,
    activeIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier,
    large: Boolean,
    light: Boolean = false,
    live: Boolean = false,
) {
    if (lines.isEmpty()) {
        // Keep the viewport's size even when there is no lyric text. The
        // fullscreen transport row is laid out after this viewport; removing
        // it entirely would make the controls jump toward the center.
        Spacer(modifier)
        return
    }
    val current = activeIndex.takeIf { it in lines.indices } ?: lines.lastIndex
    val liveTailOffset = if (live && current == lines.lastIndex) 10.dp else 0.dp
    // Keep individual rows alive. Replacing/fading the whole viewport causes
    // a blank gap on every packet and makes already-late live lyrics feel later.
    BoxWithConstraints(modifier = modifier.offset(y = liveTailOffset).clipToBounds()) {
        val rowHeight = if (large) {
            (maxHeight / 4).coerceIn(46.dp, 60.dp)
        } else {
            (maxHeight / 4).coerceIn(38.dp, 50.dp)
        }
        val rowPx = with(LocalDensity.current) { rowHeight.toPx() }
        var positioned by remember(listState) { mutableStateOf(false) }
        LaunchedEffect(current, rowPx, listState, live) {
            if (!positioned) {
                listState.scrollToItem(current)
                positioned = true
            } else {
                val scroll = listState.firstVisibleItemIndex * rowPx + listState.firstVisibleItemScrollOffset
                val distance = current * rowPx - scroll
                if (abs(distance) > rowPx * 3) listState.scrollToItem(current)
                else listState.animateScrollBy(distance, tween(if (live) 140 else 340))
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = false,
            contentPadding = PaddingValues(vertical = ((maxHeight - rowHeight) / 2).coerceAtLeast(0.dp)),
        ) {
            itemsIndexed(lines, key = { index, line -> "${line.timestampMs}:$index" }) { index, line ->
                val baseColor = if (light) Color.White else MaterialTheme.colorScheme.onSurface
                val opacity = when {
                    index == current -> 1f
                    abs(index - current) == 1 -> 0.58f
                    else -> 0.32f
                }
                // The new current line is visible at once. Only old lines fade
                // to their secondary color while the list travels one row.
                val animatedColor by animateColorAsState(baseColor.copy(alpha = opacity), tween(220), label = "lyric-color")
                Box(
                    modifier = Modifier.fillMaxWidth().height(rowHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = line.text,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (index == current) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (index == current) baseColor else animatedColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberDlnaTransportFocus(): DlnaTransportFocus = remember { DlnaTransportFocus() }

private class DlnaTransportFocus {
    val rewind = FocusRequester()
    val play = FocusRequester()
    val forward = FocusRequester()
    val stop = FocusRequester()
}

@Composable
private fun DlnaProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    seekEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val progress = if (durationMs > 0L) {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(progress) }
    val seek by rememberUpdatedState(onSeek)

    fun seekAt(fraction: Float) {
        if (!seekEnabled || durationMs <= 0L) return
        seek((fraction.coerceIn(0f, 1f) * durationMs).roundToLong())
    }

    val displayedProgress = if (dragging) dragProgress else progress
    Column(modifier = modifier) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(durationMs, seekEnabled) {
                    if (!seekEnabled) return@pointerInput
                    detectTapGestures { offset ->
                        val fraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragProgress = fraction
                        seekAt(fraction)
                    }
                }
                .pointerInput(durationMs, seekEnabled) {
                    if (!seekEnabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            dragProgress = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            seekAt(dragProgress)
                        },
                        onDragEnd = {
                            dragging = false
                            seekAt(dragProgress)
                        },
                        onDragCancel = { dragging = false },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val next = (dragProgress + dragAmount.x / size.width.toFloat()).coerceIn(0f, 1f)
                            dragProgress = next
                            seekAt(next)
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.28f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth(displayedProgress)
                    .height(4.dp)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White),
            )
            Box(
                modifier = Modifier
                    .size(if (dragging) 16.dp else 12.dp)
                    .offset(x = maxWidth * displayedProgress - if (dragging) 8.dp else 6.dp)
                    .align(Alignment.CenterStart)
                    .background(Color.White, CircleShape),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatVideoTime(if (dragging) (dragProgress * durationMs).roundToLong() else positionMs), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
            Text(formatVideoTime(durationMs), color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun DlnaTransportControls(
    viewModel: MainViewModel,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Int,
    muted: Boolean,
    fullScreen: Boolean,
    showVolumeFeedback: Boolean,
    focus: DlnaTransportFocus,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val dlnaActive by viewModel.dlnaActive.collectAsState()
    val sender = viewModel.dacpPlayer
    CompositionLocalProvider(
        LocalContentColor provides if (fullScreen) Color.White else LocalContentColor.current,
    ) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fullScreen) {
                DlnaProgressBar(
                    positionMs = positionMs,
                    durationMs = durationMs,
                    onSeek = { if (dlnaActive) viewModel.seekDlna(it) },
                    seekEnabled = dlnaActive,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    formatVideoTime(positionMs),
                    color = LocalContentColor.current,
                    modifier = Modifier.padding(end = 8.dp),
                )
                LinearProgressIndicator(
                    progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    formatVideoTime(durationMs),
                    color = LocalContentColor.current,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (fullScreen) {
                if (compact) 18.dp else 36.dp
            } else 14.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            IconButton(
                onClick = { if (dlnaActive) viewModel.seekDlnaBy(-10_000L) else sender?.seekToPreviousMediaItem() },
                modifier = Modifier
                    .size(if (fullScreen) {
                        if (compact) 48.dp else 64.dp
                    } else 48.dp)
                    .focusRequester(focus.rewind)
                    .focusProperties {
                        left = focus.rewind
                        right = focus.play
                        up = focus.rewind
                        down = focus.rewind
                    }
                    .dpadFocus(),
            ) {
                Icon(
                    if (dlnaActive) Icons.Default.Replay10 else Icons.Default.SkipPrevious,
                    stringResource(R.string.cd_rewind),
                    modifier = Modifier.size(if (fullScreen) {
                        if (compact) 28.dp else 36.dp
                    } else 24.dp),
                    tint = if (fullScreen) Color.White else LocalContentColor.current,
                )
            }
            FilledIconButton(
                onClick = {
                    if (dlnaActive) { if (playing) viewModel.pauseDlna() else viewModel.playDlna() }
                    else { if (playing) sender?.pause() else sender?.play() }
                },
                colors = if (fullScreen) {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.White,
                    )
                } else {
                    IconButtonDefaults.filledIconButtonColors()
                },
                modifier = Modifier
                    .size(if (fullScreen) {
                        if (compact) 60.dp else 80.dp
                    } else 48.dp)
                    .focusRequester(focus.play)
                    .focusProperties {
                        left = focus.rewind
                        right = focus.forward
                        up = focus.play
                        down = focus.play
                    }
                    .dpadFocus(CircleShape),
            ) {
                Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) stringResource(R.string.dlna_pause) else stringResource(R.string.dlna_play),
                    modifier = Modifier.size(if (fullScreen) {
                        if (compact) 38.dp else 52.dp
                    } else 26.dp),
                    tint = if (fullScreen) Color.White else LocalContentColor.current,
                )
            }
            IconButton(
                onClick = { if (dlnaActive) viewModel.seekDlnaBy(10_000L) else sender?.seekToNextMediaItem() },
                modifier = Modifier
                    .size(if (fullScreen) {
                        if (compact) 48.dp else 64.dp
                    } else 48.dp)
                    .focusRequester(focus.forward)
                    .focusProperties {
                        left = focus.play
                        right = focus.stop
                        up = focus.forward
                        down = focus.forward
                    }
                    .dpadFocus(),
            ) {
                Icon(
                    if (dlnaActive) Icons.Default.Forward10 else Icons.Default.SkipNext,
                    stringResource(R.string.cd_fast_forward),
                    modifier = Modifier.size(if (fullScreen) {
                        if (compact) 28.dp else 36.dp
                    } else 24.dp),
                    tint = if (fullScreen) Color.White else LocalContentColor.current,
                )
            }
            IconButton(
                onClick = { if (dlnaActive) viewModel.stopDlna() else sender?.pause() },
                modifier = Modifier
                    .focusRequester(focus.stop)
                    .focusProperties {
                        left = focus.forward
                        right = focus.stop
                        up = focus.stop
                        down = focus.forward
                    }
                    .dpadFocus(),
            ) {
                Icon(Icons.Default.Stop, stringResource(R.string.dlna_stop), tint = if (fullScreen) Color.White else LocalContentColor.current)
            }
        }
        if (showVolumeFeedback) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(
                    text = if (muted) stringResource(R.string.dlna_volume, 0) + " · 静音" else stringResource(R.string.dlna_volume, volume),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun MusicNowPlayingContent(
    viewModel: MainViewModel,
    fullScreen: Boolean,
    isInPip: Boolean = false,
    onExitFullscreen: (() -> Unit)?,
) {
    val track by viewModel.trackInfo.collectAsState()
    val playing by viewModel.videoPlaying.collectAsState()
    val positionMs by viewModel.videoPositionMs.collectAsState()
    val durationMs by viewModel.videoDurationMs.collectAsState()
    val dlnaVolume by viewModel.dlnaVolume.collectAsState()
    val dlnaMuted by viewModel.dlnaMuted.collectAsState()
    val lyrics = remember(track.lyrics) { parseDlnaLyrics(track.lyrics) }
    val lyricListState = key(track.title, track.artist) { rememberLazyListState() }
    val activeLyricIndex = remember(positionMs, lyrics, track.liveLyrics) {
        if (track.liveLyrics) lyrics.lastIndex else activeDlnaLyricIndex(lyrics, positionMs)
    }
    val transportFocus = rememberDlnaTransportFocus()
    var showVolumeFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(dlnaVolume, dlnaMuted) {
        showVolumeFeedback = true
        delay(1_200)
        showVolumeFeedback = false
    }
    if (fullScreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF09090B))
                .safeDrawingPadding(),
        ) {
            track.coverArt?.let { coverArt ->
                Image(
                    bitmap = coverArt.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = 1.18f
                            scaleY = 1.18f
                            alpha = 0.62f
                        }
                        .blur(52.dp),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.48f),
                                Color.Black.copy(alpha = 0.58f),
                                Color.Black.copy(alpha = 0.82f),
                            ),
                        ),
                    ),
            )
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                if (maxHeight > maxWidth) {
                    // Portrait: keep the title readable at the top, give the
                    // record and lyrics their own vertical space, and anchor
                    // the smaller transport row near the bottom edge.
                    Column(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            DlnaTrackMetadata(track, large = true, light = true)
                        }
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                DlnaVinylArt(
                                    coverArt = track.coverArt,
                                    playing = playing,
                                    visible = fullScreen && !isInPip,
                                    modifier = Modifier
                                        .fillMaxWidth(0.56f)
                                        .widthIn(max = 220.dp)
                                        .aspectRatio(1f),
                                )
                                DlnaLyrics(
                                    lines = lyrics,
                                    activeIndex = activeLyricIndex,
                                    listState = lyricListState,
                                    modifier = Modifier.fillMaxWidth().height(112.dp),
                                    large = false,
                                    light = true,
                                live = track.liveLyrics,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        DlnaTransportControls(
                            viewModel = viewModel,
                            playing = playing,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            volume = dlnaVolume,
                            muted = dlnaMuted,
                            fullScreen = true,
                            showVolumeFeedback = false,
                            focus = transportFocus,
                            compact = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        )
                    }
                } else {
                    // Landscape: reserve the right column for metadata and a
                    // stable lyric viewport so progress and controls cannot
                    // squeeze the lyric rows into one another.
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.weight(0.44f).fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            DlnaVinylArt(
                                coverArt = track.coverArt,
                                playing = playing,
                                visible = fullScreen && !isInPip,
                                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                            )
                        }
                        Column(
                            modifier = Modifier.weight(0.56f).fillMaxHeight().padding(vertical = 14.dp),
                        ) {
                            DlnaTrackMetadata(track, large = true, light = true)
                            Spacer(Modifier.height(12.dp))
                            DlnaLyrics(
                                lines = lyrics,
                                activeIndex = activeLyricIndex,
                                listState = lyricListState,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp),
                                large = true,
                                light = true,
                                live = track.liveLyrics,
                            )
                            Spacer(Modifier.height(8.dp))
                            DlnaTransportControls(
                                viewModel = viewModel,
                                playing = playing,
                                positionMs = positionMs,
                                durationMs = durationMs,
                                volume = dlnaVolume,
                                muted = dlnaMuted,
                                fullScreen = true,
                                showVolumeFeedback = false,
                                focus = transportFocus,
                                compact = true,
                            )
                        }
                    }
                }
            }
            if (onExitFullscreen != null) {
                IconButton(
                    onClick = onExitFullscreen,
                    // Exiting fullscreen is also available through the system
                    // Back action. Keep this visual affordance out of the TV
                    // focus order so a newly opened player does not land on
                    // the top-left arrow before the transport controls.
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 18.dp, top = 12.dp)
                        .focusProperties { canFocus = false },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_exit_fullscreen), tint = Color.White.copy(alpha = 0.7f))
                }
            }
            if (showVolumeFeedback) {
                DlnaVolumeOverlay(
                    volume = dlnaVolume,
                    muted = dlnaMuted,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
                )
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            DlnaCoverArt(
                track = track,
                modifier = Modifier.weight(1f, fill = false).aspectRatio(1f).fillMaxWidth(0.7f),
            )
            Spacer(Modifier.height(20.dp))
            DlnaTrackMetadata(track, large = false)
            Spacer(Modifier.height(12.dp))
            DlnaLyrics(
                lines = lyrics,
                activeIndex = activeLyricIndex,
                listState = lyricListState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                large = false,
                live = track.liveLyrics,
            )
            Spacer(Modifier.height(16.dp))
            DlnaTransportControls(
                viewModel = viewModel,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                volume = dlnaVolume,
                muted = dlnaMuted,
                fullScreen = false,
                showVolumeFeedback = showVolumeFeedback,
                focus = transportFocus,
            )
        }
    }
}

@Composable
private fun DlnaVolumeOverlay(volume: Int, muted: Boolean, modifier: Modifier = Modifier) {
    val displayedVolume = if (muted) 0 else volume.coerceIn(0, 100)
    Column(
        modifier = modifier
            .width(54.dp)
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(27.dp))
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = stringResource(R.string.dlna_volume, displayedVolume),
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Box(
            modifier = Modifier.width(8.dp).height(140.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(displayedVolume / 100f)
                    .align(Alignment.BottomCenter)
                    .background(Color.White, RoundedCornerShape(4.dp)),
            )
        }
        Text("$displayedVolume", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun NowPlayingContent(viewModel: MainViewModel) {
    val track by viewModel.trackInfo.collectAsState()
    val dlnaActive by viewModel.dlnaActive.collectAsState()
    val playing by viewModel.videoPlaying.collectAsState()
    val positionMs by viewModel.videoPositionMs.collectAsState()
    val durationMs by viewModel.videoDurationMs.collectAsState()
    val dlnaVolume by viewModel.dlnaVolume.collectAsState()
    val dlnaMuted by viewModel.dlnaMuted.collectAsState()

    if (dlnaActive) {
        MusicNowPlayingContent(viewModel = viewModel, fullScreen = false, onExitFullscreen = null)
        return
    }

    var showVolumeFeedback by remember { mutableStateOf(false) }

    LaunchedEffect(dlnaVolume, dlnaMuted) {
        if (dlnaActive) {
            showVolumeFeedback = true
            delay(1_200)
            showVolumeFeedback = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // cover art
        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .aspectRatio(1f)
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (track.coverArt != null) {
                Image(
                    bitmap = track.coverArt!!.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_cover_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // track info
        Text(
            text = track.title.ifEmpty { stringResource(R.string.unknown_track) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val artistAlbum = listOf(track.artist, track.album)
            .filter(String::isNotBlank)
            .joinToString(" · ")
        if (artistAlbum.isNotEmpty()) {
            Text(
                text = artistAlbum,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (track.lyrics.isNotBlank()) {
            Text(
                text = track.lyrics,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            )
        }

        if (dlnaActive && showVolumeFeedback) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    text = if (dlnaMuted) stringResource(R.string.dlna_volume, 0) + " · 静音" else stringResource(R.string.dlna_volume, dlnaVolume),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        val player = viewModel.dacpPlayer
        if (dlnaActive) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatVideoTime(positionMs), modifier = Modifier.padding(end = 8.dp))
                LinearProgressIndicator(
                    progress = { if (durationMs > 0) positionMs.toFloat() / durationMs else 0f },
                    modifier = Modifier.weight(1f)
                )
                Text(formatVideoTime(durationMs), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { viewModel.seekDlna((positionMs - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.Default.Replay10, stringResource(R.string.cd_rewind))
                }
                IconButton(onClick = { if (playing) viewModel.pauseDlna() else viewModel.playDlna() }, modifier = Modifier.dpadFocus()) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) stringResource(R.string.dlna_pause) else stringResource(R.string.dlna_play))
                }
                IconButton(onClick = { viewModel.seekDlna(positionMs + 10_000L) }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.Default.Forward10, stringResource(R.string.cd_fast_forward))
                }
                IconButton(onClick = { viewModel.stopDlna() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.Default.Stop, stringResource(R.string.dlna_stop))
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { viewModel.dlnaVolumeDown() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.cd_volume_down))
                }
                IconButton(onClick = { viewModel.dlnaMuteToggle() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.cd_mute))
                }
                IconButton(onClick = { viewModel.dlnaVolumeUp() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cd_volume_up))
                }
            }
        } else if (player != null) {
            // BottomControls' default row, minus its video-overlay gradient
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PositionText(player, Modifier.padding(end = 8.dp))
                Box(modifier = Modifier.weight(1f)) { ProgressSlider(player) }
                DurationText(player, Modifier.padding(start = 8.dp))
            }

            Spacer(Modifier.height(8.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                HoldScanButton(
                    icon = Icons.Default.FastRewind,
                    contentDescription = stringResource(R.string.cd_rewind),
                    onBegin = { viewModel.audioScanBegin(false) },
                    onEnd = { viewModel.audioScanEnd() }
                )
                PreviousButton(player, modifier = Modifier.dpadFocus())
                PlayPauseButton(
                    player,
                    modifier = Modifier.size(63.dp).dpadFocus(CircleShape),
                    iconSize = 40.dp
                )
                NextButton(player, modifier = Modifier.dpadFocus())
                HoldScanButton(
                    icon = Icons.Default.FastForward,
                    contentDescription = stringResource(R.string.cd_fast_forward),
                    onBegin = { viewModel.audioScanBegin(true) },
                    onEnd = { viewModel.audioScanEnd() }
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = { viewModel.audioVolumeDown() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.cd_volume_down))
                }
                IconButton(onClick = { viewModel.audioMuteToggle() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, stringResource(R.string.cd_mute))
                }
                IconButton(onClick = { viewModel.audioVolumeUp() }, modifier = Modifier.dpadFocus()) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.cd_volume_up))
                }
            }
        }
    }
}

private const val VIDEO_OVERLAY_HIDE_MS = 4000L

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val headingStyle = style.copy(fontWeight = FontWeight.Bold)
        val color = Color.White.copy(alpha = 0.9f)
        val headingColor = Color(0xFF80D8FF) // cyan
        val context = LocalContext.current
        debugOverlaySections(context, info).forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(section.title.uppercase(), style = headingStyle, color = headingColor)
            section.lines.forEach { Text(it, style = style, color = color) }
        }
    }
}

private data class DebugSection(val title: String, val lines: List<String>)

private fun debugOverlaySections(context: Context, info: DebugInfo): List<DebugSection> = buildList {
    buildList {
        if (info.videoCodec.isNotEmpty()) {
            add(context.getString(R.string.debug_video, info.videoCodec, info.videoRes))
            add(context.getString(R.string.debug_fps_bitrate, info.videoFps, info.bitrateStr))
            add(context.getString(R.string.debug_frames_drops, info.videoFrames, info.droppedFrames))
            add(context.getString(R.string.debug_jitter, info.jitterStr))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_video), it)) }

    buildList {
        if (info.audioCodec.isNotEmpty()) add(context.getString(R.string.debug_audio, info.audioCodec, info.audioVolume))
        info.audio?.let { a ->
            add(context.getString(R.string.debug_audio_buffer, a.backlogMs, a.tunedCushionMs))
            add(context.getString(R.string.debug_audio_glitch, a.trims, a.drops, a.silences, a.underruns, a.xrun))
            add(context.getString(R.string.debug_audio_decode, formatDecode(a.decodeMeanUs, a.decodeMaxUs, a.decodeHeld, a.decodeErrors)))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_audio), it)) }

    add(DebugSection(context.getString(R.string.debug_section_network), listOf(context.getString(R.string.debug_clients, info.connections))))
}

// "mean/max ms held=N", or "held=N" before first latency window lands
private fun formatDecode(meanUs: Int, maxUs: Int, held: Int, errors: Int): String =
    (if (meanUs == 0) "held=$held"
    else "%.1f/%.1f ms held=%d".format(meanUs / 1000.0, maxUs / 1000.0, held)) + " errs=$errors"
