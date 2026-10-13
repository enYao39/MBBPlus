package com.floatwindow.morebubblebutton

import android.app.Application
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/** Publishes the debug switch to injected app processes through libxposed Remote Preferences. */
class MoreBubbleApplication : Application(), XposedServiceHelper.OnServiceListener {
    @Volatile
    private var service: XposedService? = null

    private val settings by lazy {
        getSharedPreferences(ModuleSettings.PREFS_NAME, MODE_PRIVATE)
    }

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == ModuleSettings.KEY_DEBUG_LOGGING_ENABLED) {
            syncDebugSwitch(prefs.getBoolean(ModuleSettings.KEY_DEBUG_LOGGING_ENABLED, false))
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings.registerOnSharedPreferenceChangeListener(listener)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        syncDebugSwitch(settings.getBoolean(ModuleSettings.KEY_DEBUG_LOGGING_ENABLED, false))
    }

    override fun onServiceDied(service: XposedService) {
        if (this.service === service) this.service = null
    }

    private fun syncDebugSwitch(enabled: Boolean) {
        val bound = service ?: return
        try {
            val prefs = bound.getRemotePreferences(ModuleSettings.DEBUG_REMOTE_PREFS)
            val editor = prefs.edit()
            editor.putBoolean(ModuleSettings.KEY_DEBUG_LOGGING_ENABLED, enabled)
            if (!editor.commit()) {
                android.util.Log.w(TAG, "Remote debug preference commit failed")
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "Remote debug preference sync failed", t)
        }
    }

    companion object {
        private const val TAG = "MoreBubbleModule"
    }
}
