package io.github.jqssun.airplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

// Resolution exposed to the Android app and its rendering surface.
//
// Android TV can expose a 4K physical panel while running the app in a
// 1920x1080 logical display on some 32-bit Android TV builds. Advertising
// the physical mode makes macOS send a 4K mirror that the app then has to copy
// through JNI and decode on a 32-bit device. Use the current logical metrics so
// the sender and the actual Surface agree. A user-selected resolution still
// overrides this value in AirPlayService.
fun Context.realDisplaySize(): Pair<Int, Int> {
    val display = getSystemService(DisplayManager::class.java)
        ?.getDisplay(Display.DEFAULT_DISPLAY)
    if (display != null) {
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels to metrics.heightPixels
        }
    }
    return resources.displayMetrics.let { it.widthPixels to it.heightPixels }
}
