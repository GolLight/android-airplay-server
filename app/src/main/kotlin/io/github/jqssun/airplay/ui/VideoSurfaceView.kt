package io.github.jqssun.airplay.ui

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView

// shared surface for mirroring (VideoRenderer) and airplay video (AirPlayVideoPlayer)
@Composable
fun VideoSurfaceView(
    onSurfaceAvailable: (Surface) -> Unit,
    onSurfaceDestroyed: (Surface) -> Unit,
    aspectRatio: Float = 16f / 9f,
    // false = caller sizes the surface (content-scale modes)
    applyAspectRatio: Boolean = true,
    modifier: Modifier = Modifier,
    bufferSizePx: IntSize? = null
) {
    val callbacks = remember {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, fmt: Int, w: Int, h: Int) {
                onSurfaceAvailable(holder.surface)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSurfaceDestroyed(holder.surface)
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                it.holder.addCallback(callbacks)
            }
        },
        update = { view ->
            // A TV can expose a 1080p app window on a 4K panel. The video
            // buffer may still use native pixels independently of UI layout.
            if (bufferSizePx != null && bufferSizePx.width > 0 && bufferSizePx.height > 0) {
                view.holder.setFixedSize(bufferSizePx.width, bufferSizePx.height)
            } else {
                view.holder.setSizeFromLayout()
            }
        },
        modifier = if (applyAspectRatio) {
            modifier
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = aspectRatio < 1f)
                .fillMaxSize()
        } else {
            modifier
        }
    )
}

internal fun mirrorSurfaceBufferSize(resolution: String, panelW: Int, panelH: Int): IntSize? {
    val w = resolution.substringBefore('x').toIntOrNull() ?: return null
    val h = resolution.substringAfter('x', "").toIntOrNull() ?: return null
    if (w <= 0 || h <= 0 || panelW <= 0 || panelH <= 0) return null
    // Never upscale the buffer beyond the source or allocate beyond the panel.
    val scale = minOf(1.0, panelW.toDouble() / w, panelH.toDouble() / h)
    return IntSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
}
