package io.github.jqssun.airplay

import android.content.Context
import java.util.UUID

/** Preferences that belong to the DLNA receiver and do not alter AirPlay settings. */
object DlnaSettings {
    private const val PREFS = "dlna_settings"
    private const val ENABLED = "enabled"
    private const val UUID_KEY = "uuid"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ENABLED, enabled).apply()
    }

    fun stableUuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(UUID_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(UUID_KEY, it).apply()
        }
    }
}
