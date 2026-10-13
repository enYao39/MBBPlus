package com.floatwindow.morebubblebutton;

import android.content.Context;
import android.os.SystemClock;

/**
 * Logging facade for the Xposed entry point. Informational diagnostics are
 * opt-in; warnings and errors remain visible so genuine failures are not hidden.
 */
final class Log {
    private static final long SETTINGS_CACHE_MS = 1000L;
    private static volatile long sLastReadAt;
    private static volatile boolean sDebugEnabled;

    private Log() {}

    static int i(String tag, String message) {
        return isDebugEnabled() ? android.util.Log.i(tag, message) : 0;
    }

    static int w(String tag, String message) {
        return android.util.Log.w(tag, message);
    }

    static int w(String tag, String message, Throwable error) {
        return android.util.Log.w(tag, message, error);
    }

    static int e(String tag, String message) {
        return android.util.Log.e(tag, message);
    }

    static int e(String tag, String message, Throwable error) {
        return android.util.Log.e(tag, message, error);
    }

    private static boolean isDebugEnabled() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastReadAt < SETTINGS_CACHE_MS) return sDebugEnabled;
        synchronized (Log.class) {
            if (now - sLastReadAt < SETTINGS_CACHE_MS) return sDebugEnabled;
            boolean enabled = false;
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread");
                Object application = activityThread.getMethod("currentApplication").invoke(null);
                if (application instanceof Context) {
                    enabled = ModuleSettings.isDebugLoggingEnabled((Context) application);
                }
            } catch (Throwable ignored) {
                // Application startup can precede currentApplication(); retry shortly.
            }
            sDebugEnabled = enabled;
            sLastReadAt = now;
            return enabled;
        }
    }
}
