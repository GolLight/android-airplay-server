package io.github.jqssun.airplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

fun Context.hasLocalNetwork() = Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED
