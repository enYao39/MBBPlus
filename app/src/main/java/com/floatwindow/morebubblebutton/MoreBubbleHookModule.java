package com.floatwindow.morebubblebutton;

import android.app.Notification;
import android.app.ActivityOptions;
import android.app.ActivityManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;

public class MoreBubbleHookModule extends XposedModule {
    private static final String TAG = "MoreBubbleModule";
    private static View sSecondRow;
    private static final Set<String> sActivityIntentDiagnosticPackages = ConcurrentHashMap.newKeySet();
    private ClassLoader mLauncherClassLoader;
    private static Object sBubblesManager;
    private static final String HEADS_UP_HANDLE_TAG = "more_bubble_heads_up_handle";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long SWIPE_HANDLE_MIN_DISTANCE_DP = 24L;
    private static final String LMO_FREEFORM_SERVICE = "lmo_freeform";
    private static final String LMO_FREEFORM_DESCRIPTOR =
            "com.libremobileos.freeform.ILMOFreeformUIService";
    private static final int LMO_START_APP_TRANSACTION = 1;
    // Evolution and Lunaris use different framework ids for the ImageView that
    // NotificationContentView passes to applyBubbleAction(). Keep both ids here
    // instead of scanning arbitrary notification ImageViews.
    private static final int EVOLUTION_BUBBLE_ICON_ID = 0x01020281;
    private static final int LUNARIS_BUBBLE_ICON_ID = 0x0102024f;
    private static final String LUNARIS_SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String LUNARIS_FREEFORM_ICON =
            "desktop_mode_ic_handle_menu_floating";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            ModuleSettings.setDebugRemotePreferences(
                    getRemotePreferences(ModuleSettings.DEBUG_REMOTE_PREFS));
        } catch (Throwable t) {
            Log.w(TAG, "Xposed Remote Preferences unavailable for debug switch: "
                    + t.getClass().getSimpleName());
        }
        Log.i(TAG, "MoreBubbleModule: " + param.getProcessName() + " | API " + getApiVersion());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        String pkg = param.getPackageName();
        if ("com.google.android.apps.nexuslauncher".equals(pkg)
                || "com.android.launcher3".equals(pkg)) {
            mLauncherClassLoader = param.getDefaultClassLoader();
            hookLauncher(param);
        } else if ("com.android.systemui".equals(pkg) || "android".equals(pkg)) {
            // Depending on the LSPosed/Android build, SystemUI may be reported as its
            // package name or through the shared "android" process alias. Hook whichever
            // callback arrives first, and never install the same hooks twice.
            hookSystemUiOnce(param.getDefaultClassLoader(), pkg);
        } else if (!"com.floatwindow.morebubblebutton".equals(pkg)) {
            // LSPosed only loads this module in packages enabled in its scope. Use the
            // same generic Activity observer for any user-selected target application.
            hookActivityIntentDiagnostics(param);
        }
    }

    /** Observe routed Activity intents in any application selected in the LSPosed scope. */
    private void hookActivityIntentDiagnostics(PackageLoadedParam param) {
        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getDefaultClassLoader();
        if (packageName == null || classLoader == null
                || !sActivityIntentDiagnosticPackages.add(packageName)) return;
        try {
            Class<?> activityThreadClass = classLoader.loadClass("android.app.ActivityThread");
            int installed = 0;
            boolean launchHookInstalled = false;
            boolean newIntentHookInstalled = false;
            for (Method method : activityThreadClass.getDeclaredMethods()) {
                String name = method.getName();
                Class<?>[] types = method.getParameterTypes();
                boolean launchActivity = "performLaunchActivity".equals(name)
                        && types.length >= 1
                        && "ActivityClientRecord".equals(types[0].getSimpleName());
                boolean deliverNewIntent = "handleNewIntent".equals(name)
                        && types.length >= 2
                        && "ActivityClientRecord".equals(types[0].getSimpleName())
                        && List.class.isAssignableFrom(types[1]);
                if (!launchActivity && !deliverNewIntent) continue;
                method.setAccessible(true);
                hook(method).intercept(chain -> {
                    if (deliverNewIntent) {
                        Object activityObject = getFieldSystemUi(chain.getArg(0), "activity");
                        Object delivered = chain.getArg(1);
                        Activity activity = activityObject instanceof Activity
                                ? (Activity) activityObject : null;
                        Intent currentBefore = null;
                        List<?> deliveredIntents = delivered instanceof List<?>
                                ? new java.util.ArrayList<>((List<?>) delivered) : java.util.Collections.emptyList();
                        boolean observe = activity != null && isDebugLoggingEnabled(activity);
                        if (observe) {
                            try { currentBefore = activity.getIntent(); } catch (Throwable ignored) {}
                            for (Object item : deliveredIntents) {
                                Intent receivedIntent = item instanceof Intent ? (Intent) item : null;
                                if (!shouldObserveActivityIntent(activity, receivedIntent, currentBefore)) continue;
                                logActivityIntent(activity, packageName,
                                        "ActivityThread.handleNewIntent.received", receivedIntent);
                                logActivityIntent(activity, packageName,
                                        "ActivityThread.handleNewIntent.currentBefore", currentBefore);
                            }
                        }
                        Object result = chain.proceed();
                        if (observe) {
                            Intent currentAfter = null;
                            try { currentAfter = activity.getIntent(); } catch (Throwable ignored) {}
                            if (shouldObserveActivityIntent(activity, currentAfter)) {
                                logActivityIntent(activity, packageName,
                                        "ActivityThread.handleNewIntent.currentAfter", currentAfter);
                            }
                        }
                        return result;
                    }
                    Object result = chain.proceed();
                    if (launchActivity && result instanceof Activity) {
                        Activity activity = (Activity) result;
                        Intent launchIntent = null;
                        try { launchIntent = activity.getIntent(); } catch (Throwable ignored) {}
                        if (isDebugLoggingEnabled(activity)
                                && shouldObserveActivityIntent(activity, launchIntent)) {
                            logActivityIntent(activity, packageName,
                                    "ActivityThread.performLaunchActivity", launchIntent);
                        }
                    }
                    return result;
                });
                installed++;
                launchHookInstalled |= launchActivity;
                newIntentHookInstalled |= deliverNewIntent;
            }
            if (installed == 0 || !launchHookInstalled || !newIntentHookInstalled) {
                Log.w(TAG, "ActivityThread Intent diagnostics incomplete for " + packageName
                        + " hookedMethods=" + installed + " launch=" + launchHookInstalled
                        + " newIntent=" + newIntentHookInstalled);
            } else {
                Log.i(TAG, "ActivityThread Intent diagnostics armed for scoped package " + packageName
                        + " methods=" + installed);
            }
        } catch (Throwable t) {
            Log.w(TAG, "ActivityThread Intent diagnostics unavailable for " + packageName
                    + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private static boolean shouldObserveActivityIntent(Activity activity, Intent... intents) {
        String activityName = activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (activityName.contains("deeplink") || activityName.contains("deep_link")
                || activityName.contains("chat") || activityName.contains("conversation")
                || activityName.contains("message") || activityName.contains("dialog")) {
            return true;
        }
        if (intents != null) {
            for (Intent intent : intents) {
                if (intent != null && (intent.getData() != null
                        || Intent.ACTION_VIEW.equals(intent.getAction()))) return true;
            }
        }
        return false;
    }

    private static void logActivityIntent(Activity activity, String packageName,
            String stage, Intent intent) {
        try {
            String component = activity.getClass().getName();
            int taskId = activity.getTaskId();
            boolean taskRoot = activity.isTaskRoot();
            String intentSummary = intent == null ? "null"
                    : "action=" + intent.getAction()
                    + " data=" + limitDebugValue(intent.getDataString())
                    + " identifier=" + intent.getIdentifier()
                    + " flags=0x" + Integer.toHexString(intent.getFlags())
                    + " categories=" + intent.getCategories()
                    + " extras=" + summarizeRoutingExtras(intent);
            logDetailedInfo(activity, "Activity Intent observed package=" + packageName
                    + " stage=" + stage + " activity=" + component
                    + " taskId=" + taskId + " taskRoot=" + taskRoot
                    + " intent=" + intentSummary);
        } catch (Throwable t) {
            logDetailedInfo(activity, "Activity Intent observation failed package=" + packageName
                    + " stage=" + stage + " activity=" + activity.getClass().getName()
                    + " error=" + t.getClass().getSimpleName());
        }
    }

    /** Include extra names and route-like scalar values while omitting message payloads. */
    private static String summarizeRoutingExtras(Intent intent) {
        try {
            android.os.Bundle extras = intent.getExtras();
            if (extras == null || extras.isEmpty()) return "[]";
            StringBuilder result = new StringBuilder("keys=")
                    .append(new java.util.TreeSet<>(extras.keySet())).append(" routeValues={");
            boolean first = true;
            for (String name : new java.util.TreeSet<>(extras.keySet())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.contains("conversation") || lower.contains("chat")
                        || lower.contains("dialog") || lower.contains("peer")
                        || lower.contains("user") || lower.contains("target")
                        || lower.contains("route") || lower.contains("deeplink")
                        || lower.contains("deep_link") || lower.contains("uri")
                        || lower.contains("url") || lower.endsWith("_id")
                        || "id".equals(lower))) continue;
                if (lower.contains("body") || lower.contains("text") || lower.contains("message")
                        || lower.contains("title") || lower.contains("payload")
                        || lower.contains("token") || lower.contains("signature")) continue;
                Object value;
                try { value = extras.get(name); } catch (Throwable ignored) { continue; }
                if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) continue;
                if (!first) result.append(", ");
                result.append(name).append('=').append(limitDebugValue(String.valueOf(value)));
                first = false;
            }
            return result.append('}').toString();
        } catch (Throwable t) {
            return "unavailable:" + t.getClass().getSimpleName();
        }
    }

    private ClassLoader mSystemUiClassLoader;
    private static volatile boolean sSystemUiHookInstalled;
    private static final Map<String, Long> sForcedBubbleKeys = new ConcurrentHashMap<>();
    private static final long FORCED_BUBBLE_GRACE_MS = 8000L;
    private static final Map<String, PendingHeadsUpLaunch> sPendingHeadsUpLaunches =
            new ConcurrentHashMap<>();
    // A row can invoke several SystemUI lifecycle callbacks during one frame. Keep at most one
    // pending synchronization runnable per row so the handle never creates a second layout pass
    // for every callback in the same frame.
    private static final Map<View, Runnable> sPendingHeadsUpSyncs = new WeakHashMap<>();
    private static final Map<View, Boolean> sTrackedHeadsUpRows = new WeakHashMap<>();
    private static final Map<View, Object> sSwipeHandleRows = new WeakHashMap<>();
    private static volatile boolean sShadeOrQsExpanded;
    private static volatile boolean sQsFullscreen;
    // Tracks whether any row has reported being on the keyguard. This is a
    // coarse flag — it's set when setOnKeyguard(true) is called on ANY row,
    // and cleared when setOnKeyguard(false) is called. Used to prevent
    // scheduling sync runnables for newly-inflated rows on the lock screen.
    private static volatile boolean sOnKeyguard;

    private void hookSystemUiOnce(ClassLoader cl, String packageName) {
        if (cl == null || sSystemUiHookInstalled) return;
        synchronized (MoreBubbleHookModule.class) {
            if (sSystemUiHookInstalled) return;
            try {
                cl.loadClass("com.android.systemui.statusbar.notification.row.NotificationContentView");
            } catch (Throwable t) {
                Log.w(TAG, "SystemUI hook deferred for " + packageName + ": " + t.getMessage());
                return;
            }
            mSystemUiClassLoader = cl;
            hookSystemUi(cl);
            sSystemUiHookInstalled = true;
            Log.i(TAG, "SystemUI hooks installed from " + packageName);
        }
    }

    private void hookSystemUi(ClassLoader cl) {
        Log.i(TAG, "Hooking SystemUI...");
        // 1. shouldShowBubbleButton: 让所有具备可打开入口的非锁屏通知显示按钮。
        //    Bubble 按钮与 Heads-up 下滑横条由独立设置控制。
        Class<?> contentViewClass = null;
        try {
            contentViewClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.NotificationContentView");
        } catch (Throwable t) {
            Log.e(TAG, "Load NotificationContentView: " + t.getMessage());
        }
        if (contentViewClass != null) {
            try {
                Method shouldShowBubbleButton = findBubbleVisibilityMethod(contentViewClass);
                if (shouldShowBubbleButton == null) {
                    throw new NoSuchMethodException("shouldShowBubbleButton");
                }
                hook(shouldShowBubbleButton).intercept(chain -> {
                    Object contentView = chain.getThisObject();
                    Object row = getFieldSystemUi(contentView, "mContainingNotification");
                    Object entry = getNotificationEntryFromRow(row);
                    if (shouldShowBubbleButton.getParameterCount() > 0
                            && chain.getArg(0) != null) {
                        entry = chain.getArg(0);
                    }
                    try {
                        Context ctx = null;
                        try { ctx = ((View) contentView).getContext(); } catch (Throwable ignored) {}
                        if (ctx != null && !ModuleSettings.isSystemUiBubbleEnabled(ctx)) return chain.proceed();

                        // A notification row can report the native button as available even on
                        // the public/keyguard layout. The module must never expose the extra
                        // launcher there; use the row's own state instead of the coarse global
                        // sOnKeyguard flag because shade and keyguard rows may coexist briefly.
                        // The row is the authoritative owner of the current lock-screen state.
                        // A ContentView can keep its old mOnKeyguard value for one bind after
                        // the device is unlocked, so do not let that stale value suppress the
                        // button when a row is already known to be off keyguard.
                        if (isOnKeyguardRow(row)
                                || (row == null && isOnKeyguardContentView(contentView))) {
                            return false;
                        }

                        // shouldShowBubbleButton() can run before onNotificationUpdated() or
                        // setBubbleClickListener(). Prepare the metadata first so the first
                        // inflation gets the same action as later refreshes.
                        prepareBubbleMetadata(entry);
                    } catch (Throwable ignored) {}

                    boolean original = (boolean) chain.proceed();
                    if (original) return true;
                    return isBubbleButtonEligible(row, entry);
                });
                Log.i(TAG, "Hooked shouldShowBubbleButton OK: "
                        + shouldShowBubbleButton.getParameterCount() + " args");
            } catch (Throwable t) {
                Log.e(TAG, "Hook shouldShowBubbleButton: " + t.getMessage());
            }

            try {
                // applyBubbleAction() is the common SystemUI path that paints the
                // native Bubble icon in both the shade row and the Heads-up card.
                // Keep the original listener (NotificationEntryAdapter) intact and
                // only replace the visual affordance when Freeform is selected.
                Method applyBubbleAction = findMethodSystemUi(
                        contentViewClass, "applyBubbleAction", View.class);
                if (applyBubbleAction == null) throw new NoSuchMethodException("applyBubbleAction");
                hook(applyBubbleAction).intercept(chain -> {
                    Object result = chain.proceed();
                    try {
                        Context ctx = ((View) chain.getThisObject()).getContext();
                        if (isFreeformOpenMode(ctx)) {
                            Object root = chain.getArg(0);
                            if (root instanceof View) {
                                replaceFreeformPopupIcon((View) root, ctx);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Freeform popup icon update: " + t.getMessage());
                    }
                    return result;
                });
                Log.i(TAG, "Hooked NotificationContentView.applyBubbleAction OK");
            } catch (Throwable t) {
                Log.w(TAG, "Hook applyBubbleAction: " + t.getMessage());
            }

            hookNotificationContentLifecycle(contentViewClass);
        }

        // The row owns the actual Heads-up state. This is the reliable first-frame
        // entry point on Evolution17; NotificationContentView is often rebound before
        // the row enters Heads-up mode.
        hookHeadsUpRowLifecycle(cl);

        // 2. Make system think it can bubble by hooking NotificationEntry.canBubble
        try {
            Class<?> entryClass = cl.loadClass("com.android.systemui.statusbar.notification.collection.NotificationEntry");
            java.lang.reflect.Method canBubbleMethod = null;
            try {
                canBubbleMethod = entryClass.getDeclaredMethod("canBubble");
            } catch (NoSuchMethodException e) {
                // It might not exist in all versions, ignore if not found
            }
            if (canBubbleMethod != null) {
                hook(canBubbleMethod).intercept(chain -> {
                    boolean result = (boolean) chain.proceed();
                    if (result) return true;
                    try {
                        Object entry = chain.getThisObject();
                        Object sbn = getFieldSystemUi(entry, "mSbn");
                        Notification notif = sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
                        if (notif != null 
                                && (notif.flags & Notification.FLAG_ONGOING_EVENT) == 0 
                                && notif.contentIntent != null) {
                            return true;
                        }
                    } catch (Throwable t) {
                        // ignore
                    }
                    return result;
                });
                Log.i(TAG, "Hooked NotificationEntry.canBubble OK");
            } else {
                Log.w(TAG, "NotificationEntry.canBubble method not found");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Hook NotificationEntry canBubble: " + t.getMessage());
        }

        // 3. Intercept the native NotificationEntry route so the notification
        // button always uses the module's Bubble/Freeform action. Lunaris uses
        // this callback for the native notification Bubble button.
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            hookBubblesManagerConstructors(bubblesCls);
            java.lang.reflect.Method expand = null;
            for (java.lang.reflect.Method m : bubblesCls.getDeclaredMethods()) {
                if (m.getName().equals("expandStackAndSelectBubble")
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].getName().contains("NotificationEntry")) {
                    expand = m;
                    break;
                }
            }
            if (expand != null) {
                hook(expand).intercept(chain -> {
                    sBubblesManager = chain.getThisObject();
                    try {
                        Object entry = chain.getArg(0);
                        if (entry != null) {
                            Object sbn = getFieldSystemUi(entry, "mSbn");
                            Notification notif = sbn != null
                                    ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
                            if (notif != null
                                    && (notif.flags & Notification.FLAG_ONGOING_EVENT) == 0
                                    && notif.contentIntent != null) {
                                if (!expandAppBubbleFromNotification(
                                        chain.getThisObject(), entry, "expand guard")) {
                                    launchNotificationFullscreen(entry, "expand guard");
                                }
                                return null;
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "expand guard: " + t.getMessage());
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked BubblesManager.expandStackAndSelectBubble OK");
            } else {
                Log.w(TAG, "BubblesManager.expandStackAndSelectBubble(NotificationEntry) not found");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook BubblesManager: " + t.getMessage()); }

        // 4. Keep the user-change callback on the same module-controlled route.
        // On Lunaris this is the callback used after the native button changes
        // the notification Bubble state.
        try {
            Class<?> bubblesCls = cl.loadClass("com.android.systemui.wmshell.BubblesManager");
            java.lang.reflect.Method onUserChanged = null;
            for (java.lang.reflect.Method m : bubblesCls.getDeclaredMethods()) {
                if (m.getName().equals("onUserChangedBubble")
                        && m.getParameterCount() == 2
                        && m.getParameterTypes()[0].getName().contains("NotificationEntry")
                        && m.getParameterTypes()[1] == boolean.class) {
                    onUserChanged = m;
                    break;
                }
            }
            if (onUserChanged != null) {
                hook(onUserChanged).intercept(chain -> {
                    sBubblesManager = chain.getThisObject();
                    try {
                        boolean enabled = (boolean) chain.getArg(1);
                        Object entry = chain.getArg(0);
                        if (enabled && entry != null) {
                            if (!expandAppBubbleFromNotification(
                                    chain.getThisObject(), entry, "user change")) {
                                launchNotificationFullscreen(entry, "user change");
                            }
                            return null;
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "user change bubble: " + t.getMessage());
                    }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked BubblesManager.onUserChangedBubble OK");
            } else {
                Log.w(TAG, "BubblesManager.onUserChangedBubble(NotificationEntry, boolean) not found");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook onUserChangedBubble: " + t.getMessage()); }

        // Evolution17's native popup/shade Bubble button enters through the
        // NotificationEntryAdapter. Its Entry is a field, not a method argument.
        // Intercept only in Freeform mode; on every failure the original method
        // continues, which preserves the native Bubble fallback.
        try {
            Class<?> adapterClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.collection.NotificationEntryAdapter");
            Method bubbleClick = adapterClass.getMethod("onNotificationBubbleIconClicked");
            hook(bubbleClick).intercept(chain -> {
                Object adapter = chain.getThisObject();
                Context context = getNotificationAdapterContext(adapter);
                Object entry = getFieldSystemUi(adapter, "entry");
                if (entry != null) {
                    if (context != null
                            && ModuleSettings.isDebugLoggingEnabled(context)
                            && ModuleSettings.isDebugFullscreenNotificationButtonEnabled(context)) {
                        if (launchExtractedNotificationIntentFullscreen(context, entry,
                                "Bubble button diagnostic")) {
                            if (sBubblesManager != null) {
                                dismissClickedNotificationIfAutoCancel(sBubblesManager, entry);
                                collapseShadeFromManager(sBubblesManager);
                            }
                            Log.i(TAG, "Bubble button handled by extracted-Intent fullscreen diagnostic");
                            return null;
                        }
                        Log.w(TAG, "Bubble button diagnostic could not launch extracted Intent; continuing normal handling");
                    }
                    if (isFreeformOpenMode(context)) {
                        if (launchNotificationInFreeform(context, entry, "Bubble button")) {
                            if (sBubblesManager != null) {
                                dismissClickedNotificationIfAutoCancel(sBubblesManager, entry);
                                collapseShadeFromManager(sBubblesManager);
                            }
                            Log.i(TAG, "Bubble button handled by Freeform");
                            return null;
                        }
                        Log.i(TAG, "Bubble button: Freeform unavailable, using Bubble");
                    } else if (sBubblesManager != null
                            && expandAppBubbleFromNotification(sBubblesManager, entry,
                            "Bubble button")) {
                        Log.i(TAG, "Bubble button handled by Bubble");
                        return null;
                    }
                }
                // Preserve the native path if the module cannot construct an app Bubble.
                return chain.proceed();
            });
            Log.i(TAG, "Hooked NotificationEntryAdapter.onNotificationBubbleIconClicked OK");
        } catch (Throwable t) {
            Log.w(TAG, "Hook Bubble button: " + t.getMessage());
        }

        // 5. dismissBubbleWithKey guard - 防止刚强制创建的气泡被 Ranking/Channel 立刻移除。
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            for (java.lang.reflect.Method dm : dataCls.getDeclaredMethods()) {
                if (dm.getName().equals("dismissBubbleWithKey")
                        && dm.getParameterCount() >= 2
                        && dm.getParameterTypes()[0] == int.class
                        && dm.getParameterTypes()[dm.getParameterCount() - 1] == String.class) {
                    final int keyArgIndex = dm.getParameterCount() - 1;
                    hook(dm).intercept(chain -> {
                        try {
                            int reason = (int) chain.getArg(0);
                            String key = (String) chain.getArg(keyArgIndex);
                            if ((reason == 4 || reason == 7 || reason == 14) && isRecentlyForcedBubble(key)) {
                                Log.i(TAG, "keep forced bubble: skip dismiss reason=" + reason + " key=" + key);
                                return null;
                            }
                        } catch (Throwable t) { Log.w(TAG, "dismiss guard: " + t.getMessage()); }
                        return chain.proceed();
                    });
                }
            }
            Log.i(TAG, "Hooked BubbleData.dismissBubbleWithKey OK");
        } catch (Throwable t) { Log.w(TAG, "Hook dismiss guard: " + t.getMessage()); }

        // 6. setSelectedBubbleInternal guard
        try {
            Class<?> dataCls = cl.loadClass("com.android.wm.shell.bubbles.BubbleData");
            java.lang.reflect.Method m = findMethodSystemUi(dataCls, "setSelectedBubbleInternal");
            if (m != null) {
                hook(m).intercept(chain -> {
                    try {
                        Object provider = chain.getArg(0);
                        Object bubbleData = chain.getThisObject();
                        if (provider != null && provider.getClass().getName().endsWith("BubbleEntry")) {
                            java.lang.reflect.Field f = findFieldSystemUi(bubbleData.getClass(), "mBubbles");
                            if (f != null) {
                                f.setAccessible(true);
                                java.util.List list = (java.util.List) f.get(bubbleData);
                                if (list != null && !list.contains(provider)) {
                                    list.add(provider);
                                    Log.i(TAG, "BubbleData.mBubbles forcibly added BubbleEntry");
                                }
                            }
                        }
                    } catch (Throwable t) { Log.w(TAG, "select guard: " + t.getMessage()); }
                    return chain.proceed();
                });
                Log.i(TAG, "Hooked setSelectedBubbleInternal OK");
            }
        } catch (Throwable t) { Log.w(TAG, "Hook select guard: " + t.getMessage()); }

        Log.i(TAG, "All SystemUI hooks installed");
    }

    /**
     * Evolution exposes shouldShowBubbleButton() without arguments, while Lunaris 3.12
     * passes the current NotificationEntry to shouldShowBubbleButton(NotificationEntry).
     */
    private static Method findBubbleVisibilityMethod(Class<?> contentViewClass) {
        for (Class<?> type = contentViewClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!"shouldShowBubbleButton".equals(method.getName())) continue;
                int count = method.getParameterCount();
                if (count == 0
                        || (count == 1
                        && method.getParameterTypes()[0].getName().contains("NotificationEntry"))) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }

    private void hookBubblesManagerConstructors(Class<?> bubblesCls) {
        try {
            for (java.lang.reflect.Constructor<?> constructor : bubblesCls.getDeclaredConstructors()) {
                hook(constructor).intercept(chain -> {
                    Object result = chain.proceed();
                    sBubblesManager = chain.getThisObject();
                    return result;
                });
            }
            Log.i(TAG, "Hooked BubblesManager constructors OK");
        } catch (Throwable t) {
            Log.w(TAG, "Hook BubblesManager constructors: " + t.getMessage());
        }
    }

    private static Context getNotificationAdapterContext(Object adapter) {
        try {
            Object starter = getFieldSystemUi(adapter, "notificationActivityStarter");
            Object context = getFieldSystemUi(starter, "mContext");
            if (context instanceof Context) return (Context) context;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * NotificationContentView uses a stable framework id for the ImageView it
     * passes to applyBubbleAction(). Evolution and Lunaris do not use the same
     * id, so try both known ids while avoiding a broad ImageView tree scan.
     */
    private static void replaceFreeformPopupIcon(View root, Context ctx) {
        if (root == null || ctx == null) return;
        View iconView = root.findViewById(LUNARIS_BUBBLE_ICON_ID);
        if (!(iconView instanceof android.widget.ImageView)) {
            iconView = root.findViewById(EVOLUTION_BUBBLE_ICON_ID);
        }
        if (!(iconView instanceof android.widget.ImageView)) return;
        android.graphics.drawable.Drawable icon = getFreeformActionIcon(
                ctx, ctx.getResources(), ctx.getPackageName());
        if (icon == null) return;
        android.widget.ImageView imageView = (android.widget.ImageView) iconView;
        imageView.setImageDrawable(icon);
        imageView.setContentDescription(getFreeformLabel(ctx));
    }

    private void hookNotificationContentLifecycle(Class<?> contentViewClass) {
        hookContentMethod(contentViewClass, "setHeadsUpChild");
        hookContentMethod(contentViewClass, "setContractedChild");
        hookContentMethod(contentViewClass, "setHeadsUp");
        hookContentMethod(contentViewClass, "setOnKeyguard");
        hookContentMethod(contentViewClass, "selectLayout");
        hookContentMethod(contentViewClass, "onAttachedToWindow");
        hookContentMethod(contentViewClass, "onLayout");
        hookContentMethod(contentViewClass, "onNotificationUpdated");
        hookContentMethod(contentViewClass, "setBubbleClickListener");
    }

    private void hookContentMethod(Class<?> targetClass, String name) {
        Set<String> hooked = new HashSet<>();
        for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName())
                        || !method.getDeclaringClass().getName().startsWith("com.android.systemui.")) {
                    continue;
                }
                String signature = method.toGenericString();
                if (!hooked.add(signature)) continue;
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object contentView = chain.getThisObject();
                        Object row = getFieldSystemUi(contentView, "mContainingNotification");
                        boolean leavingKeyguard = "setOnKeyguard".equals(name)
                                && method.getParameterCount() > 0
                                && !Boolean.TRUE.equals(chain.getArg(0));
                        if ("onNotificationUpdated".equals(name)
                                || "setBubbleClickListener".equals(name)
                                || leavingKeyguard) {
                            // The visibility check can happen during the same bind, before
                            // SystemUI's later callback refreshes the row. Keep metadata ready
                            // for both the shade and the Heads-up layout on first inflation.
                            prepareBubbleMetadata(getNotificationEntryFromRow(row));
                        }
                        Object result = chain.proceed();
                        updateHeadsUpSwipeHandle(contentView);
                        if ("onNotificationUpdated".equals(name)
                                || "setBubbleClickListener".equals(name)
                                || leavingKeyguard) {
                            prepareBubbleMetadata(getNotificationEntryFromRow(row));
                            refreshBubbleButton(row);
                        }
                        return result;
                    });
                    Log.i(TAG, "Hooked NotificationContentView." + name
                            + " " + method.getParameterCount() + " args");
                } catch (Throwable t) {
                    Log.w(TAG, "Hook NotificationContentView." + name + ": "
                            + t.getMessage());
                }
            }
        }
    }

    private void hookHeadsUpRowLifecycle(ClassLoader cl) {
        try {
            Class<?> rowClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
            hookRowMethods(rowClass, "setHeadsUp", false);
            hookRowMethods(rowClass, "onLayout", false);
            hookRowMethods(rowClass, "onAttachedToWindow", false);
            hookRowMethods(rowClass, "onDetachedFromWindow", false);
            hookRowMethods(rowClass, "setUserExpanded", false);
            hookRowMethods(rowClass, "setUserSwipingToExpandRow", false);
            hookRowMethods(rowClass, "setOnKeyguard", true);
            hookRowMethods(rowClass, "setHeadsUpAnimatingAway", false);
            hookRowMethods(rowClass, "onNotificationUpdated", true);
            hookRowMethods(rowClass, "setBubbleClickListener", true);
            Log.i(TAG, "Hooked ExpandableNotificationRow Heads-up lifecycle OK");
        } catch (Throwable t) {
            Log.e(TAG, "Hook ExpandableNotificationRow lifecycle: " + t.getMessage());
        }
        hookHeadsUpManagerLifecycle(cl);
    }

    private void hookHeadsUpManagerLifecycle(ClassLoader cl) {
        try {
            Class<?> managerClass = cl.loadClass(
                    "com.android.systemui.statusbar.notification.headsup.HeadsUpManagerImpl");
            Set<String> hooked = new HashSet<>();
            for (Class<?> type = managerClass; type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    String name = method.getName();
                    if (!("onEntryAnimatingAwayEnded".equals(name)
                            || "onShadeOrQsExpanded".equals(name)
                            || "onQsFullscreen".equals(name))
                            || method.getParameterCount() != 1
                            || !hooked.add(method.toGenericString())) continue;
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        if ("onEntryAnimatingAwayEnded".equals(name)) {
                            launchPendingForEntry(chain.getArg(0));
                        } else if ("onShadeOrQsExpanded".equals(name)) {
                            sShadeOrQsExpanded = Boolean.TRUE.equals(chain.getArg(0));
                            if (sShadeOrQsExpanded) {
                                removeAllTrackedHeadsUpHandles();
                            } else {
                                postAllTrackedHeadsUpSyncs();
                            }
                        } else if ("onQsFullscreen".equals(name)) {
                            sQsFullscreen = Boolean.TRUE.equals(chain.getArg(0));
                            if (sQsFullscreen) {
                                removeAllTrackedHeadsUpHandles();
                            } else {
                                postAllTrackedHeadsUpSyncs();
                            }
                        }
                        return result;
                    });
                    Log.i(TAG, "Hooked HeadsUpManagerImpl." + name + " OK");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Hook HeadsUpManagerImpl animation callback: " + t.getMessage());
        }
    }

    private void hookRowMethods(Class<?> targetClass, String name, boolean refreshBubble) {
        Set<String> hooked = new HashSet<>();
        for (Class<?> type = targetClass; type != null; type = type.getSuperclass()) {
            String typeName = type.getName();
            if ("android.view.View".equals(typeName)
                    || "android.view.ViewGroup".equals(typeName)
                    || "java.lang.Object".equals(typeName)) {
                break;
            }
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName())
                        || !method.getDeclaringClass().getName().startsWith("com.android.systemui.")) {
                    continue;
                }
                String signature = method.toGenericString();
                if (!hooked.add(signature)) continue;
                try {
                    method.setAccessible(true);
                    hook(method).intercept(chain -> {
                        Object result = chain.proceed();
                        Object row = chain.getThisObject();
                        if ("onDetachedFromWindow".equals(name)) {
                            cancelHeadsUpRowSync(row);
                            untrackHeadsUpRow(row);
                            cancelPendingForRow(row);
                            removeSwipeHandle(row);
                            return result;
                        }
                        if ("setHeadsUpAnimatingAway".equals(name)
                                && method.getParameterCount() > 0
                                && !Boolean.TRUE.equals(chain.getArg(0))) {
                            launchPendingForRow(row);
                        }
                        if ("setOnKeyguard".equals(name)
                                && method.getParameterCount() > 0) {
                            boolean onKeyguard = Boolean.TRUE.equals(chain.getArg(0));
                            sOnKeyguard = onKeyguard;
                            if (onKeyguard) {
                                // Lock screen activated: remove handles from ALL tracked
                                // rows, not just this one, so newly-arriving notifications
                                // that haven't been tracked yet are also covered.
                                removeAllTrackedHeadsUpHandles();
                                cancelHeadsUpRowSync(row);
                                removeSwipeHandle(row);
                                return result;
                            }
                            // Notifications received while the display was off are first
                            // bound to the keyguard layout. Once the row leaves keyguard,
                            // explicitly rebuild the Bubble button even if it is not a
                            // Heads-up row, because the popup-only lifecycle may not run.
                            prepareBubbleMetadata(getNotificationEntryFromRow(row));
                            refreshBubbleButton(row);
                        }
                        if (("setUserExpanded".equals(name)
                                || "setUserSwipingToExpandRow".equals(name)
                                || "setOnKeyguard".equals(name))
                                && !isHeadsUpPopupAvailable(row)) {
                            cancelHeadsUpRowSync(row);
                            removeSwipeHandle(row);
                            return result;
                        }
                        if (refreshBubble && !"setOnKeyguard".equals(name)) {
                            prepareBubbleMetadata(getNotificationEntryFromRow(row));
                            refreshBubbleButton(row);
                        }
                        postHeadsUpRowSync(row);
                        return result;
                    });
                    Log.i(TAG, "Hooked ExpandableNotificationRow." + name
                            + " " + method.getParameterCount() + " args");
                } catch (Throwable t) {
                    Log.w(TAG, "Hook ExpandableNotificationRow." + name + ": "
                            + t.getMessage());
                }
            }
        }
    }

    /**
     * Adds the small bottom-center handle only to a real Heads-up popup row.
     * The handle is hosted by the actual mHeadsUpChild when available, so it follows the same
     * content transform as the popup instead of appearing with the outer row first.
     */
    private static void updateHeadsUpSwipeHandle(Object contentViewObject) {
        Object row = getFieldSystemUi(contentViewObject, "mContainingNotification");
        if (row != null) {
            postHeadsUpRowSync(row);
        }
    }

    private static void postHeadsUpRowSync(Object row) {
        if (!(row instanceof View)) return;
        // When the shade or QS is expanded (or fullscreen), never schedule a sync.
        // The handle would be removed immediately by syncHeadsUpSwipeHandle anyway,
        // but skipping the post avoids a frame where the handle briefly re-appears
        // and also prevents adding a handle to a row that isn't tracked yet.
        if (sShadeOrQsExpanded || sQsFullscreen || sOnKeyguard) return;
        View rowView = (View) row;
        synchronized (sPendingHeadsUpSyncs) {
            if (sPendingHeadsUpSyncs.containsKey(rowView)) return;
            Runnable sync = () -> {
                synchronized (sPendingHeadsUpSyncs) {
                    sPendingHeadsUpSyncs.remove(rowView);
                }
                syncHeadsUpSwipeHandle(rowView);
            };
            sPendingHeadsUpSyncs.put(rowView, sync);
            rowView.postOnAnimation(sync);
        }
    }

    private static void cancelHeadsUpRowSync(Object row) {
        if (!(row instanceof View)) return;
        View rowView = (View) row;
        synchronized (sPendingHeadsUpSyncs) {
            Runnable sync = sPendingHeadsUpSyncs.remove(rowView);
            if (sync != null) rowView.removeCallbacks(sync);
        }
    }

    private static void syncHeadsUpSwipeHandle(Object row) {
        if (!(row instanceof ViewGroup)) return;
        ViewGroup rowView = (ViewGroup) row;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            postHeadsUpRowSync(row);
            return;
        }
        ViewGroup host = rowView;
        trackHeadsUpRow(row);
        Context ctx = host.getContext();
        try {
            boolean enabled = ModuleSettings.isSwipeHandleEnabled(ctx);
            Notification notification = getNotificationFromContentView(row);
            boolean validNotification = notification != null
                    && (notification.flags & Notification.FLAG_ONGOING_EVENT) == 0
                    && notification.contentIntent != null;
            if (!enabled || !validNotification || !isHeadsUpPopupAvailable(row)) {
                removeSwipeHandle(row);
                return;
            }

            // Keep one stable interactive view. Removing and re-adding it on every lifecycle
            // callback cancels an in-progress touch sequence and breaks swipe-down launching.
            View handle = rowView.findViewWithTag(HEADS_UP_HANDLE_TAG);
            if (handle != null && handle.getParent() != host) {
                if (handle.getParent() instanceof ViewGroup) {
                    ((ViewGroup) handle.getParent()).removeView(handle);
                }
                sSwipeHandleRows.remove(handle);
                handle = null;
            }
            if (handle == null) {
                SwipeHandleView newHandle = new SwipeHandleView(ctx);
                newHandle.setTag(HEADS_UP_HANDLE_TAG);
                newHandle.setContentDescription(getConfiguredOpenLabel(ctx));
                newHandle.setOnTouchListener(new View.OnTouchListener() {
                    private float downY;
                    private boolean passedThreshold;

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getActionMasked()) {
                            case MotionEvent.ACTION_DOWN:
                                requestDisallowParentIntercept(v, true);
                                downY = event.getY();
                                passedThreshold = false;
                                v.setAlpha(1f);
                                return true;
                            case MotionEvent.ACTION_MOVE:
                                requestDisallowParentIntercept(v, true);
                                if (event.getY() - downY >= swipeDistance(v.getContext())) {
                                    passedThreshold = true;
                                    v.setAlpha(0.72f);
                                }
                                return true;
                            case MotionEvent.ACTION_UP:
                                requestDisallowParentIntercept(v, false);
                                boolean shouldOpen = passedThreshold
                                        || event.getY() - downY >= swipeDistance(v.getContext());
                                passedThreshold = false;
                                if (shouldOpen) {
                                    boolean launched = openNotificationFromHeadsUp(v);
                                    if (!launched) v.setAlpha(1f);
                                } else {
                                    v.performClick();
                                }
                                return true;
                            case MotionEvent.ACTION_CANCEL:
                                passedThreshold = false;
                                v.setAlpha(1f);
                                requestDisallowParentIntercept(v, false);
                                return true;
                            default:
                                return true;
                        }
                    }
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        dp(ctx, ModuleSettings.getSwipeHandleLength(ctx) + 24),
                        dp(ctx, 30), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = 0;
                addHandleToRow(host, newHandle, lp);
                handle = newHandle;
                Log.i(TAG, "Heads-up swipe handle attached to "
                        + host.getClass().getSimpleName() + " inside "
                        + rowView.getClass().getSimpleName());
            }
            sSwipeHandleRows.put(handle, row);
            if (handle.getParent() != host) {
                if (handle.getParent() instanceof ViewGroup) {
                    ((ViewGroup) handle.getParent()).removeView(handle);
                }
                addHandleToRow(host, handle, null);
            }
            positionSwipeHandle(host, handle, ctx);
            if (host.indexOfChild(handle) != host.getChildCount() - 1) {
                handle.bringToFront();
            }
            if (handle.getAlpha() != 1f) handle.setAlpha(1f);
            String label = getConfiguredOpenLabel(ctx);
            if (!label.contentEquals(handle.getContentDescription())) {
                handle.setContentDescription(label);
            }
            if (handle.getVisibility() != View.VISIBLE) handle.setVisibility(View.VISIBLE);
        } catch (Throwable t) {
            Log.w(TAG, "sync Heads-up handle: " + t.getMessage());
        }
    }



    private static boolean isRowExpandedOrSwiping(Object row) {
        Object value = invokeSystemUi(row, "isUserSwipingToExpandRow");
        if (Boolean.TRUE.equals(value)) return true;
        value = invokeSystemUi(row, "isUserExpanded");
        if (Boolean.TRUE.equals(value)) return true;
        value = invokeSystemUi(row, "isExpanded");
        return Boolean.TRUE.equals(value);
    }

    private static boolean isHeadsUpPopupAvailable(Object row) {
        if (!isHeadsUpRow(row) || isRowExpandedOrSwiping(row)) return false;
        if (sShadeOrQsExpanded || sQsFullscreen) return false;

        Object onKeyguard = invokeSystemUi(row, "isOnKeyguard");
        if (Boolean.TRUE.equals(onKeyguard)
                || Boolean.TRUE.equals(getFieldSystemUi(row, "mOnKeyguard"))) {
            return false;
        }

        // Prefer the manager's authoritative shade state. This also covers the case where the
        // module is loaded after the notification shade was already expanded.
        Object manager = getFieldSystemUi(row, "mHeadsUpManager");
        if (Boolean.TRUE.equals(getFieldSystemUi(manager, "mIsShadeOrQsExpanded"))
                || Boolean.TRUE.equals(getFieldSystemUi(manager, "mIsQsFullscreen"))) {
            return false;
        }
        return true;
    }

    private static void trackHeadsUpRow(Object row) {
        if (!(row instanceof View)) return;
        synchronized (sTrackedHeadsUpRows) {
            sTrackedHeadsUpRows.put((View) row, Boolean.TRUE);
        }
    }

    private static void untrackHeadsUpRow(Object row) {
        if (!(row instanceof View)) return;
        synchronized (sTrackedHeadsUpRows) {
            sTrackedHeadsUpRows.remove((View) row);
        }
    }

    private static void removeAllTrackedHeadsUpHandles() {
        Set<View> rows;
        synchronized (sTrackedHeadsUpRows) {
            rows = new HashSet<>(sTrackedHeadsUpRows.keySet());
        }
        // Cancel any pending sync runnables first — they would re-add the handle
        // if they fire after the removal below.
        for (View row : rows) cancelHeadsUpRowSync(row);
        for (View row : rows) removeSwipeHandle(row);
    }

    private static void postAllTrackedHeadsUpSyncs() {
        Set<View> rows;
        synchronized (sTrackedHeadsUpRows) {
            rows = new HashSet<>(sTrackedHeadsUpRows.keySet());
        }
        for (View row : rows) postHeadsUpRowSync(row);
    }

    private static boolean isHeadsUpRow(Object row) {
        Object value = invokeSystemUi(row, "isHeadsUpState");
        if (value instanceof Boolean) return Boolean.TRUE.equals(value);
        value = getFieldSystemUi(row, "mIsHeadsUp");
        return Boolean.TRUE.equals(value);
    }

    private static void positionSwipeHandle(ViewGroup host, View handle, Context ctx) {
        int handleHeight = dp(ctx, 30);
        int bottomMargin = dp(ctx, ModuleSettings.getSwipeHandleBottomMargin(ctx));
        int actualHeight = 0;
        Object value = invokeSystemUi(host, "getActualHeight");
        if (value instanceof Number) actualHeight = ((Number) value).intValue();
        int layoutHeight = host.getHeight();
        int measuredHeight = host.getMeasuredHeight();

        // getActualHeight() is an animation value on ExpandableView. During the first
        // unexpanded Heads-up layout it can still describe the intrinsic/expanded row height,
        // while the View itself is already laid out at the smaller contracted height. Using it
        // directly places the handle below the row's clip bounds. Always clamp the placement to
        // the actual laid-out bounds, and then to any explicit clip bounds applied by SystemUI.
        int visibleBottom = layoutHeight > 0 ? layoutHeight : measuredHeight;
        if (visibleBottom <= 0) visibleBottom = actualHeight;
        if (actualHeight > 0 && visibleBottom > 0) {
            visibleBottom = Math.min(visibleBottom, actualHeight);
        }
        Rect clipBounds = host.getClipBounds();
        int visibleTop = 0;
        if (clipBounds != null) {
            visibleTop = Math.max(0, clipBounds.top);
            if (clipBounds.bottom > 0) {
                visibleBottom = visibleBottom > 0
                        ? Math.min(visibleBottom, clipBounds.bottom)
                        : clipBounds.bottom;
            }
        }
        if (visibleBottom <= 0) {
            handle.setVisibility(View.INVISIBLE);
            return;
        }
        int topMargin = Math.max(visibleTop,
                visibleBottom - handleHeight - bottomMargin);
        int width = dp(ctx, ModuleSettings.getSwipeHandleLength(ctx) + 24);
        if (host instanceof FrameLayout) {
            FrameLayout.LayoutParams lp = handle.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) handle.getLayoutParams()
                    : new FrameLayout.LayoutParams(width, handleHeight);
            boolean changed = lp.width != width || lp.height != handleHeight
                    || lp.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL)
                    || lp.topMargin != topMargin || lp.bottomMargin != 0;
            lp.width = width;
            lp.height = handleHeight;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = topMargin;
            lp.bottomMargin = 0;
            if (changed) handle.setLayoutParams(lp);
        } else {
            ViewGroup.LayoutParams raw = handle.getLayoutParams();
            ViewGroup.MarginLayoutParams lp = raw instanceof ViewGroup.MarginLayoutParams
                    ? (ViewGroup.MarginLayoutParams) raw
                    : new ViewGroup.MarginLayoutParams(width, handleHeight);
            boolean changed = lp.width != width || lp.height != handleHeight
                    || lp.topMargin != topMargin;
            lp.width = width;
            lp.height = handleHeight;
            lp.topMargin = topMargin;
            if (changed) handle.setLayoutParams(lp);
            handle.setX(Math.max(0, (host.getWidth() - width) / 2f));
        }

        // Notification rows can skip measuring a child that was injected after their normal
        // content children. Give our fixed-size view an explicit measure pass as a safeguard.
        int exactWidth = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY);
        int exactHeight = View.MeasureSpec.makeMeasureSpec(handleHeight, View.MeasureSpec.EXACTLY);
        if (handle.getMeasuredWidth() != width || handle.getMeasuredHeight() != handleHeight) {
            handle.measure(exactWidth, exactHeight);
        }
    }

    private static void addHandleToRow(ViewGroup host, View handle,
            FrameLayout.LayoutParams initialParams) {
        if (host instanceof FrameLayout) {
            host.addView(handle, initialParams != null
                    ? initialParams : new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            host.addView(handle, initialParams != null
                    ? new ViewGroup.MarginLayoutParams(initialParams.width, initialParams.height)
                    : new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static void removeSwipeHandle(Object row) {
        if (!(row instanceof ViewGroup)) return;
        ViewGroup rowView = (ViewGroup) row;
        View handle = rowView.findViewWithTag(HEADS_UP_HANDLE_TAG);
        while (handle != null) {
            sSwipeHandleRows.remove(handle);
            if (handle.getParent() instanceof ViewGroup) {
                ((ViewGroup) handle.getParent()).removeView(handle);
            } else {
                break;
            }
            handle = rowView.findViewWithTag(HEADS_UP_HANDLE_TAG);
        }
    }

    private static boolean isOnKeyguardRow(Object row) {
        if (row == null) return false;
        Object value = invokeSystemUi(row, "isOnKeyguard");
        if (Boolean.TRUE.equals(value)) return true;
        return Boolean.TRUE.equals(getFieldSystemUi(row, "mOnKeyguard"));
    }

    private static boolean isOnKeyguardContentView(Object contentView) {
        if (contentView == null) return false;
        return Boolean.TRUE.equals(getFieldSystemUi(contentView, "mOnKeyguard"));
    }

    private static boolean isBubbleButtonEligible(Object row) {
        return isBubbleButtonEligible(row, getNotificationEntryFromRow(row));
    }

    private static boolean isBubbleButtonEligible(Object row, Object entry) {
        if (row != null && isOnKeyguardRow(row)) return false;
        try {
            Object sbn = entry != null ? getFieldSystemUi(entry, "mSbn") : null;
            if (sbn == null) sbn = getNotificationSbnFromRow(row);
            Notification notification = sbn != null
                    ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            return notification != null
                    && (notification.flags & Notification.FLAG_ONGOING_EVENT) == 0
                    && notification.contentIntent != null;
        } catch (Throwable t) {
            Log.w(TAG, "Bubble button eligibility: " + t.getMessage());
            return false;
        }
    }

    private static Object getNotificationSbnFromRow(Object row) {
        if (row == null) return null;
        try {
            Object adapter = getFieldSystemUi(row, "mEntryAdapter");
            Object sbn = adapter != null ? invokeSystemUi(adapter, "getSbn") : null;
            if (sbn != null) return sbn;
        } catch (Throwable ignored) {}
        try {
            Object entry = getFieldSystemUi(row, "mEntry");
            return entry != null ? getFieldSystemUi(entry, "mSbn") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getNotificationEntryFromRow(Object row) {
        if (row == null) return null;
        Object entry = getFieldSystemUi(row, "mEntry");
        if (entry != null) return entry;
        Object adapter = getFieldSystemUi(row, "mEntryAdapter");
        if (adapter == null) return null;
        entry = getFieldSystemUi(adapter, "entry");
        if (entry != null) return entry;
        entry = invokeSystemUi(adapter, "getEntry");
        return entry != null ? entry : invokeSystemUi(adapter, "getNotificationEntry");
    }

    private static Notification getNotificationFromContentView(Object row) {
        Object sbn = getNotificationSbnFromRow(row);
        try {
            return sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object getHeadsUpRowFromHandle(View handle) {
        if (handle == null) return null;
        Object mappedRow = sSwipeHandleRows.get(handle);
        if (mappedRow != null) return mappedRow;
        android.view.ViewParent parent = handle.getParent();
        while (parent != null) {
            Object row = getFieldSystemUi(parent, "mContainingNotification");
            if (row != null) return row;
            // When the handle is attached directly to ExpandableNotificationRow, the parent
            // itself is the row and has isHeadsUpState().
            if (findMethodSystemUi(parent.getClass(), "isHeadsUpState") != null) {
                return parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    private static float swipeDistance(Context ctx) {
        return Math.max(ctx.getResources().getDisplayMetrics().density * SWIPE_HANDLE_MIN_DISTANCE_DP,
                android.view.ViewConfiguration.get(ctx).getScaledTouchSlop() * 2f);
    }

    private static void requestDisallowParentIntercept(View view, boolean disallow) {
        try {
            android.view.ViewParent parent = view.getParent();
            while (parent != null) {
                parent.requestDisallowInterceptTouchEvent(disallow);
                parent = parent.getParent();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean openNotificationFromHeadsUp(View handle) {
        try {
            Object row = getHeadsUpRowFromHandle(handle);
            Object sbn = getNotificationSbnFromRow(row);
            Object entry = getNotificationEntryFromRow(row);
            if (sbn == null) return false;
            String key = getNotificationKey(sbn);
            PendingHeadsUpLaunch pending = key != null
                    ? new PendingHeadsUpLaunch(handle.getContext(), row, sbn, entry,
                    "Heads-up swipe") : null;
            if (pending != null) sPendingHeadsUpLaunches.put(key, pending);
            if (requestHeadsUpPopupRemoval(row)) {
                // Launch only after the row/manager reports that the normal removal
                // animation has finished while the original row is still valid.
                if (pending != null) {
                    waitForHeadsUpRemoval(pending, 0);
                }
                return true;
            }
            if (pending != null) sPendingHeadsUpLaunches.remove(key, pending);
            return openNotificationForSnapshot(handle.getContext(), sbn, entry,
                    "Heads-up swipe");
        } catch (Throwable t) {
            Log.w(TAG, "Heads-up swipe open: " + t.getMessage());
            return false;
        }
    }

    private static boolean requestHeadsUpPopupRemoval(Object row) {
        if (row == null) return false;
        try {
            Object sbn = getNotificationSbnFromRow(row);
            Object keyValue = sbn != null ? invokeSystemUi(sbn, "getKey") : null;
            if (!(keyValue instanceof String)) return false;
            String key = (String) keyValue;
            Object manager = getHeadsUpManager(row);
            if (manager != null && removeHeadsUpEntry(manager, key)) {
                Log.i(TAG, "Requested normal Heads-up removal before opening " + key);
                return true;
            }
            Log.w(TAG, "Heads-up manager unavailable; leaving popup lifecycle to SystemUI");
        } catch (Throwable t) {
            Log.w(TAG, "Request Heads-up removal failed: " + t.getMessage());
        }
        return false;
    }

    private static void waitForHeadsUpRemoval(PendingHeadsUpLaunch pending, int attempt) {
        if (pending == null || pending.started
                || sPendingHeadsUpLaunches.get(pending.key) != pending) return;
        boolean stillHeadsUp = isHeadsUpRow(pending.row);
        if (!stillHeadsUp) {
            launchPendingHeadsUp(pending);
            return;
        }
        new android.os.Handler(Looper.getMainLooper()).postDelayed(
                () -> waitForHeadsUpRemoval(pending, attempt + 1), 16L);
    }

    private static void launchPendingForRow(Object row) {
        String key = getNotificationKey(getNotificationSbnFromRow(row));
        if (key == null) return;
        PendingHeadsUpLaunch pending = sPendingHeadsUpLaunches.get(key);
        if (pending != null && pending.row == row) launchPendingHeadsUp(pending);
    }

    private static void launchPendingForEntry(Object entry) {
        Object sbn = entry != null ? getFieldSystemUi(entry, "mSbn") : null;
        String key = getNotificationKey(sbn);
        if (key == null) return;
        PendingHeadsUpLaunch pending = sPendingHeadsUpLaunches.get(key);
        if (pending != null && pending.entry == entry) launchPendingHeadsUp(pending);
    }

    private static void cancelPendingForRow(Object row) {
        String key = getNotificationKey(getNotificationSbnFromRow(row));
        if (key == null) return;
        PendingHeadsUpLaunch pending = sPendingHeadsUpLaunches.get(key);
        if (pending != null && pending.row == row) {
            sPendingHeadsUpLaunches.remove(key, pending);
        }
    }

    private static void launchPendingHeadsUp(String key) {
        PendingHeadsUpLaunch pending = sPendingHeadsUpLaunches.get(key);
        launchPendingHeadsUp(pending);
    }

    private static void launchPendingHeadsUp(PendingHeadsUpLaunch pending) {
        if (pending == null || pending.started
                || !sPendingHeadsUpLaunches.remove(pending.key, pending)) return;
        pending.started = true;
        openNotificationForSnapshot(pending.context, pending.sbn, pending.entry,
                pending.reason);
    }

    private static String getNotificationKey(Object sbn) {
        Object key = sbn != null ? invokeSystemUi(sbn, "getKey") : null;
        return key instanceof String ? (String) key : null;
    }

    private static boolean openNotificationForSnapshot(Context ctx, Object sbn,
            Object entry, String reason) {
        if (sbn == null) return false;
        if (isFreeformOpenMode(ctx)) {
            if (launchNotificationInFreeformSbn(ctx, sbn, reason)) return true;
            Log.i(TAG, reason + ": freeform unavailable, falling back to Bubble");
        }
        if (entry != null && sBubblesManager != null
                && expandAppBubbleFromNotification(sBubblesManager, entry, reason)) {
            return true;
        }
        boolean launched = launchNotificationFullscreenSbn(sbn, reason);
        if (entry == null) {
            Log.w(TAG, reason + ": NotificationEntry unavailable; used direct Intent fallback");
        } else if (sBubblesManager == null) {
            Log.w(TAG, reason + ": BubblesManager is not ready");
        }
        return launched;
    }

    private static Object getHeadsUpManager(Object row) {
        Object directManager = getFieldSystemUi(row, "mHeadsUpManager");
        if (directManager != null) return directManager;
        try {
            ClassLoader cl = row.getClass().getClassLoader();
            String[] names = {
                    "com.android.systemui.statusbar.notification.headsup.HeadsUpManager",
                    "com.android.systemui.statusbar.policy.HeadsUpManager"
            };
            Class<?> dependency = cl.loadClass("com.android.systemui.Dependency");
            Method get = findMethodSystemUi(dependency, "get", Class.class);
            if (get == null) return null;
            for (String name : names) {
                try {
                    return get.invoke(null, cl.loadClass(name));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            Log.w(TAG, "HeadsUpManager lookup failed: " + t.getMessage());
        }
        return null;
    }

    private static boolean removeHeadsUpEntry(Object manager, String key) {
        Method selected = null;
        for (Class<?> type = manager.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!"removeNotification".equals(method.getName())
                        || types.length < 2 || types[0] != String.class) {
                    continue;
                }
                boolean supported = true;
                for (int i = 1; i < types.length; i++) {
                    if (types[i] != boolean.class && types[i] != Boolean.class
                            && types[i] != String.class) {
                        supported = false;
                        break;
                    }
                }
                if (!supported) continue;
                method.setAccessible(true);
                if (types.length == 4 && types[1] == boolean.class
                        && types[2] == boolean.class && types[3] == String.class) {
                    selected = method;
                    break;
                }
                if (selected == null && types.length == 3
                        && types[1] == boolean.class && types[2] == String.class) {
                    selected = method;
                } else if (selected == null && types.length == 2
                        && types[1] == boolean.class) {
                    selected = method;
                }
            }
            if (selected != null && selected.getParameterCount() == 4) break;
        }
        if (selected == null) return false;
        try {
            Class<?>[] types = selected.getParameterTypes();
            Object[] args = new Object[types.length];
            args[0] = key;
            for (int i = 1; i < types.length; i++) {
                if (types[i] == boolean.class || types[i] == Boolean.class) {
                    // Skip the minimum HUN dwell time, but keep SystemUI's normal
                    // going-away animation. Evolution17's four-arg overload is preferred.
                    args[i] = true;
                } else if (types[i] == String.class) {
                    args[i] = "MoreBubbleButton swipe";
                } else {
                    return false;
                }
            }
            Object result = selected.invoke(manager, args);
            Log.i(TAG, "HeadsUpManager.removeNotification invoked key=" + key
                    + " result=" + result + " args=" + types.length);
            // false means the manager queued a normal removal after the dwell time;
            // invocation itself is still successful and the completion callback/poll
            // controls when the target is launched.
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "HeadsUpManager.removeNotification failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean openNotificationForRow(Context ctx, Object row, String reason) {
        Object sbn = getNotificationSbnFromRow(row);
        Object entry = getNotificationEntryFromRow(row);
        if (sbn == null) return false;
        if (isFreeformOpenMode(ctx)) {
            if (launchNotificationInFreeformSbn(ctx, sbn, reason)) return true;
            Log.i(TAG, reason + ": freeform unavailable, falling back to Bubble");
        }
        if (entry != null && sBubblesManager != null
                && expandAppBubbleFromNotification(sBubblesManager, entry, reason)) {
            return true;
        }
        boolean launched = launchNotificationFullscreenSbn(sbn, reason);
        if (entry == null) {
            Log.w(TAG, reason + ": NotificationEntry unavailable; used direct Intent fallback");
        } else if (sBubblesManager == null) {
            Log.w(TAG, reason + ": BubblesManager is not ready");
        }
        return launched;
    }

    private static boolean launchNotificationInFreeform(Context ctx, Object entry, String reason) {
        return launchNotificationInFreeformSbn(ctx,
                getFieldSystemUi(entry, "mSbn"), reason);
    }

    private static boolean launchNotificationInFreeformSbn(Context ctx, Object sbn, String reason) {
        try {
            Notification notification = sbn != null
                    ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            if (notification == null || notification.contentIntent == null) return false;
            Log.i(TAG, reason + ": Freeform notification route creator="
                    + safePendingIntentCreator(notification.contentIntent)
                    + " target=" + summarizeIntent(getNotificationTargetIntent(notification,
                    getNotificationPackage(sbn))));
            if (!isFreeformSupported(ctx)) {
                Log.w(TAG, reason + ": device freeform support is disabled");
                showFreeformUnavailableToast(ctx);
                return false;
            }
            // Evolution17's actual Freeform implementation is LMOFreeform. It accepts
            // the target activity through an exported receiver and starts it from the
            // system-side Binder service. Sending the notification PendingIntent with
            // ActivityOptions alone can return without throwing while Android 17 blocks
            // the background activity launch, leaving the task fullscreen.
            if (isLmoFreeformServiceAvailable()
                    && launchNotificationViaLmoFreeform(ctx, sbn, notification, reason)) {
                return true;
            }
            ActivityOptions options = ActivityOptions.makeBasic();
            if (!configureFreeformOptions(options, ctx, true)) return false;
            notification.contentIntent.send(options.toBundle());
            Log.i(TAG, reason + ": launched notification PendingIntent in freeform");
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, reason + ": notification PendingIntent canceled");
        } catch (Throwable t) {
            Log.w(TAG, reason + ": freeform notification launch failed: " + t.getMessage());
            showFreeformUnavailableToast(ctx);
        }
        return false;
    }

    private static boolean launchNotificationViaLmoFreeform(Context ctx, Object sbn,
            Notification notification, String reason) {
        // The LMO service has a dedicated PendingIntent path. Unlike the exported
        // component-only receiver, it preserves the notification's deep-link,
        // extras and creator token (for example, a WeChat conversation target).
        // It is restricted to SYSTEM_UID by LMOFreeformUIService, so only try it
        // when this hook is actually running in a system process. SystemUI itself
        // is a separate application UID and must use the task-move path below.
        if (android.os.Process.myUid() == android.os.Process.SYSTEM_UID
                && launchNotificationViaLmoBinder(ctx, sbn, notification, reason)) return true;

        // Evolution's own SystemUI uses the hidden ActivityOptions.setLaunchTaskId() API
        // to deliver a later PendingIntent into an already-created task. Start the
        // resolved component in LMO Freeform first, then inject the exact notification
        // PendingIntent into that task to avoid the temporary fullscreen transition.
        if (launchNotificationViaLmoComponentThenIntent(ctx, sbn, notification, reason)) {
            return true;
        }

        // SystemUI cannot call the LMO Binder service directly. Stage the exact
        // notification PendingIntent behind the current task, then ask the
        // exported system-UID receiver to move that task into an LMO Freeform
        // display. This preserves the notification extras without exposing a
        // fullscreen task-switch animation to the user.
        if (launchNotificationByTaskMove(ctx, sbn, notification, reason)) return true;

        return launchNotificationViaLmoComponent(ctx, sbn, notification, reason);
    }

    private static boolean launchNotificationViaLmoComponentThenIntent(Context ctx, Object sbn,
            Notification notification, String reason) {
        if (notification == null || notification.contentIntent == null) return false;

        try {
            Method setLaunchTaskId = findMethodSystemUi(ActivityOptions.class,
                    "setLaunchTaskId", int.class);
            if (setLaunchTaskId == null) {
                Log.w(TAG, reason + ": LMO two-stage launch unavailable: setLaunchTaskId missing");
                return false;
            }

            String packageName = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            if (packageName == null) return false;
            Intent targetIntent = getNotificationTargetIntent(notification, packageName);
            ComponentName component = targetIntent != null ? targetIntent.getComponent() : null;
            if (component == null && targetIntent != null) {
                component = targetIntent.resolveActivity(ctx.getPackageManager());
            }
            if (component == null) {
                Log.w(TAG, reason + ": LMO two-stage target activity not resolved");
                return false;
            }

            int userId = getNotificationUserId(sbn);
            List<?> tasksBefore = getRunningTasks(ctx);
            if (tasksBefore == null) return false;
            final Set<Integer> taskIdsBefore = collectTaskIds(tasksBefore);

            Intent startFreeform = new Intent("com.libremobileos.freeform.START_FREEFORM")
                    .setComponent(new ComponentName(
                            "com.libremobileos.freeform",
                            "com.libremobileos.freeform.receiver.StartFreeformReceiver"))
                    .putExtra("packageName", component.getPackageName())
                    .putExtra("activityName", component.getClassName())
                    .putExtra("userId", userId)
                    .putExtra("taskId", -1);
            UserHandle user = getUserHandle(userId);
            if (user == null) return false;
            ctx.sendBroadcastAsUser(startFreeform, user);

            final ComponentName finalComponent = component;
            final String finalPackageName = packageName;
            final long launchTime = SystemClock.uptimeMillis();
            Thread injector = new Thread(() -> deliverNotificationIntentToLmoTask(
                    notification.contentIntent, ctx, finalPackageName, finalComponent,
                    taskIdsBefore, launchTime, setLaunchTaskId, reason),
                    "MoreBubble-LMO-intent-injector");
            injector.start();
            Log.i(TAG, reason + ": requested LMO Freeform component first; "
                    + "notification Intent will be injected after task creation for "
                    + finalComponent.flattenToShortString());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": LMO two-stage launch failed: " + t.getMessage());
        }
        return false;
    }

    private static void deliverNotificationIntentToLmoTask(PendingIntent pendingIntent,
            Context ctx, String packageName, ComponentName component, Set<Integer> taskIdsBefore,
            long launchTime, Method setLaunchTaskId, String reason) {
        try {
            int taskId = waitForNewLmoTask(ctx, packageName, component, taskIdsBefore,
                    launchTime);
            if (taskId < 0) {
                Log.w(TAG, reason + ": LMO two-stage task was not created");
                return;
            }
            ActivityOptions options = ActivityOptions.makeBasic();
            setLaunchTaskId.invoke(options, taskId);
            setPendingIntentBackgroundStartAllowed(options);
            Method avoidMoveToFront = findMethodSystemUi(options.getClass(),
                    "setAvoidMoveToFront");
            if (avoidMoveToFront != null) avoidMoveToFront.invoke(options);

            pendingIntent.send(options.toBundle());
            Log.i(TAG, reason + ": launched component in LMO Freeform, then delivered "
                    + "notification Intent into taskId=" + taskId + " for "
                    + component.flattenToShortString());
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, reason + ": notification PendingIntent canceled in LMO two-stage launch");
        } catch (Throwable t) {
            Log.w(TAG, reason + ": LMO two-stage Intent injection failed: "
                    + t.getMessage());
        }
    }

    private static boolean launchNotificationViaLmoComponent(Context ctx, Object sbn,
            Notification notification, String reason) {
        try {
            String packageName = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            Intent targetIntent = getNotificationTargetIntent(notification, packageName);
            if (targetIntent == null) return false;
            ComponentName component = targetIntent.getComponent();
            if (component == null) component = targetIntent.resolveActivity(ctx.getPackageManager());
            if (component == null) {
                Log.w(TAG, reason + ": LMO Freeform target activity not resolved");
                return false;
            }

            int userId = 0;
            try { userId = (int) sbn.getClass().getMethod("getUserId").invoke(sbn); }
            catch (Throwable ignored) {}
            if (userId < 0) userId = 0;

            Intent startFreeform = new Intent("com.libremobileos.freeform.START_FREEFORM")
                    .setComponent(new ComponentName(
                            "com.libremobileos.freeform",
                            "com.libremobileos.freeform.receiver.StartFreeformReceiver"))
                    .putExtra("packageName", component.getPackageName())
                    .putExtra("activityName", component.getClassName())
                    .putExtra("userId", userId)
                    .putExtra("taskId", -1);
            UserHandle user = getUserHandle(userId);
            if (user == null) return false;
            ctx.sendBroadcastAsUser(startFreeform, user);
            Log.i(TAG, reason + ": requested LMO Freeform for "
                    + component.flattenToShortString());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": LMO Freeform request failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean launchNotificationByTaskMove(Context ctx, Object sbn,
            Notification notification, String reason) {
        if (notification == null || notification.contentIntent == null) return false;

        try {
            String packageName = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            if (packageName == null) return false;
            Intent targetIntent = getNotificationTargetIntent(notification, packageName);
            ComponentName target = targetIntent != null ? targetIntent.getComponent() : null;
            if (target == null && targetIntent != null) {
                target = targetIntent.resolveActivity(ctx.getPackageManager());
            }
            if (target == null) {
                Intent launchIntent = ctx.getPackageManager().getLaunchIntentForPackage(packageName);
                if (launchIntent != null) target = launchIntent.resolveActivity(ctx.getPackageManager());
            }

            int userId = getNotificationUserId(sbn);
            // Check the task-query capability before consuming the notification
            // PendingIntent. If this device hides running tasks from SystemUI,
            // the caller can still use the normal Bubble/fullscreen fallback.
            if (getRunningTasks(ctx) == null) return false;

            // Android 17 defaults PendingIntent launches from SystemUI to
            // MODE_SYSTEM_DEFINED. The notification creator is a background
            // app, so ActivityTaskManager rejects the launch before a task can
            // be created. SystemUI's own remote-action code opts into mode 1;
            // mirror that behavior here so the task-move path has a real task.
            // Evolution's LMO implementation owns the Freeform display area;
            // Android's native launchWindowingMode=5 is ignored on this build.
            // Launch the exact Intent behind the current task instead, so the
            // temporary normal task is never shown before LMO reparents it.
            ActivityOptions pendingIntentOptions = ActivityOptions.makeTaskLaunchBehind();
            setPendingIntentBackgroundStartAllowed(pendingIntentOptions);
            notification.contentIntent.send(pendingIntentOptions.toBundle());
            long launchTime = SystemClock.uptimeMillis();
            ComponentName finalTarget = target;
            Context appContext = ctx.getApplicationContext();
            Thread mover = new Thread(() -> {
                int taskId = waitForNotificationTask(appContext, packageName, finalTarget,
                        launchTime);
                if (taskId < 0) {
                    Log.w(TAG, reason + ": notification task was not found for LMO Freeform");
                    return;
                }
                requestLmoFreeformForTask(appContext, packageName,
                        finalTarget != null ? finalTarget.getClassName() : "unknown",
                        userId, taskId, reason);
            }, "MoreBubble-LMO-task-move");
            mover.start();
            Log.i(TAG, reason + ": staged notification PendingIntent behind current task; "
                    + "moving its task to LMO Freeform");
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, reason + ": notification PendingIntent canceled before LMO task move");
        } catch (Throwable t) {
            Log.w(TAG, reason + ": LMO task move preparation failed: " + t.getMessage());
        }
        return false;
    }

    private static void setPendingIntentBackgroundStartAllowed(ActivityOptions options) {
        try {
            Method setter = ActivityOptions.class.getMethod(
                    "setPendingIntentBackgroundActivityStartMode", int.class);
            int mode = 1; // MODE_BACKGROUND_ACTIVITY_START_ALLOWED on API 34+.
            try {
                mode = ActivityOptions.class.getField(
                        "MODE_BACKGROUND_ACTIVITY_START_ALLOWED").getInt(null);
            } catch (Throwable ignored) {}
            setter.invoke(options, mode);
        } catch (Throwable t) {
            Log.w(TAG, "PendingIntent BAL allowance unavailable: " + t.getMessage());
        }
    }

    private static int getNotificationUserId(Object sbn) {
        try {
            int userId = (int) sbn.getClass().getMethod("getUserId").invoke(sbn);
            return userId >= 0 ? userId : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static UserHandle getUserHandle(int userId) {
        try {
            return (UserHandle) UserHandle.class.getMethod("of", int.class)
                    .invoke(null, userId);
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Constructor<UserHandle> constructor =
                    UserHandle.class.getDeclaredConstructor(int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(userId);
        } catch (Throwable ignored) {}
        try {
            return (UserHandle) UserHandle.class.getField("CURRENT").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static List<?> getRunningTasks(Context ctx) {
        try {
            Object activityManager = ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) return null;
            Method getRunningTasks = activityManager.getClass()
                    .getMethod("getRunningTasks", int.class);
            Object result = getRunningTasks.invoke(activityManager, 32);
            return result instanceof List ? (List<?>) result : null;
        } catch (Throwable t) {
            Log.w(TAG, "LMO task move cannot query running tasks: " + t.getMessage());
            return null;
        }
    }

    private static int waitForNotificationTask(Context ctx, String packageName,
            ComponentName target, long launchTime) {
        long deadline = SystemClock.uptimeMillis() + 1800L;
        while (SystemClock.uptimeMillis() < deadline) {
            List<?> tasks = getRunningTasks(ctx);
            if (tasks == null) return -1;
            int taskId = findNotificationTask(tasks, packageName, target, launchTime);
            if (taskId >= 0) return taskId;
            SystemClock.sleep(80L);
        }
        return -1;
    }

    private static int waitForNewLmoTask(Context ctx, String packageName,
            ComponentName target, Set<Integer> taskIdsBefore, long launchTime) {
        long deadline = SystemClock.uptimeMillis() + 2200L;
        while (SystemClock.uptimeMillis() < deadline) {
            List<?> tasks = getRunningTasks(ctx);
            if (tasks == null) return -1;
            int taskId = findNewLmoTask(tasks, packageName, target, taskIdsBefore, launchTime);
            if (taskId >= 0) return taskId;
            SystemClock.sleep(80L);
        }
        return -1;
    }

    private static Set<Integer> collectTaskIds(List<?> tasks) {
        Set<Integer> ids = new HashSet<>();
        for (Object task : tasks) {
            try {
                ids.add(task.getClass().getField("taskId").getInt(task));
            } catch (Throwable ignored) {}
        }
        return ids;
    }

    private static int findNewLmoTask(List<?> tasks, String packageName,
            ComponentName target, Set<Integer> taskIdsBefore, long launchTime) {
        int fallbackTaskId = -1;
        long fallbackActiveTime = Long.MIN_VALUE;
        for (Object task : tasks) {
            try {
                int taskId = task.getClass().getField("taskId").getInt(task);
                if (taskIdsBefore.contains(taskId)) continue;
                ComponentName top = (ComponentName) task.getClass()
                        .getField("topActivity").get(task);
                ComponentName base = (ComponentName) task.getClass()
                        .getField("baseActivity").get(task);
                ComponentName match = top != null ? top : base;
                if (match == null || !packageName.equals(match.getPackageName())) continue;
                if (target != null && !target.getClassName().equals(match.getClassName())
                        && (base == null || !target.getClassName().equals(base.getClassName()))) {
                    continue;
                }
                long activeTime = Long.MIN_VALUE;
                try {
                    activeTime = task.getClass().getField("lastActiveTime").getLong(task);
                } catch (Throwable ignored) {}
                if (activeTime >= launchTime || fallbackTaskId < 0) {
                    if (activeTime >= fallbackActiveTime) {
                        fallbackTaskId = taskId;
                        fallbackActiveTime = activeTime;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return fallbackTaskId;
    }

    private static int findNotificationTask(List<?> tasks, String packageName,
            ComponentName target, long launchTime) {
        int fallbackTaskId = -1;
        long fallbackActiveTime = Long.MIN_VALUE;
        for (Object task : tasks) {
            try {
                int taskId = task.getClass().getField("taskId").getInt(task);
                ComponentName top = (ComponentName) task.getClass()
                        .getField("topActivity").get(task);
                ComponentName base = (ComponentName) task.getClass()
                        .getField("baseActivity").get(task);
                ComponentName match = top != null ? top : base;
                if (match == null || !packageName.equals(match.getPackageName())) continue;
                if (target != null && !target.getClassName().equals(match.getClassName())
                        && (base == null || !target.getClassName().equals(base.getClassName()))) {
                    continue;
                }
                long activeTime = Long.MIN_VALUE;
                try {
                    activeTime = task.getClass().getField("lastActiveTime").getLong(task);
                } catch (Throwable ignored) {}
                if (activeTime >= launchTime || fallbackTaskId < 0) {
                    if (activeTime >= fallbackActiveTime) {
                        fallbackTaskId = taskId;
                        fallbackActiveTime = activeTime;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return fallbackTaskId;
    }

    private static boolean requestLmoFreeformForTask(Context ctx, String packageName,
            String activityName, int userId, int taskId, String reason) {
        try {
            Intent startFreeform = new Intent("com.libremobileos.freeform.START_FREEFORM")
                    .setComponent(new ComponentName(
                            "com.libremobileos.freeform",
                            "com.libremobileos.freeform.receiver.StartFreeformReceiver"))
                    .putExtra("packageName", packageName)
                    .putExtra("activityName", activityName)
                    .putExtra("userId", userId)
                    .putExtra("taskId", taskId);
            UserHandle user = getUserHandle(userId);
            if (user == null) return false;
            ctx.sendBroadcastAsUser(startFreeform, user);
            Log.i(TAG, reason + ": requested LMO Freeform task move for "
                    + packageName + " taskId=" + taskId);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": LMO Freeform task move failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean launchNotificationViaLmoBinder(Context ctx, Object sbn,
            Notification notification, String reason) {
        if (notification == null || notification.contentIntent == null) return false;

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            IBinder binder = getLmoFreeformBinder();
            if (binder == null) return false;

            String packageName = null;
            try {
                packageName = notification.contentIntent.getCreatorPackage();
            } catch (Throwable ignored) {}
            if (packageName == null && sbn != null) {
                try {
                    packageName = (String) sbn.getClass().getMethod("getPackageName")
                            .invoke(sbn);
                } catch (Throwable ignored) {}
            }
            if (packageName == null) return false;

            android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            int width = Math.max(1, Math.min(Math.round(dm.widthPixels * 0.7f), 600));
            int height = Math.max(1, Math.min(Math.round(dm.heightPixels * 0.4f), 600));
            int densityDpi = Math.max(1, dm.densityDpi);

            data.writeInterfaceToken(LMO_FREEFORM_DESCRIPTOR);
            data.writeString(packageName);
            data.writeString("notification-" + SystemClock.uptimeMillis());
            // LMOFreeformServiceManager.createWindow(PendingIntent, ...) uses -100
            // to select FreeformWindow's PendingIntent branch.
            data.writeInt(-100);
            data.writeInt(-1);
            data.writeInt(1);
            notification.contentIntent.writeToParcel(data, 0);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(densityDpi);

            if (!binder.transact(LMO_START_APP_TRANSACTION, data, reply, 0)) {
                Log.w(TAG, reason + ": LMO Binder transaction was rejected");
                return false;
            }
            reply.readException();
            Log.i(TAG, reason + ": launched notification PendingIntent via LMO Binder for "
                    + packageName + " (" + width + "x" + height + ", dpi=" + densityDpi + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": LMO PendingIntent Binder launch failed: "
                    + t.getMessage());
            return false;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static IBinder getLmoFreeformBinder() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            getService.setAccessible(true);
            Object binder = getService.invoke(null, LMO_FREEFORM_SERVICE);
            return binder instanceof IBinder ? (IBinder) binder : null;
        } catch (Throwable t) {
            Log.w(TAG, "LMO Freeform Binder lookup failed: " + t.getMessage());
            return null;
        }
    }

    private static boolean isFreeformSupported(Context ctx) {
        try {
            if (ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)) {
                Log.i(TAG, "Freeform supported by PackageManager feature");
                return true;
            }
        } catch (Throwable ignored) {}
        try {
            if (android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "enable_freeform_support", 0) != 0) {
                Log.i(TAG, "Freeform supported by enable_freeform_support");
                return true;
            }
        } catch (Throwable ignored) {
            // Evolution's current Launcher3 path also supports the newer DesktopMode /
            // Window Extensions implementation, which may not publish the legacy
            // FEATURE_FREEFORM_WINDOW_MANAGEMENT or enable_freeform_support setting.
        }
        if (isEvolutionDesktopModeAvailable(ctx)) return true;
        if (isLmoFreeformServiceAvailable()) return true;

        // Some Evolution-derived builds enable the WMShell extension without exposing
        // the legacy feature flag. This is only a positive hint; the actual launch is
        // still guarded by the try/catch below and will fall back if WindowManager rejects it.
        if (getSystemPropertyBoolean("persist.wm.extensions.enabled", false)) {
            Log.i(TAG, "Freeform supported by Window Extensions property");
            return true;
        }
        Log.w(TAG, "No Freeform/DesktopMode capability detected");
        return false;
    }

    private static boolean isLmoFreeformServiceAvailable() {
        try {
            if (getLmoFreeformBinder() != null) {
                Log.i(TAG, "Freeform supported by lmo_freeform Binder service");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "LMO Freeform service check failed: " + t.getMessage());
        }
        return false;
    }

    private static boolean isEvolutionDesktopModeAvailable(Context ctx) {
        String[] statusClasses = {
                "com.android.wm.shell.shared.desktopmode.DesktopModeStatus",
                "com.android.wm.shell.desktopmode.DesktopModeStatus"
        };
        for (String className : statusClasses) {
            try {
                ClassLoader loader = ctx.getClassLoader();
                Class<?> status = Class.forName(className, false, loader);
                Method method = status.getDeclaredMethod("canEnterDesktopMode", Context.class);
                method.setAccessible(true);
                Object result = method.invoke(null, ctx);
                if (Boolean.TRUE.equals(result)) {
                    Log.i(TAG, "Freeform supported by " + className + ".canEnterDesktopMode");
                    return true;
                }
                Log.i(TAG, className + ".canEnterDesktopMode=false");
            } catch (ClassNotFoundException ignored) {
                // The class is optional across Android/Evolution versions.
            } catch (Throwable t) {
                Log.w(TAG, "DesktopMode capability check failed for " + className + ": "
                        + t.getMessage());
            }
        }
        return false;
    }

    private static boolean getSystemPropertyBoolean(String key, boolean defaultValue) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method getBoolean = systemProperties.getDeclaredMethod(
                    "getBoolean", String.class, boolean.class);
            getBoolean.setAccessible(true);
            Object result = getBoolean.invoke(null, key, defaultValue);
            return Boolean.TRUE.equals(result);
        } catch (Throwable ignored) {
            return defaultValue;
        }
    }

    private static boolean configureFreeformOptions(ActivityOptions options, Context ctx,
            boolean setBounds) {
        try {
            Method windowingMode = findMethodSystemUi(options.getClass(),
                    "setLaunchWindowingMode", int.class);
            if (windowingMode == null) return false;
            windowingMode.invoke(options, WINDOWING_MODE_FREEFORM);
            if (setBounds) {
                Method launchBounds = findMethodSystemUi(options.getClass(),
                        "setLaunchBounds", Rect.class);
                if (launchBounds != null) launchBounds.invoke(options, defaultFreeformBounds(ctx));
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "configure freeform options: " + t.getMessage());
            return false;
        }
    }

    private static Rect defaultFreeformBounds(Context ctx) {
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int width = dm.widthPixels;
        int height = dm.heightPixels;
        int left = Math.max(0, width / 12);
        int top = Math.max(0, height / 5);
        int right = Math.min(width, width - left);
        int bottom = Math.min(height, top + Math.max(dp(ctx, 280), (int) (height * 0.58f)));
        return new Rect(left, top, right, bottom);
    }

    private static void showFreeformUnavailableToast(Context ctx) {
        showToast(ctx, getFreeformUnavailableLabel(ctx));
    }

    private static boolean launchNotificationFullscreen(Object entry, String reason) {
        return launchNotificationFullscreenSbn(getFieldSystemUi(entry, "mSbn"), reason);
    }

    private static boolean launchNotificationFullscreenSbn(Object sbn, String reason) {
        try {
            Notification notification = sbn != null
                    ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            if (notification == null || notification.contentIntent == null) return false;
            notification.contentIntent.send();
            Log.i(TAG, reason + ": launched notification PendingIntent fullscreen");
            return true;
        } catch (PendingIntent.CanceledException e) {
            Log.w(TAG, reason + ": fullscreen PendingIntent canceled");
        } catch (Throwable t) {
            Log.w(TAG, reason + ": fullscreen launch failed: " + t.getMessage());
        }
        return false;
    }

    private static int dp(Context ctx, long value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String getConfiguredOpenLabel(Context ctx) {
        return isFreeformOpenMode(ctx)
                ? getFreeformLabel(ctx) : getBubbleButtonLabel(ctx);
    }

    private static String getFreeformLabel(Context hostContext) {
        try {
            Context moduleContext = hostContext.createPackageContext(
                    "com.floatwindow.morebubblebutton", Context.CONTEXT_IGNORE_SECURITY);
            return moduleContext.getString(R.string.freeform_label);
        } catch (Throwable ignored) {
            return "Freeform";
        }
    }

    private static String getFreeformUnavailableLabel(Context hostContext) {
        try {
            Context moduleContext = hostContext.createPackageContext(
                    "com.floatwindow.morebubblebutton", Context.CONTEXT_IGNORE_SECURITY);
            return moduleContext.getString(R.string.freeform_unavailable);
        } catch (Throwable ignored) {
            return "Freeform is unavailable; using Bubble instead";
        }
    }

    private static final class PendingHeadsUpLaunch {
        final Context context;
        final Object row;
        final Object sbn;
        final Object entry;
        final String key;
        final String reason;
        boolean started;

        PendingHeadsUpLaunch(Context context, Object row, Object sbn, Object entry,
                String reason) {
            this.context = context;
            this.row = row;
            this.sbn = sbn;
            this.entry = entry;
            this.key = getNotificationKey(sbn);
            this.reason = reason;
        }
    }

    private static final class SwipeHandleView extends View {
        private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SwipeHandleView(Context context) {
            super(context);
            setWillNotDraw(false);
            setClickable(true);
            setFocusable(false);
            barPaint.setColor(0xB85F6368);
            setMinimumHeight(dp(context, 30));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            Context context = getContext();
            setMeasuredDimension(
                    dp(context, ModuleSettings.getSwipeHandleLength(context) + 24),
                    dp(context, 30));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Context context = getContext();
            float density = getResources().getDisplayMetrics().density;
            float barWidth = ModuleSettings.getSwipeHandleLength(context) * density;
            float barHeight = ModuleSettings.getSwipeHandleThickness(context) * density;
            float left = (getWidth() - barWidth) / 2f;
            float top = (getHeight() - barHeight) / 2f;
            canvas.drawRoundRect(left, top, left + barWidth, top + barHeight,
                    barHeight / 2f, barHeight / 2f, barPaint);
        }
    }

    private static boolean expandAppBubbleFromNotification(Object bubblesManager, Object entry, String reason) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            if (sbn == null) return false;
            String pkg = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            int userId = 0;
            try { userId = (int) sbn.getClass().getMethod("getUserId").invoke(sbn); } catch (Throwable ignored) {}
            if (userId < 0) userId = 0;
            UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
            Object bubblesImpl = getFieldSystemUi(bubblesManager, "mBubbles");
            Object controller = getFieldSystemUi(bubblesImpl, "this$0");
            if (controller == null || pkg == null) return false;
            Notification notif = (Notification) invokeSystemUi(sbn, "getNotification");
            Context ctx = (Context) getFieldSystemUi(controller, "mContext");
            Object notificationKey = getFieldSystemUi(entry, "key");
            logDetailedInfo(ctx, reason + ": Bubble route requested package=" + pkg
                    + " notificationKey=" + notificationKey
                    + " openMode=" + (isFreeformOpenMode(ctx) ? "freeform" : "bubble"));
            if (isFreeformOpenMode(ctx)) {
                boolean launched = launchNotificationInFreeform(ctx, entry, reason);
                if (launched) {
                    runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                    return true;
                }
                Log.i(TAG, reason + ": freeform unavailable, falling back to Bubble");
            }
            Intent targetIntent = getNotificationTargetIntent(notif, pkg);
            Intent launchIntent = ctx != null ? ctx.getPackageManager().getLaunchIntentForPackage(pkg) : null;
            boolean usingLaunchFallback = targetIntent == null;
            if (targetIntent == null) targetIntent = launchIntent;
            logDetailedInfo(ctx, reason + ": Bubble notification route pkg=" + pkg
                    + " notificationKey=" + notificationKey
                    + " creator=" + (notif != null && notif.contentIntent != null
                    ? safePendingIntentCreator(notif.contentIntent) : "none")
                    + " pendingIntentId=" + (notif != null && notif.contentIntent != null
                    ? System.identityHashCode(notif.contentIntent) : "none")
                    + " launchFallback=" + usingLaunchFallback
                    + " target=" + summarizeIntent(targetIntent));
            logIntentDetails(ctx, reason + ": Bubble target intent", targetIntent);
            if (targetIntent == null || launchIntent == null) {
                Log.w(TAG, reason + ": skip app bubble, no target intent for " + pkg);
                collapseShadeFromManager(bubblesManager);
                return launchNotificationFullscreen(entry, reason);
            }
            if (targetIntent.getPackage() == null) {
                targetIntent.setPackage(pkg);
            }
            targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            Object entryPoint = findEntryPoint(controller.getClass().getClassLoader(), "NOTIFICATION");
            final Intent finalIntent = targetIntent;
            final UserHandle finalUser = user;
            logDetailedInfo(ctx, reason + ": Bubble launch prepared package=" + pkg
                    + " notificationKey=" + notificationKey
                    + " launchFallback=" + usingLaunchFallback
                    + " entryPoint=" + entryPoint
                    + " intent=" + summarizeIntent(finalIntent));
            logIntentDetails(ctx, reason + ": Bubble submitted intent", finalIntent);
            Runnable work = () -> {
                try {
                    if (notif != null && notif.contentIntent != null
                            && launchNotificationIntoExistingAppBubbleTask(controller, pkg,
                            finalUser, notif.contentIntent, finalIntent, reason)) {
                        runOnSysuiMain(bubblesManager,
                                () -> dismissClickedNotificationIfAutoCancel(bubblesManager, entry));
                        runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                        return;
                    }
                    Class<?> bubbleBarLocCls = clOrNull(controller.getClass().getClassLoader(),
                            "com.android.wm.shell.shared.bubbles.BubbleBarLocation");
                    Method expand = findMethodSystemUi(controller.getClass(), "expandStackAndSelectBubble",
                            Intent.class, UserHandle.class, entryPoint != null ? entryPoint.getClass() : Object.class,
                            bubbleBarLocCls != null ? bubbleBarLocCls : Object.class);
                    if (expand == null) {
                        for (Method m : controller.getClass().getDeclaredMethods()) {
                            if (m.getName().equals("expandStackAndSelectBubble")
                                    && m.getParameterCount() == 4
                                    && m.getParameterTypes()[0] == Intent.class) {
                                m.setAccessible(true);
                                expand = m;
                                break;
                            }
                        }
                    }
                    if (expand != null) {
                        expand.invoke(controller, finalIntent, finalUser, entryPoint, null);
                        Log.i(TAG, reason + ": expanded app bubble through legacy API for "
                                + pkg + " target=" + summarizeIntent(finalIntent));
                        scheduleBubbleTaskSnapshots(ctx, pkg, reason, "legacy",
                                controller, null, finalIntent);
                        runOnSysuiMain(bubblesManager, () -> dismissClickedNotificationIfAutoCancel(bubblesManager, entry));
                        runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                    } else if (expandAppBubbleNewApi(controller, finalIntent, finalUser, entryPoint, pkg, reason)) {
                        // Android 17 (Cinnamon Bun) / EvolutionX: BubbleController no longer exposes
                        // expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation).
                        // Mirror the system's own showAppBubble() implementation: build a TYPE_APP
                        // Bubble and hand it to expandStackAndSelectAppBubble(Bubble, EntryPoint, ...).
                        runOnSysuiMain(bubblesManager, () -> dismissClickedNotificationIfAutoCancel(bubblesManager, entry));
                        runOnSysuiMain(bubblesManager, () -> collapseShadeFromManager(bubblesManager));
                    } else {
                        Log.w(TAG, reason + ": app bubble expand method not found");
                        launchNotificationFullscreen(entry, reason);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, reason + ": app bubble expand failed: " + t.getMessage());
                    launchNotificationFullscreen(entry, reason);
                }
            };
            Object executor = getFieldSystemUi(controller, "mMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) execute.invoke(executor, work); else work.run();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": app bubble schedule failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Deliver the notification's original PendingIntent into an already existing app Bubble's
     * TaskView. App bubbles are keyed by package, so submitting another TYPE_APP Bubble with a
     * different Intent can select the old Bubble without changing the TaskView it already owns.
     */
    private static boolean launchNotificationIntoExistingAppBubbleTask(Object controller,
            String packageName, UserHandle user, PendingIntent notificationPendingIntent,
            Intent submittedIntent, String reason) {
        Context context = (Context) getFieldSystemUi(controller, "mContext");
        if (context == null || packageName == null || user == null
                || notificationPendingIntent == null) return false;

        try {
            ClassLoader classLoader = controller.getClass().getClassLoader();
            Class<?> bubbleClass = classLoader.loadClass("com.android.wm.shell.bubbles.Bubble");
            Method getKey = findMethodSystemUi(bubbleClass, "getAppBubbleKeyForApp",
                    String.class, UserHandle.class);
            if (getKey == null) {
                logDetailedInfo(context, reason + ": existing app Bubble key API unavailable");
                return false;
            }
            String bubbleKey = (String) getKey.invoke(null, packageName, user);
            ControllerBubbleIntentResult lookup = readControllerAppBubbleIntent(controller, bubbleKey);
            Object bubble = lookup.bubble;
            if (bubble == null) {
                Object bubbleData = getFieldSystemUi(controller, "mBubbleData");
                Object bubbles = bubbleData != null
                        ? invokeSystemUi(bubbleData, "getBubbles") : null;
                if (bubbles instanceof java.util.Collection<?>) {
                    for (Object candidate : (java.util.Collection<?>) bubbles) {
                        Object isAppBubble = invokeSystemUi(candidate, "isAppBubble");
                        Object candidatePackage = invokeSystemUi(candidate, "getPackageName");
                        if (Boolean.TRUE.equals(isAppBubble)
                                && packageName.equals(candidatePackage)) {
                            bubble = candidate;
                            logDetailedInfo(context, reason
                                    + ": found existing app Bubble by package after key lookup miss");
                            break;
                        }
                    }
                }
            }
            if (bubble == null) {
                if (promoteOverflowAppBubbleWithIntent(controller, packageName, user,
                        bubbleKey, submittedIntent, reason)) {
                    scheduleBubbleTaskSnapshots(context, packageName, reason, "overflow-app-bubble",
                            controller, bubbleKey, submittedIntent);
                    return true;
                }
                logDetailedInfo(context, reason + ": existing app Bubble TaskView not found; lookup="
                        + lookup.status + " method=" + lookup.lookupMethod
                        + " selectedKey=" + lookup.selectedKey);
                return false;
            }

            Object appBubble = invokeSystemUi(bubble, "isAppBubble");
            if (appBubble instanceof Boolean && !((Boolean) appBubble)) {
                logDetailedInfo(context, reason + ": matching Bubble key is not an app Bubble");
                return false;
            }

            Object expandedView = invokeSystemUi(bubble, "getExpandedView");
            if (expandedView == null) expandedView = getFieldSystemUi(bubble, "mExpandedView");
            Object taskView = expandedView != null ? invokeSystemUi(expandedView, "getTaskView") : null;
            if (taskView == null && expandedView != null) {
                taskView = getFieldSystemUi(expandedView, "mTaskView");
            }
            if (taskView == null) {
                Object bubbleBarView = invokeSystemUi(bubble, "getBubbleBarExpandedView");
                if (bubbleBarView == null) {
                    bubbleBarView = getFieldSystemUi(bubble, "mBubbleBarExpandedView");
                }
                Object taskViewHelper = bubbleBarView != null
                        ? getFieldSystemUi(bubbleBarView, "mBubbleTaskViewHelper") : null;
                if (taskViewHelper != null) {
                    taskView = invokeSystemUi(taskViewHelper, "getTaskView");
                    if (taskView == null) taskView = getFieldSystemUi(taskViewHelper, "mTaskView");
                }
            }
            if (taskView == null) {
                logDetailedInfo(context, reason + ": existing app Bubble has no ready TaskView"
                        + " bubbleClass=" + bubble.getClass().getName()
                        + " expandedView=" + (expandedView != null));
                return false;
            }

            // Select the existing Bubble before sending the activity so the TaskView stays visible.
            Object bubbleData = getFieldSystemUi(controller, "mBubbleData");
            Method select = bubbleData != null
                    ? findMethodSystemUi(bubbleData.getClass(), "setSelectedBubbleAndExpandStack",
                    bubble.getClass()) : null;
            if (select != null) {
                select.invoke(bubbleData, bubble);
            } else {
                Method expandExisting = findMethodSystemUi(controller.getClass(),
                        "expandStackAndSelectBubble", bubble.getClass());
                if (expandExisting != null) expandExisting.invoke(controller, bubble);
            }

            ActivityOptions options = ActivityOptions.makeCustomAnimation(context,
                    0 /* enter animation */, 0 /* exit animation */);
            Method alwaysOnTop = findMethodSystemUi(options.getClass(), "setTaskAlwaysOnTop",
                    boolean.class);
            if (alwaysOnTop != null) alwaysOnTop.invoke(options, true);
            Method launchedFromBubble = findMethodSystemUi(options.getClass(), "setLaunchedFromBubble",
                    boolean.class);
            if (launchedFromBubble != null) launchedFromBubble.invoke(options, true);
            setPendingIntentBackgroundStartAllowed(options);

            Rect launchBounds = new Rect();
            Method getBounds = findMethodSystemUi(taskView.getClass(), "getBoundsOnScreen", Rect.class);
            if (getBounds != null) getBounds.invoke(taskView, launchBounds);
            if (launchBounds.isEmpty()) launchBounds = null;

            Method startActivity = null;
            for (Class<?> type = taskView.getClass(); type != null && startActivity == null;
                    type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("startActivity") && parameters.length == 4
                            && PendingIntent.class.isAssignableFrom(parameters[0])
                            && Intent.class.isAssignableFrom(parameters[1])
                            && ActivityOptions.class.isAssignableFrom(parameters[2])
                            && Rect.class.isAssignableFrom(parameters[3])) {
                        method.setAccessible(true);
                        startActivity = method;
                        break;
                    }
                }
            }
            if (startActivity == null) {
                logDetailedInfo(context, reason + ": existing Bubble TaskView startActivity API unavailable"
                        + " taskViewClass=" + taskView.getClass().getName());
                return false;
            }

            startActivity.invoke(taskView, notificationPendingIntent, null, options, launchBounds);
            logDetailedInfo(context, reason + ": delivered notification PendingIntent to existing Bubble TaskView"
                    + " package=" + packageName + " bubbleKey=" + bubbleKey
                    + " taskViewClass=" + taskView.getClass().getName()
                    + " intent=" + summarizeIntent(submittedIntent));
            Log.i(TAG, reason + ": routed notification into existing app Bubble TaskView for " + packageName);
            scheduleBubbleTaskSnapshots(context, packageName, reason, "existing-taskview",
                    controller, bubbleKey, submittedIntent);
            return true;
        } catch (Throwable t) {
            logDetailedInfo(context, reason + ": existing Bubble TaskView route failed="
                    + t.getClass().getSimpleName());
            Log.w(TAG, reason + ": existing app Bubble TaskView route failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * An app Bubble moved to overflow has no live TaskView. Update the retained Bubble Intent
     * before asking Shell to promote/reinflate it, otherwise Shell can restore the old route.
     */
    private static boolean promoteOverflowAppBubbleWithIntent(Object controller, String packageName,
            UserHandle user, String bubbleKey, Intent submittedIntent, String reason) {
        Context context = (Context) getFieldSystemUi(controller, "mContext");
        Object bubbleData = getFieldSystemUi(controller, "mBubbleData");
        if (bubbleData == null) return false;
        try {
            Method getOverflow = findMethodSystemUi(bubbleData.getClass(),
                    "getOverflowBubbleWithKey", String.class);
            if (getOverflow == null) {
                logDetailedInfo(context, reason + ": overflow app Bubble lookup API unavailable");
                return false;
            }
            Object overflowBubble = getOverflow.invoke(bubbleData, bubbleKey);
            if (overflowBubble == null) return false;

            Object isAppBubble = invokeSystemUi(overflowBubble, "isAppBubble");
            Object overflowPackage = invokeSystemUi(overflowBubble, "getPackageName");
            if (Boolean.FALSE.equals(isAppBubble)
                    || (overflowPackage != null && !packageName.equals(overflowPackage))) {
                logDetailedInfo(context, reason + ": overflow key matched a non-app Bubble");
                return false;
            }

            // This API handles overflow removal, Intent replacement, reinflation and expansion.
            Method showOrHide = findMethodSystemUi(controller.getClass(), "showOrHideAppBubble",
                    Intent.class, UserHandle.class, android.graphics.drawable.Icon.class);
            if (showOrHide != null) {
                android.graphics.drawable.Icon icon = buildAppBubbleIcon(
                        controller, submittedIntent, packageName);
                showOrHide.invoke(controller, new Intent(submittedIntent), user, icon);
                logDetailedInfo(context, reason + ": updated and promoted overflow app Bubble through"
                        + " showOrHideAppBubble package=" + packageName
                        + " intent=" + summarizeIntent(submittedIntent));
                Log.i(TAG, reason + ": updated overflow app Bubble route for " + packageName);
                return true;
            }

            Method setIntent = findMethodSystemUi(overflowBubble.getClass(),
                    "setAppBubbleIntent", Intent.class);
            String intentUpdatePath = null;
            if (setIntent != null) {
                setIntent.invoke(overflowBubble, new Intent(submittedIntent));
                intentUpdatePath = "method setAppBubbleIntent";
            }
            if (setIntent == null) {
                // Lunaris 16.2 stores TYPE_APP's launch Intent in Bubble.mIntent and exposes
                // this package-private setter. Its overflow promotion path reuses the retained
                // Bubble, so update the launch Intent before asking Shell to reinflate it.
                setIntent = findMethodSystemUi(overflowBubble.getClass(), "setIntent", Intent.class);
                if (setIntent != null) {
                    setIntent.invoke(overflowBubble, new Intent(submittedIntent));
                    intentUpdatePath = "method setIntent";
                }
            }
            if (intentUpdatePath == null) {
                // Some shipped SystemUI builds keep Bubble.mIntent but omit both setters.
                // The diagnostic path confirms this member is an Intent on that runtime.
                java.lang.reflect.Field appIntent = findFieldSystemUi(
                        overflowBubble.getClass(), "mIntent");
                if (appIntent != null && Intent.class.isAssignableFrom(appIntent.getType())) {
                    appIntent.setAccessible(true);
                    appIntent.set(overflowBubble, new Intent(submittedIntent));
                    intentUpdatePath = "field mIntent";
                }
            }
            if (intentUpdatePath == null) {
                logDetailedInfo(context, reason
                        + ": overflow Bubble Intent update API unavailable (setAppBubbleIntent/setIntent/mIntent)");
                if (context != null && isDebugLoggingEnabled(context)) {
                    logDetailedInfo(context, reason + ": overflow Bubble class="
                            + describeBubbleIntentApi(overflowBubble));
                }
                return false;
            }
            logDetailedInfo(context, reason + ": updated retained overflow Bubble Intent via "
                    + intentUpdatePath
                    + " before promotion package=" + packageName);

            Method promote = findMethodSystemUi(controller.getClass(),
                    "expandStackAndSelectBubble", overflowBubble.getClass());
            if (promote != null) {
                promote.invoke(controller, overflowBubble);
            } else {
                Object entryPoint = findEntryPoint(controller.getClass().getClassLoader(), "NOTIFICATION");
                promote = null;
                for (Method method : controller.getClass().getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    if (method.getName().equals("expandStackAndSelectAppBubble")
                            && (parameters.length == 2 || parameters.length == 3)
                            && parameters[0].isAssignableFrom(overflowBubble.getClass())) {
                        method.setAccessible(true);
                        promote = method;
                        break;
                    }
                }
                if (promote == null) {
                    logDetailedInfo(context, reason + ": overflow Bubble promotion API unavailable");
                    return false;
                }
                if (promote.getParameterCount() == 2) {
                    promote.invoke(controller, overflowBubble, entryPoint);
                } else {
                    promote.invoke(controller, overflowBubble, entryPoint, null);
                }
            }
            logDetailedInfo(context, reason + ": promoted overflow Bubble after Intent update"
                    + " package=" + packageName);
            Log.i(TAG, reason + ": promoted updated overflow app Bubble for " + packageName);
            return true;
        } catch (Throwable t) {
            logDetailedInfo(context, reason + ": overflow app Bubble update failed="
                    + t.getClass().getSimpleName());
            Log.w(TAG, reason + ": overflow app Bubble update failed: " + t.getMessage());
            return false;
        }
    }

    /** Describe only member names and types; never include Bubble/Intent values. */
    private static String describeBubbleIntentApi(Object bubble) {
        if (bubble == null) return "null";
        StringBuilder result = new StringBuilder(bubble.getClass().getName());
        int reported = 0;
        for (Class<?> type = bubble.getClass(); type != null && reported < 24;
                type = type.getSuperclass()) {
            try {
                for (Method method : type.getDeclaredMethods()) {
                    String name = method.getName().toLowerCase(Locale.ROOT);
                    if (!name.contains("intent") && !name.contains("appbubble")) continue;
                    if (reported++ >= 24) break;
                    result.append("; method=").append(type.getSimpleName()).append('#')
                            .append(method.getName()).append('(');
                    Class<?>[] parameters = method.getParameterTypes();
                    for (int i = 0; i < parameters.length; i++) {
                        if (i > 0) result.append(',');
                        result.append(parameters[i].getSimpleName());
                    }
                    result.append(")->").append(method.getReturnType().getSimpleName());
                }
                for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                    String name = field.getName().toLowerCase(Locale.ROOT);
                    if (!name.contains("intent") && !name.contains("appbubble")) continue;
                    if (reported++ >= 24) break;
                    result.append("; field=").append(type.getSimpleName()).append('#')
                            .append(field.getName()).append(':')
                            .append(field.getType().getSimpleName());
                }
            } catch (Throwable ignored) {}
        }
        return result.toString();
    }

    /**
     * Temporary diagnostic control path: launch the Intent extracted from the
     * notification's content PendingIntent as a regular fullscreen activity.
     * This deliberately bypasses both the module Bubble and Freeform launchers.
     */
    private static boolean launchExtractedNotificationIntentFullscreen(Context context,
            Object entry, String reason) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            if (sbn == null) {
                Log.w(TAG, reason + ": notification entry has no StatusBarNotification");
                return false;
            }
            String packageName = (String) sbn.getClass().getMethod("getPackageName").invoke(sbn);
            Notification notification = (Notification) invokeSystemUi(sbn, "getNotification");
            Intent intent = getNotificationTargetIntent(notification, packageName);
            if (intent == null) {
                Log.w(TAG, reason + ": extracted notification Intent is unavailable");
                return false;
            }
            if (intent.getPackage() == null && packageName != null) intent.setPackage(packageName);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            logDetailedInfo(context, reason + ": launching extracted notification Intent "
                    + summarizeIntent(intent));
            try {
                int userId = 0;
                try { userId = (int) sbn.getClass().getMethod("getUserId").invoke(sbn); }
                catch (Throwable ignored) {}
                if (userId >= 0) {
                    UserHandle user = (UserHandle) UserHandle.class
                            .getMethod("of", int.class).invoke(null, userId);
                    Method startAsUser = findMethodSystemUi(context.getClass(),
                            "startActivityAsUser", Intent.class, UserHandle.class);
                    if (startAsUser != null) {
                        try {
                            startAsUser.invoke(context, intent, user);
                            Log.i(TAG, reason + ": started extracted Intent fullscreen as user " + userId
                                    + " target=" + summarizeIntent(intent));
                            return true;
                        } catch (Throwable userLaunchError) {
                            Log.w(TAG, reason + ": startActivityAsUser failed; trying current-user start",
                                    userLaunchError);
                        }
                    }
                }
                context.startActivity(intent);
                Log.i(TAG, reason + ": started extracted Intent fullscreen target="
                        + summarizeIntent(intent));
                return true;
            } catch (Throwable launchError) {
                Log.w(TAG, reason + ": extracted Intent fullscreen launch failed", launchError);
                return false;
            }
        } catch (Throwable t) {
            Log.w(TAG, reason + ": could not prepare extracted Intent fullscreen launch", t);
            return false;
        }
    }

    /**
     * Android 17 / EvolutionX replacement for the removed
     * expandStackAndSelectBubble(Intent, UserHandle, EntryPoint, BubbleBarLocation).
     * Constructs a TYPE_APP Bubble exactly like the system's own IBubbles.showAppBubble() handler
     * (BubbleController$IBubblesImpl) and invokes:
     *   BubbleController.expandStackAndSelectAppBubble(Bubble, EntryPoint, UpdateLocationRequest)
     */
    private static boolean expandAppBubbleNewApi(Object controller, Intent intent, UserHandle user,
            Object entryPoint, String pkg, String reason) {
        try {
            Log.i(TAG, reason + ": using TYPE_APP bubble API for " + pkg
                    + " target=" + summarizeIntent(intent));
            ClassLoader cl = controller.getClass().getClassLoader();
            Class<?> bubbleCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble");
            Class<?> bubbleTypeCls = cl.loadClass("com.android.wm.shell.bubbles.Bubble$BubbleType");
            Object bubbleType = Enum.valueOf((Class<? extends Enum>) bubbleTypeCls, "TYPE_APP");
            String key = (String) bubbleCls.getMethod("getAppBubbleKeyForApp", String.class, UserHandle.class)
                    .invoke(null, pkg, user);
            Context context = (Context) getFieldSystemUi(controller, "mContext");
            logDetailedInfo(context, reason + ": TYPE_APP identity package=" + pkg
                    + " bubbleKey=" + key + " user=" + user);
            android.graphics.drawable.Icon icon = buildAppBubbleIcon(controller, intent, pkg);
            Object bubble = null;
            // Evolution's Android 17 build has a five-argument constructor,
            // while Lunaris v3.12 adds the main/background Executor pair.
            for (java.lang.reflect.Constructor<?> constructor
                    : bubbleCls.getDeclaredConstructors()) {
                Class<?>[] types = constructor.getParameterTypes();
                if (types.length == 5
                        && types[0] == Intent.class
                        && types[1] == UserHandle.class
                        && types[2] == android.graphics.drawable.Icon.class
                        && types[3] == bubbleTypeCls
                        && types[4] == String.class) {
                    constructor.setAccessible(true);
                    bubble = constructor.newInstance(intent, user, icon, bubbleType, key);
                    break;
                }
                if (types.length == 7
                        && types[0] == Intent.class
                        && types[1] == UserHandle.class
                        && types[2] == android.graphics.drawable.Icon.class
                        && types[3] == bubbleTypeCls
                        && types[4] == String.class
                        && java.util.concurrent.Executor.class.isAssignableFrom(types[5])
                        && java.util.concurrent.Executor.class.isAssignableFrom(types[6])) {
                    Object mainExecutor = getFieldSystemUi(controller, "mMainExecutor");
                    Object backgroundExecutor = getFieldSystemUi(controller, "mBackgroundExecutor");
                    java.util.concurrent.Executor fallbackExecutor = command -> command.run();
                    if (!(backgroundExecutor instanceof java.util.concurrent.Executor)) {
                        backgroundExecutor = fallbackExecutor;
                    }
                    if (!(mainExecutor instanceof java.util.concurrent.Executor)) {
                        mainExecutor = fallbackExecutor;
                    }
                    constructor.setAccessible(true);
                    bubble = constructor.newInstance(intent, user, icon, bubbleType, key,
                            mainExecutor, backgroundExecutor);
                    break;
                }
            }
            if (bubble == null) {
                Log.w(TAG, reason + ": compatible app Bubble constructor not found");
                return false;
            }

            Method expandApp = null;
            for (Method m : controller.getClass().getDeclaredMethods()) {
                if (!m.getName().equals("expandStackAndSelectAppBubble")) continue;
                Class<?>[] types = m.getParameterTypes();
                if ((types.length == 2 || types.length == 3) && types[0] == bubbleCls) {
                    m.setAccessible(true);
                    expandApp = m;
                    break;
                }
            }
            if (expandApp == null) {
                Log.w(TAG, reason + ": app bubble expand method not found (new api)");
                return false;
            }
            if (expandApp.getParameterCount() == 2) {
                expandApp.invoke(controller, bubble, entryPoint);
            } else {
                expandApp.invoke(controller, bubble, entryPoint, null);
            }
            logDetailedInfo(context, reason + ": submitted TYPE_APP Bubble"
                    + " package=" + pkg + " entryPoint=" + entryPoint
                    + " intent=" + summarizeIntent(intent));
            Log.i(TAG, reason + ": expanded app bubble via expandStackAndSelectAppBubble for " + pkg);
            scheduleBubbleTaskSnapshots(context, pkg, reason, "TYPE_APP",
                    controller, key, intent);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, reason + ": app bubble new api failed: " + t.getMessage());
            return false;
        }
    }

    private static android.graphics.drawable.Icon buildAppBubbleIcon(Object controller, Intent intent, String pkg) {
        try {
            Context ctx = (Context) getFieldSystemUi(controller, "mContext");
            if (ctx == null) return null;
            // Prefer the system's own icon provider (same as IBubbles.showAppBubble()).
            Object bubbleData = getFieldSystemUi(controller, "mBubbleData");
            Object provider = bubbleData != null ? getFieldSystemUi(bubbleData, "mAppInfoProvider") : null;
            if (provider != null) {
                Method getIcon = findMethodSystemUi(provider.getClass(), "getActivityInfoIcon",
                        android.content.pm.PackageManager.class, Intent.class);
                if (getIcon != null) {
                    Object icon = getIcon.invoke(provider, ctx.getPackageManager(), intent);
                    if (icon instanceof android.graphics.drawable.Icon) return (android.graphics.drawable.Icon) icon;
                }
            }
            // Fallback: application icon rasterized to a bitmap.
            android.graphics.drawable.Drawable d = ctx.getPackageManager().getApplicationIcon(pkg);
            if (d instanceof android.graphics.drawable.BitmapDrawable) {
                return android.graphics.drawable.Icon.createWithBitmap(
                        ((android.graphics.drawable.BitmapDrawable) d).getBitmap());
            }
            int size = Math.max(48, (int) (48 * ctx.getResources().getDisplayMetrics().density));
            android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                    size, size, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(bmp);
            d.setBounds(0, 0, size, size);
            d.draw(canvas);
            return android.graphics.drawable.Icon.createWithBitmap(bmp);
        } catch (Throwable t) {
            Log.w(TAG, "buildAppBubbleIcon: " + t.getMessage());
            return null;
        }
    }

    private static Intent getNotificationTargetIntent(Notification notif, String pkg) {
        try {
            PendingIntent pi = notif != null ? notif.contentIntent : null;
            Intent intent = null;
            if (pi != null) {
                Method getIntent = findMethodSystemUi(pi.getClass(), "getIntent");
                if (getIntent != null) intent = (Intent) getIntent.invoke(pi);
            }
            if (intent == null) {
                Log.i(TAG, "Notification target Intent unavailable; contentIntent="
                        + (pi != null) + " creator=" + safePendingIntentCreator(pi));
                return null;
            }
            Intent copy = new Intent(intent);
            if (pkg != null && copy.getPackage() == null) {
                copy.setPackage(pkg);
            }
            Log.i(TAG, "Notification target Intent extracted: creator="
                    + safePendingIntentCreator(pi) + " target=" + summarizeIntent(copy));
            return copy;
        } catch (Throwable t) {
            Log.w(TAG, "notification target intent: " + t.getMessage());
            return null;
        }
    }

    private static boolean isDebugLoggingEnabled(Context context) {
        try {
            return context != null && ModuleSettings.isDebugLoggingEnabled(context);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Emit detailed diagnostics using the same host context as the setting check. */
    private static void logDetailedInfo(Context context, String message) {
        if (isDebugLoggingEnabled(context)) {
            android.util.Log.i(TAG, message);
        }
    }

    /** Schedule task snapshots only when detailed logging is enabled. */
    private static void scheduleBubbleTaskSnapshots(Context context, String packageName,
            String reason, String api, Object controller, String bubbleKey, Intent submittedIntent) {
        if (!isDebugLoggingEnabled(context) || packageName == null) return;
        Handler handler = new Handler(Looper.getMainLooper());
        long[] delaysMs = {300L, 1200L, 2800L};
        for (long delayMs : delaysMs) {
            handler.postDelayed(() -> {
                if (!isDebugLoggingEnabled(context)) return;
                try {
                    if (controller != null && bubbleKey != null) {
                        ControllerBubbleIntentResult lookup = readControllerAppBubbleIntent(controller, bubbleKey);
                        logDetailedInfo(context, reason + ": Bubble Controller lookup api=" + api
                                + " elapsedMs=" + delayMs + " bubbleKey=" + bubbleKey
                                + " result=" + lookup.status
                                + " bubbleData=" + lookup.bubbleDataClass
                                + " lookupMethod=" + lookup.lookupMethod
                                + " stackCount=" + lookup.stackBubbleCount
                                + " selectedKey=" + lookup.selectedKey
                                + " matchedKey=" + lookup.matchedKey
                                + " bubbleClass=" + lookup.bubbleClass
                                + " intentSource=" + lookup.intentGetter
                                + (delayMs == 300L ? " intentMembers=" + lookup.intentMembers : ""));
                        if (lookup.intent == null) {
                            // Keep the precise failure reason above so a missing snapshot cannot
                            // be mistaken for proof that the app Bubble/task does not exist.
                        } else {
                            logDetailedInfo(context, reason + ": Bubble Controller intent snapshot api=" + api
                                    + " elapsedMs=" + delayMs + " bubbleKey=" + bubbleKey
                                    + " adopted=" + summarizeIntent(lookup.intent));
                            logIntentDetails(context, reason + ": Bubble Controller adopted intent", lookup.intent);
                            logIntentComparison(context, reason + ": submitted vs Bubble Controller", submittedIntent,
                                    lookup.intent);
                        }
                    }
                    ActivityManager activityManager = (ActivityManager)
                            context.getSystemService(Context.ACTIVITY_SERVICE);
                    List<ActivityManager.RunningTaskInfo> tasks = activityManager != null
                            ? activityManager.getRunningTasks(50) : null;
                    if (tasks == null) {
                        logDetailedInfo(context, reason + ": Bubble task snapshot api=" + api
                                + " elapsedMs=" + delayMs
                                + " result=task-query-unavailable");
                        return;
                    }
                    int matches = 0;
                    for (ActivityManager.RunningTaskInfo task : tasks) {
                        ComponentName top = task.topActivity;
                        ComponentName base = task.baseActivity;
                        if (!packageName.equals(top != null ? top.getPackageName() : null)
                                && !packageName.equals(base != null ? base.getPackageName() : null)) {
                            continue;
                        }
                        matches++;
                        int windowingMode = 0;
                        int displayId = -1;
                        try {
                            Object configuration = getFieldSystemUi(task, "configuration");
                            Object windowConfiguration = configuration != null
                                    ? invokeSystemUi(configuration, "getWindowConfiguration") : null;
                            Object mode = windowConfiguration != null
                                    ? invokeSystemUi(windowConfiguration, "getWindowingMode") : null;
                            if (mode instanceof Number) windowingMode = ((Number) mode).intValue();
                        } catch (Throwable ignored) {}
                        try {
                            Object id = getFieldSystemUi(task, "displayId");
                            if (id instanceof Number) displayId = ((Number) id).intValue();
                        } catch (Throwable ignored) {}
                        logDetailedInfo(context, reason + ": Bubble task snapshot api=" + api
                                + " elapsedMs=" + delayMs + " taskId=" + task.taskId
                                + " displayId=" + displayId
                                + " windowingMode=" + windowingMode
                                + " top=" + flattenComponent(top)
                                + " base=" + flattenComponent(base)
                                + " baseIntent=" + summarizeIntent(task.baseIntent));
                        if (task.baseIntent != null && submittedIntent != null) {
                            logIntentDetails(context, reason + ": task baseIntent", task.baseIntent);
                            logIntentComparison(context, reason + ": submitted vs task baseIntent",
                                    submittedIntent, task.baseIntent);
                        }
                    }
                    if (matches == 0) {
                        logDetailedInfo(context, reason + ": Bubble task snapshot api=" + api
                                + " elapsedMs=" + delayMs + " package=" + packageName
                                + " result=no matching running task");
                    }
                } catch (Throwable t) {
                    logDetailedInfo(context, reason + ": Bubble task snapshot api=" + api
                            + " elapsedMs=" + delayMs + " failed=" + t.getClass().getSimpleName()
                            + ": " + t.getMessage());
                }
            }, delayMs);
        }
    }

    private static final class ControllerBubbleIntentResult {
        Object bubble;
        Intent intent;
        String status = "unknown";
        String bubbleDataClass = "none";
        String lookupMethod = "none";
        String stackBubbleCount = "unavailable";
        String selectedKey = "none";
        String matchedKey = "none";
        String bubbleClass = "none";
        String intentGetter = "none";
        String intentMembers = "none";
    }

    private static ControllerBubbleIntentResult readControllerAppBubbleIntent(
            Object controller, String bubbleKey) {
        ControllerBubbleIntentResult result = new ControllerBubbleIntentResult();
        try {
            Object bubbleData = getFieldSystemUi(controller, "mBubbleData");
            if (bubbleData == null) {
                result.status = findFieldSystemUi(controller.getClass(), "mBubbleData") == null
                        ? "bubble-data-field-missing" : "bubble-data-null";
                return result;
            }
            result.bubbleDataClass = bubbleData.getClass().getName();
            Object bubble = null;
            Method byKey = findMethodSystemUi(bubbleData.getClass(), "getBubbleInStackWithKey", String.class);
            if (byKey != null) {
                result.lookupMethod = "getBubbleInStackWithKey";
                bubble = byKey.invoke(bubbleData, bubbleKey);
            }
            Object bubbles = null;
            Method getBubbles = findMethodSystemUi(bubbleData.getClass(), "getBubbles");
            if (getBubbles != null) {
                bubbles = getBubbles.invoke(bubbleData);
                if (bubbles instanceof java.util.Collection<?>) {
                    result.stackBubbleCount = Integer.toString(((java.util.Collection<?>) bubbles).size());
                    if (bubble == null) {
                        for (Object candidate : (java.util.Collection<?>) bubbles) {
                            String candidateKey = String.valueOf(getBubbleKey(candidate));
                            if (bubbleKey.equals(candidateKey)) {
                                bubble = candidate;
                                result.lookupMethod = "getBubbles-match";
                                break;
                            }
                        }
                    }
                }
            }
            if (bubble == null) {
                Method selected = findMethodSystemUi(bubbleData.getClass(), "getSelectedBubble");
                if (selected != null) {
                    Object candidate = selected.invoke(bubbleData);
                    result.selectedKey = candidate != null ? String.valueOf(getBubbleKey(candidate)) : "none";
                    if (candidate != null && bubbleKey.equals(getBubbleKey(candidate))) {
                        bubble = candidate;
                        result.lookupMethod = "getSelectedBubble-match";
                    }
                }
            }
            if (bubble == null) {
                result.status = byKey == null && getBubbles == null
                        ? "bubble-lookup-methods-missing" : "bubble-key-not-found";
                return result;
            }
            result.matchedKey = String.valueOf(getBubbleKey(bubble));
            result.bubble = bubble;
            result.bubbleClass = bubble.getClass().getName();
            inspectBubbleIntentAccessors(bubble, result);
            result.status = result.intent == null
                    ? ("none".equals(result.intentGetter) ? "app-intent-accessor-missing" : "app-intent-null")
                    : "ok";
            return result;
        } catch (Throwable t) {
            result.status = "reflection-failed:" + t.getClass().getSimpleName();
            return result;
        }
    }

    private static Object getBubbleKey(Object bubble) {
        Object key = invokeSystemUi(bubble, "getKey");
        return key != null ? key : getFieldSystemUi(bubble, "mKey");
    }

    private static void inspectBubbleIntentAccessors(Object bubble, ControllerBubbleIntentResult result) {
        java.util.ArrayList<String> members = new java.util.ArrayList<>();
        Intent fallbackIntent = null;
        String fallbackSource = "none";
        for (Class<?> type = bubble.getClass(); type != null; type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!Intent.class.isAssignableFrom(field.getType())
                        && !PendingIntent.class.isAssignableFrom(field.getType())) continue;
                members.add("field:" + field.getName() + ":" + field.getType().getSimpleName());
                try {
                    field.setAccessible(true);
                    Object value = field.get(bubble);
                    Intent candidate = value instanceof Intent ? (Intent) value
                            : value instanceof PendingIntent ? getPendingIntentIntent((PendingIntent) value) : null;
                    if (candidate == null) continue;
                    boolean preferred = field.getName().toLowerCase(Locale.ROOT).contains("app");
                    if (preferred || fallbackIntent == null) {
                        fallbackIntent = candidate;
                        fallbackSource = "field:" + field.getName();
                    }
                } catch (Throwable t) {
                    members.add("field-read-failed:" + field.getName() + ":" + t.getClass().getSimpleName());
                }
            }
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterCount() != 0
                        || (!Intent.class.isAssignableFrom(method.getReturnType())
                        && !PendingIntent.class.isAssignableFrom(method.getReturnType()))
                        || !method.getName().toLowerCase(Locale.ROOT).contains("intent")) continue;
                members.add("method:" + method.getName() + "():" + method.getReturnType().getSimpleName());
                try {
                    method.setAccessible(true);
                    Object value = method.invoke(bubble);
                    Intent candidate = value instanceof Intent ? (Intent) value
                            : value instanceof PendingIntent ? getPendingIntentIntent((PendingIntent) value) : null;
                    if (candidate == null) continue;
                    boolean preferred = method.getName().toLowerCase(Locale.ROOT).contains("app");
                    if (preferred || fallbackIntent == null) {
                        fallbackIntent = candidate;
                        fallbackSource = "method:" + method.getName();
                    }
                } catch (Throwable t) {
                    members.add("method-call-failed:" + method.getName() + ":" + t.getClass().getSimpleName());
                }
            }
        }
        result.intentMembers = members.isEmpty() ? "none" : String.join(",", members);
        result.intent = fallbackIntent;
        result.intentGetter = fallbackSource;
    }

    private static Intent getPendingIntentIntent(PendingIntent pendingIntent) {
        if (pendingIntent == null) return null;
        try {
            Method getter = findMethodSystemUi(pendingIntent.getClass(), "getIntent");
            Object value = getter != null ? getter.invoke(pendingIntent) : null;
            return value instanceof Intent ? (Intent) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logIntentComparison(Context context, String label, Intent submitted, Intent observed) {
        if (!isDebugLoggingEnabled(context) || submitted == null || observed == null) return;
        logDetailedInfo(context, label + " filterEquals=" + submitted.filterEquals(observed)
                + " identifierEqual=" + java.util.Objects.equals(
                        submitted.getIdentifier(), observed.getIdentifier())
                + " submitted=" + summarizeIntent(submitted)
                + " observed=" + summarizeIntent(observed));
    }

    private static String flattenComponent(ComponentName component) {
        return component != null ? component.flattenToShortString() : "none";
    }

    private static String safePendingIntentCreator(PendingIntent pendingIntent) {
        if (pendingIntent == null) return "none";
        try {
            return pendingIntent.getCreatorPackage();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    /** Log complete launch routing data only when detailed debug logging is enabled. */
    private static void logIntentDetails(Context context, String label, Intent intent) {
        if (!isDebugLoggingEnabled(context) || intent == null) return;
        try {
            logDetailedInfo(context, label + " data=" + limitDebugValue(intent.getDataString()));
            logDetailedInfo(context, label + " identifier=" + intent.getIdentifier());
            android.os.Bundle extras = intent.getExtras();
            if (extras == null || extras.isEmpty()) {
                logDetailedInfo(context, label + " extras=empty");
                return;
            }
            for (String name : new java.util.TreeSet<>(extras.keySet())) {
                Object value;
                try {
                    value = extras.get(name);
                } catch (Throwable t) {
                    logDetailedInfo(context, label + " extra[" + name + "]=<read-failed:" + t.getClass().getSimpleName() + ">");
                    continue;
                }
                logDetailedInfo(context, label + " extra[" + name + "] type="
                        + (value == null ? "null" : value.getClass().getName())
                        + " value=" + describeDebugValue(value));
                if (value instanceof android.os.Bundle) {
                    logBundleDetails(context, label + " extra[" + name + "]", (android.os.Bundle) value, 1);
                }
            }
        } catch (Throwable t) {
            logDetailedInfo(context, label + " details-failed=" + t.getClass().getSimpleName());
        }
    }

    private static void logBundleDetails(Context context, String label,
            android.os.Bundle bundle, int depth) {
        if (depth > 3) {
            logDetailedInfo(context, label + " nested bundle depth limit reached");
            return;
        }
        for (String name : new java.util.TreeSet<>(bundle.keySet())) {
            Object value;
            try {
                value = bundle.get(name);
            } catch (Throwable t) {
                logDetailedInfo(context, label + "[" + name + "]=<read-failed:" + t.getClass().getSimpleName() + ">");
                continue;
            }
            logDetailedInfo(context, label + "[" + name + "] type="
                    + (value == null ? "null" : value.getClass().getName())
                    + " value=" + describeDebugValue(value));
            if (value instanceof android.os.Bundle) {
                logBundleDetails(context, label + "[" + name + "]", (android.os.Bundle) value, depth + 1);
            }
        }
    }

    private static String describeDebugValue(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[]) {
            byte[] bytes = (byte[]) value;
            StringBuilder out = new StringBuilder("bytes[").append(bytes.length).append("] hex=");
            int limit = Math.min(bytes.length, 512);
            for (int i = 0; i < limit; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 0xff));
            if (bytes.length > limit) out.append("...(truncated)");
            return out.toString();
        }
        if (value instanceof android.os.Bundle) {
            android.os.Bundle bundle = (android.os.Bundle) value;
            return "Bundle(keys=" + new java.util.TreeSet<>(bundle.keySet()) + ")";
        }
        if (value instanceof int[] || value instanceof long[] || value instanceof boolean[]
                || value instanceof String[] || value instanceof Object[]) {
            if (value instanceof int[]) return java.util.Arrays.toString((int[]) value);
            if (value instanceof long[]) return java.util.Arrays.toString((long[]) value);
            if (value instanceof boolean[]) return java.util.Arrays.toString((boolean[]) value);
            return java.util.Arrays.deepToString((Object[]) value);
        }
        return limitDebugValue(String.valueOf(value));
    }

    private static String limitDebugValue(String value) {
        if (value == null) return "null";
        final int max = 1800;
        return value.length() <= max ? value : value.substring(0, max) + "...(truncated,len=" + value.length() + ")";
    }

    /** Summarize routing metadata for normal non-detailed log lines. */
    private static String summarizeIntent(Intent intent) {
        if (intent == null) return "null";
        try {
            String component = intent.getComponent() != null
                    ? intent.getComponent().flattenToShortString() : "implicit";
            Uri data = intent.getData();
            String dataOrigin = data == null ? "none"
                    : String.valueOf(data.getScheme()) + "://" + String.valueOf(data.getHost());
            Set<String> categories = intent.getCategories();
            int extraCount = 0;
            try {
                android.os.Bundle extras = intent.getExtras();
                if (extras != null) {
                    extraCount = extras.size();
                }
            } catch (Throwable ignored) {
                extraCount = -1;
            }
            return "pkg=" + intent.getPackage()
                    + " cmp=" + component
                    + " action=" + intent.getAction()
                    + " data=" + dataOrigin
                    + " categories=" + (categories == null ? "[]" : categories)
                    + " flags=0x" + Integer.toHexString(intent.getFlags())
                    + " extraCount=" + (extraCount >= 0 ? extraCount : "unavailable");
        } catch (Throwable t) {
            return "summary-unavailable:" + t.getClass().getSimpleName();
        }
    }

    private static String getNotificationPackage(Object sbn) {
        try {
            Object packageName = sbn != null
                    ? sbn.getClass().getMethod("getPackageName").invoke(sbn) : null;
            return packageName instanceof String ? (String) packageName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void dismissClickedNotificationIfAutoCancel(Object bubblesManager, Object entry) {
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            Notification notif = sbn != null ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            if (notif == null || (notif.flags & Notification.FLAG_AUTO_CANCEL) == 0) return;
            Object visibilityProvider = getFieldSystemUi(bubblesManager, "mVisibilityProvider");
            Object visibility = null;
            if (visibilityProvider != null) {
                Method obtain = findCompatibleMethodByName(visibilityProvider.getClass(), "obtain", entry.getClass());
                if (obtain == null) obtain = findCompatibleMethodByName(visibilityProvider.getClass(), "obtain", String.class);
                if (obtain != null) {
                    Class<?> argType = obtain.getParameterTypes()[0];
                    Object arg = argType == String.class ? getFieldSystemUi(entry, "key") : entry;
                    visibility = obtain.invoke(visibilityProvider, arg);
                }
            }
            ClassLoader cl = bubblesManager.getClass().getClassLoader();
            Class<?> statsCls = cl.loadClass("com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats");
            Object stats = null;
            for (java.lang.reflect.Constructor<?> c : statsCls.getDeclaredConstructors()) {
                if (c.getParameterCount() == 2) {
                    c.setAccessible(true);
                    stats = c.newInstance(1, visibility);
                    break;
                }
            }
            if (stats == null) return;
            Object callbacks = getFieldSystemUi(bubblesManager, "mCallbacks");
            if (callbacks instanceof java.util.List) {
                for (Object cb : (java.util.List<?>) callbacks) {
                    Method remove = findMethodByNameAndCount(cb.getClass(), "removeNotification", 2);
                    if (remove != null) remove.invoke(cb, entry, stats);
                }
                Log.i(TAG, "dismissed clicked auto-cancel notification");
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            Log.w(TAG, "dismiss clicked notification: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
        }
    }

    private static void runOnSysuiMain(Object bubblesManager, Runnable runnable) {
        try {
            Object executor = getFieldSystemUi(bubblesManager, "mSysuiMainExecutor");
            Method execute = executor != null ? findMethodSystemUi(executor.getClass(), "execute", Runnable.class) : null;
            if (execute != null) {
                execute.invoke(executor, runnable);
            } else {
                new android.os.Handler(Looper.getMainLooper()).post(runnable);
            }
        } catch (Throwable t) {
            try { runnable.run(); } catch (Throwable ignored) {}
        }
    }

    private static void collapseShadeFromManager(Object bubblesManager) {
        try {
            Object shadeController = getFieldSystemUi(bubblesManager, "mShadeController");
            if (shadeController == null) return;
            Method postForce = findMethodSystemUi(shadeController.getClass(), "postAnimateForceCollapseShade");
            if (postForce != null) { postForce.invoke(shadeController); return; }
            Method instant = findMethodSystemUi(shadeController.getClass(), "instantCollapseShade");
            if (instant != null) { instant.invoke(shadeController); return; }
            Method full = findMethodSystemUi(shadeController.getClass(), "animateCollapseShade", int.class, boolean.class, boolean.class);
            if (full != null) { full.invoke(shadeController, 2, true, true); return; }
            Method normal = findMethodSystemUi(shadeController.getClass(), "animateCollapseShade", int.class);
            if (normal != null) normal.invoke(shadeController, 0);
        } catch (Throwable t) {
            Log.w(TAG, "collapseShade: " + t.getMessage());
        }
    }

    private static Method findMethodByNameAndCount(Class<?> c, String n, int count) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == count) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Method findCompatibleMethodByName(Class<?> c, String n, Class<?> argType) {
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(n) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(argType)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Class<?> clOrNull(ClassLoader cl, String name) {
        try { return cl.loadClass(name); } catch (Throwable t) { return null; }
    }

    private static Object findEntryPoint(ClassLoader cl, String name) {
        try {
            Class<?> epCls = cl.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            for (Object e : (Object[]) epCls.getDeclaredField("$VALUES").get(null)) {
                if (name.equals(e.toString())) return e;
            }
        } catch (Throwable t) { Log.w(TAG, "findEntryPoint: " + t.getMessage()); }
        return null;
    }

    private static void rememberForcedBubble(String key) {
        if (key != null) sForcedBubbleKeys.put(key, System.currentTimeMillis());
    }

    private static boolean isRecentlyForcedBubble(String key) {
        if (key == null) return false;
        Long ts = sForcedBubbleKeys.get(key);
        if (ts == null) return false;
        long age = System.currentTimeMillis() - ts;
        if (age > FORCED_BUBBLE_GRACE_MS) {
            sForcedBubbleKeys.remove(key);
            return false;
        }
        return true;
    }

    private static void prepareBubbleMetadata(Object entry) {
        if (entry == null) return;
        try {
            Object sbn = getFieldSystemUi(entry, "mSbn");
            Notification notif = sbn != null
                    ? (Notification) invokeSystemUi(sbn, "getNotification") : null;
            if (notif == null
                    || (notif.flags & Notification.FLAG_ONGOING_EVENT) != 0
                    || notif.contentIntent == null) {
                return;
            }
            injectBubbleMetadata(entry, notif);
        } catch (Throwable t) {
            Log.w(TAG, "prepareBubbleMetadata: " + t.getMessage());
        }
    }

    private static void refreshBubbleButton(Object row) {
        if (row == null) return;
        try {
            Method update = findMethodSystemUi(row.getClass(), "updateBubbleButton");
            if (update != null && update.getParameterCount() == 0) {
                update.invoke(row);
                return;
            }
            Object contentView = getFieldSystemUi(row, "mPrivateLayout");
            if (contentView == null) contentView = getFieldSystemUi(row, "mNotificationContentView");
            update = contentView != null
                    ? findMethodSystemUi(contentView.getClass(), "updateBubbleButton") : null;
            if (update != null && update.getParameterCount() == 0) {
                update.invoke(contentView);
            }
        } catch (Throwable t) {
            Log.w(TAG, "refreshBubbleButton: " + t.getMessage());
        }
    }

    private static void injectBubbleMetadata(Object entry, Notification notif) {
        try {
            java.lang.reflect.Field metaField = findFieldSystemUi(entry.getClass(), "mBubbleMetadata");
            if (metaField == null) return;
            metaField.setAccessible(true);
            Object existing = metaField.get(entry);
            if (existing instanceof Notification.BubbleMetadata) {
                setNotificationBubbleMetadata(notif, (Notification.BubbleMetadata) existing);
                return;
            }
            Notification.BubbleMetadata.Builder builder = new Notification.BubbleMetadata.Builder();
            builder.setIntent(notif.contentIntent);
            builder.setDeleteIntent(notif.deleteIntent);
            android.graphics.drawable.Icon notificationIcon = null;
            int iconResId = 0;
            try {
                java.lang.reflect.Method getSmall = notif.getClass().getMethod("getSmallIcon");
                Object smallIcon = getSmall.invoke(notif);
                if (smallIcon instanceof android.graphics.drawable.Icon) {
                    notificationIcon = (android.graphics.drawable.Icon) smallIcon;
                }
                if (smallIcon != null) {
                    java.lang.reflect.Method getRes = smallIcon.getClass().getMethod("getResId");
                    iconResId = (int) getRes.invoke(smallIcon);
                }
            } catch (Throwable ignore) {}
            String pkg = null;
            try {
                Object sbn = getFieldSystemUi(entry, "mSbn");
                if (sbn != null) {
                    Object value = invokeSystemUi(sbn, "getPackageName");
                    if (value instanceof String) pkg = (String) value;
                }
            } catch (Throwable ignore) {}

            boolean iconSet = false;
            // Android 16/Lunaris exposes BubbleMetadata.Builder.setIcon(Icon). Passing the
            // notification's Icon preserves its package/user metadata and is required by
            // NotificationContentView.shouldShowBubbleButton().
            if (notificationIcon != null) {
                try {
                    builder.getClass().getMethod("setIcon", android.graphics.drawable.Icon.class)
                            .invoke(builder, notificationIcon);
                    iconSet = true;
                } catch (Throwable t) {
                    Log.w(TAG, "setIcon(Icon) failed: " + t.getMessage());
                }
            }
            // Some ROMs return only a resource id from their Notification implementation.
            // Recreate a package-qualified Icon so the resource is resolved in the app's
            // namespace instead of SystemUI's namespace.
            if (!iconSet && iconResId != 0 && pkg != null) {
                try {
                    android.graphics.drawable.Icon icon =
                            android.graphics.drawable.Icon.createWithResource(pkg, iconResId);
                    builder.getClass().getMethod("setIcon", android.graphics.drawable.Icon.class)
                            .invoke(builder, icon);
                    iconSet = true;
                } catch (Throwable t) {
                    Log.w(TAG, "setIcon(resource Icon) failed: " + t.getMessage());
                }
            }
            // Evolution's older framework accepted an integer resource id. Keep this only as
            // a compatibility fallback; never use it as the primary Lunaris path.
            if (!iconSet && iconResId != 0) {
                try {
                    builder.getClass().getMethod("setIcon", int.class).invoke(builder, iconResId);
                    iconSet = true;
                } catch (Throwable ignored) {}
            }
            if (!iconSet && pkg != null) {
                try {
                    builder.getClass().getMethod("setShortcutId", String.class).invoke(builder, pkg);
                    iconSet = true;
                } catch (Throwable t) {
                    Log.w(TAG, "setIcon/shortcut set failed: " + t.getMessage());
                }
            }
            if (!iconSet) {
                Log.w(TAG, "Bubble metadata has no usable Icon for notification");
                return;
            }
            Notification.BubbleMetadata metadata = builder.build();
            setNotificationBubbleMetadata(notif, metadata);
            metaField.set(entry, metadata);
            Log.i(TAG, "Set bubble metadata OK for " + pkg);
        } catch (Throwable t) {
            Log.w(TAG, "injectBubbleMetadata: " + t.getMessage());
        }
    }

    private static void setNotificationBubbleMetadata(Notification notif, Notification.BubbleMetadata metadata) {
        try {
            Method m = notif.getClass().getMethod("setBubbleMetadata", Notification.BubbleMetadata.class);
            m.invoke(notif, metadata);
            return;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Field f = findFieldSystemUi(notif.getClass(), "mBubbleMetadata");
            if (f == null) f = findFieldSystemUi(notif.getClass(), "bubbleMetadata");
            if (f != null) {
                f.setAccessible(true);
                f.set(notif, metadata);
            }
        } catch (Throwable t) {
            Log.w(TAG, "setNotificationBubbleMetadata: " + t.getMessage());
        }
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) { return null; }
    }
    private static Object invoke(Object obj, String method) {
        try { java.lang.reflect.Method m = findMethodSystemUi(obj.getClass(), method); return m != null ? m.invoke(obj) : null; }
        catch (Throwable t) { return null; }
    }
    private static java.lang.reflect.Field findField(Class<?> c, String n) {
        while (c != null) { try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } return null;
    }
    private static java.lang.reflect.Method findMethod(Class<?> c, String n, Class<?>... p) {
        while (c != null) { try { java.lang.reflect.Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); } } return null;
    }

    private void hookLauncher(PackageLoadedParam param) {
        ClassLoader cl = mLauncherClassLoader;

        // Hook OverviewActionsView.onFinishInflate
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onFinishInflate")).intercept(chain -> {
                Object ret = chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isActionBarEnabled(ctx))
                        injectBubbleButton(chain.getThisObject(), cl);
                } catch (Throwable t) { Log.e(TAG, "inject failed", t); }
                return ret;
            });
        } catch (Throwable t) { Log.e(TAG, "Hook onFinishInflate: " + t.getMessage()); }

        // Hook OverviewActionsView.onClick
        try {
            hook(cl.loadClass("com.android.quickstep.views.OverviewActionsView")
                    .getMethod("onClick", View.class)).intercept(chain -> {
                View v = (View) chain.getArg(0);
                if (isBottomActionButton(v)) {
                    onBottomActionClick(v, "freeform_button".equals(v.getTag()));
                    return null;
                }
                return chain.proceed();
            });
        } catch (Throwable t) { Log.e(TAG, "Hook onClick: " + t.getMessage()); }

        // Hook TaskMenuView.addMenuOptions
        try {
            hook(cl.loadClass("com.android.quickstep.views.TaskMenuView")
                    .getDeclaredMethod("addMenuOptions")).intercept(chain -> {
                chain.proceed();
                try {
                    Context ctx = ((View) chain.getThisObject()).getContext();
                    if (ModuleSettings.isMenuEnabled(ctx))
                        addBubbleMenuOption(chain.getThisObject(), cl);
                } catch (Throwable t) { Log.e(TAG, "addBubbleMenuOption: " + t.getMessage()); }
                return null;
            });
        } catch (Throwable t) { Log.e(TAG, "Hook addMenuOptions: " + t.getMessage()); }
    }

    // ==================== 操作栏按钮 ====================

    @SuppressLint("DiscouragedApi")
    private void injectBubbleButton(Object actionsView, ClassLoader cl) {
        Context ctx = ((View) actionsView).getContext();
        android.content.res.Resources res = ctx.getResources();
        String pkg = ctx.getPackageName();
        ViewGroup actionsParent = (ViewGroup) actionsView;
        boolean showBubble = ModuleSettings.isActionBarBubbleEnabled(ctx);
        boolean showFreeform = ModuleSettings.isActionBarFreeformEnabled(ctx);
        boolean freeformFirst = ModuleSettings.getActionBarOrder(ctx) == 0;
        int positionMode = ModuleSettings.getPositionMode(ctx);

        int nativeRowId = res.getIdentifier(
                "second_overview_actions_container", "id", pkg);
        View nativeSecondRow = nativeRowId != 0
                ? actionsParent.findViewById(nativeRowId) : null;
        if (positionMode == 1 && nativeSecondRow instanceof ViewGroup) {
            removeSecondRowActions();
            if (freeformFirst) {
                if (showFreeform) addToNativeActionRow(ctx, (ViewGroup) nativeSecondRow,
                        res, pkg, true);
                if (showBubble) addToNativeActionRow(ctx, (ViewGroup) nativeSecondRow,
                        res, pkg, false);
            } else {
                if (showBubble) addToNativeActionRow(ctx, (ViewGroup) nativeSecondRow,
                        res, pkg, false);
                if (showFreeform) addToNativeActionRow(ctx, (ViewGroup) nativeSecondRow,
                        res, pkg, true);
            }
            Log.i(TAG, "Second-row mode: using host native icon-only action row");
            return;
        }

        if (positionMode == 0) removeSecondRowActions();
        boolean[] order = freeformFirst ? new boolean[]{true, false}
                : new boolean[]{false, true};
        for (boolean freeform : order) {
            if ((freeform && !showFreeform) || (!freeform && !showBubble)) continue;
            Button btn = createBottomActionButton(ctx, res, pkg, freeform);
            if (positionMode == 0) addToActionButtons(actionsParent, btn, res, pkg);
            else ensureSecondRow(actionsParent, btn, res, pkg);
        }
    }

    @SuppressLint("DiscouragedApi")
    private void addToNativeActionRow(Context ctx, ViewGroup row,
            android.content.res.Resources res, String pkg, boolean freeform) {
        String tag = freeform ? "freeform_button" : "bubble_button";
        for (int i = 0; i < row.getChildCount(); i++) {
            View child = row.getChildAt(i);
            if (tag.equals(child.getTag())) return;
        }

        View sampleButton = findNativeActionSample(row, res, pkg);
        ImageButton button = createNativeActionButton(ctx, res, pkg, freeform, sampleButton);
        int spacerWidth = getNativeActionSpacerWidth(row, res, pkg);
        Space spacer = new Space(ctx);
        spacer.setTag(tag + "_spacing");
        spacer.setLayoutParams(new LinearLayout.LayoutParams(spacerWidth,
                ViewGroup.LayoutParams.MATCH_PARENT));

        int insertionIndex = row.getChildCount();
        int endSpaceId = res.getIdentifier("end_space", "id", pkg);
        View endSpace = endSpaceId != 0 ? row.findViewById(endSpaceId) : null;
        if (endSpace != null) insertionIndex = row.indexOfChild(endSpace);
        row.addView(spacer, insertionIndex++);
        row.addView(button, insertionIndex);
    }

    @SuppressLint("DiscouragedApi")
    private View findNativeActionSample(ViewGroup row,
            android.content.res.Resources res, String pkg) {
        String[] ids = {"action_screenshot", "action_split", "action_lens"};
        for (String name : ids) {
            int id = res.getIdentifier(name, "id", pkg);
            View candidate = id != 0 ? row.findViewById(id) : null;
            if (candidate instanceof ImageButton) return candidate;
        }
        return null;
    }

    @SuppressLint("DiscouragedApi")
    private int getNativeActionSpacerWidth(ViewGroup row,
            android.content.res.Resources res, String pkg) {
        String[] ids = {"action_screenshot_space", "action_split_space", "action_lens_space"};
        for (String name : ids) {
            int id = res.getIdentifier(name, "id", pkg);
            View space = id != 0 ? row.findViewById(id) : null;
            if (space != null && space.getLayoutParams() != null
                    && space.getLayoutParams().width > 0) {
                return space.getLayoutParams().width;
            }
        }
        return (int) (24 * ctxDensity(row.getContext()) + 0.5f);
    }

    private ImageButton createNativeActionButton(Context ctx,
            android.content.res.Resources res, String pkg, boolean freeform, View sample) {
        int styleId = res.getIdentifier("OverviewActionButton", "style", pkg);
        ImageButton button = styleId != 0
                ? new ImageButton(ctx, null, 0, styleId) : new ImageButton(ctx);
        android.graphics.drawable.Drawable icon = freeform
                ? getFreeformActionIcon(ctx, res, pkg) : getBubbleActionIcon(ctx, res, pkg);
        button.setImageDrawable(icon);
        if (sample instanceof ImageView) {
            ImageView imageSample = (ImageView) sample;
            button.setImageTintList(imageSample.getImageTintList());
            button.setScaleType(imageSample.getScaleType());
            button.setPadding(sample.getPaddingLeft(), sample.getPaddingTop(),
                    sample.getPaddingRight(), sample.getPaddingBottom());
            button.setMinimumWidth(sample.getMinimumWidth());
            button.setMinimumHeight(sample.getMinimumHeight());
            button.setMaxWidth(imageSample.getMaxWidth());
            button.setMaxHeight(imageSample.getMaxHeight());
            android.graphics.drawable.Drawable background = sample.getBackground();
            if (background != null && background.getConstantState() != null) {
                button.setBackground(background.getConstantState().newDrawable(
                        res, ctx.getTheme()).mutate());
            }
            button.setBackgroundTintList(sample.getBackgroundTintList());
            ViewGroup.LayoutParams sampleParams = sample.getLayoutParams();
            if (sampleParams != null) {
                if (sampleParams instanceof LinearLayout.LayoutParams) {
                    button.setLayoutParams(new LinearLayout.LayoutParams(
                            (LinearLayout.LayoutParams) sampleParams));
                } else if (sampleParams instanceof ViewGroup.MarginLayoutParams) {
                    button.setLayoutParams(new LinearLayout.LayoutParams(
                            (ViewGroup.MarginLayoutParams) sampleParams));
                } else {
                    button.setLayoutParams(new LinearLayout.LayoutParams(
                            sampleParams.width, sampleParams.height));
                }
            }
        } else {
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        String label = freeform ? getFreeformLabel(ctx) : getBubbleButtonLabel(ctx);
        button.setContentDescription(label);
        button.setTooltipText(label);
        button.setTag(freeform ? "freeform_button" : "bubble_button");
        button.setOnClickListener(v -> onBottomActionClick(v, freeform));
        return button;
    }

    private static float ctxDensity(Context ctx) {
        return ctx.getResources().getDisplayMetrics().density;
    }

    private static boolean isBottomActionButton(View view) {
        if (view == null || view.getTag() == null) return false;
        String tag = view.getTag().toString();
        return "bubble_button".equals(tag) || "freeform_button".equals(tag);
    }

    private void removeSecondRowActions() {
        if (sSecondRow == null) return;
        ViewGroup parent = sSecondRow.getParent() instanceof ViewGroup
                ? (ViewGroup) sSecondRow.getParent() : null;
        if (parent == null) {
            sSecondRow = null;
            return;
        }
        ViewGroup row = (ViewGroup) sSecondRow;
        for (int i = row.getChildCount() - 1; i >= 0; i--) {
            if (isBottomActionButton(row.getChildAt(i))) row.removeViewAt(i);
        }
        if (row.getChildCount() == 0) parent.removeView(row);
        sSecondRow = null;
    }

    /**
     * 更新第二行位置 — 读取当前设置并应用到 LayoutParams
     */
    private void updateSecondRowPosition(Context ctx, FrameLayout.LayoutParams lp) {
        int posX = ModuleSettings.getPosX(ctx);
        int posY = ModuleSettings.getPosY(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;

        // Y 偏移
        int maxOffset = (int)(48 * density);
        lp.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);

        // X margin — 需要等 View 宽度可用
        if (sSecondRow != null && sSecondRow.getWidth() > 0) {
            applyXMargin(ctx, lp);
        } else if (sSecondRow != null) {
            sSecondRow.post(() -> {
                if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                    applyXMargin(ctx, p);
                    sSecondRow.setLayoutParams(p);
                }
            });
        }

        if (sSecondRow != null) {
            sSecondRow.setLayoutParams(lp);
            Log.i(TAG, "Position updated: X=" + posX + " Y=" + posY);
        }
    }

    private static void applyXMargin(Context ctx, FrameLayout.LayoutParams lp) {
        int posX = ModuleSettings.getPosX(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        ViewGroup parent = (ViewGroup) sSecondRow.getParent();
        if (parent == null) return;
        int parentWidth = parent.getWidth();
        int btnWidth = sSecondRow.getWidth();
        if (btnWidth <= 0 || parentWidth <= 0) return;

        // 纯百分比，50%居中时自动补偿图标偏移
        int iconOffset = (int)(13 * density); // 图标左置补偿
        float maxMargin = parentWidth - btnWidth;
        int marginStart = (int)((posX / 100f) * maxMargin) - iconOffset;
        lp.setMarginStart((int) Math.max(0, Math.min(marginStart, maxMargin)));
        Log.i(TAG, "applyXMargin: posX=" + posX + " marginStart=" + marginStart
                + " maxMargin=" + maxMargin + " parentW=" + parentWidth + " btnW=" + btnWidth);
    }

    /**
     * 模式0：跟随原按钮 — 直接加到 action_buttons 末尾
     */
    private void addToActionButtons(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        // 如果之前在第二行，先移除
        if (sSecondRow != null) removeSecondRowActions();

        int abId = res.getIdentifier("action_buttons", "id", pkg);
        LinearLayout actionButtons = (LinearLayout) actionsParent.findViewById(abId);
        if (actionButtons == null) return;

        // 去重
        for (int i = 0; i < actionButtons.getChildCount(); i++) {
            View child = actionButtons.getChildAt(i);
            if (child.getTag() != null && child.getTag().equals(btn.getTag())) return;
        }

        // 添加到末尾
        ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) mlp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(mlp);
        actionButtons.addView(btn);

        Log.i(TAG, btn.getTag() + " added to action_buttons (follow mode)");
    }

    private boolean isClearAllButton(View child) {
        try { return mLauncherClassLoader.loadClass("com.android.quickstep.views.ClearAllButton").isInstance(child); }
        catch (Throwable t) { return false; }
    }

    @SuppressLint("DiscouragedApi")
    private Button createBottomActionButton(Context ctx, android.content.res.Resources res,
            String pkg, boolean freeform) {
        int styleId = res.getIdentifier("OverviewActionButton.Blur", "style", pkg);
        if (styleId == 0) styleId = res.getIdentifier("OverviewActionButton", "style", pkg);
        Button btn = (styleId != 0) ? new Button(ctx, null, 0, styleId) : new Button(ctx);
        String label = freeform ? getFreeformLabel(ctx) : getBubbleButtonLabel(ctx);
        btn.setText(label);
        btn.setContentDescription(label);
        btn.setTooltipText(label);
        btn.setId(View.generateViewId());
        btn.setTag(freeform ? "freeform_button" : "bubble_button");

        // 图标
        android.graphics.drawable.Drawable icon = freeform
                ? getFreeformActionIcon(ctx, res, pkg) : getBubbleActionIcon(ctx, res, pkg);
        if (icon != null) btn.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);

        btn.setOnClickListener(v -> onBottomActionClick(v, freeform));
        return btn;
    }

    private android.graphics.drawable.Drawable getBubbleActionIcon(Context ctx,
            android.content.res.Resources hostResources, String hostPackage) {
        int iconId = hostResources.getIdentifier("ic_bubble_button", "drawable", hostPackage);
        if (iconId == 0) iconId = hostResources.getIdentifier("ic_bubble_bar", "drawable", hostPackage);
        return iconId != 0 ? hostResources.getDrawable(iconId, ctx.getTheme()) : null;
    }

    private android.graphics.drawable.Drawable getConfiguredActionIcon(
            Context ctx, android.content.res.Resources hostResources, String hostPackage) {
        if (isFreeformOpenMode(ctx)) {
            android.graphics.drawable.Drawable icon = getFreeformActionIcon(
                    ctx, hostResources, hostPackage);
            if (icon != null) return icon;
        }
        return getBubbleActionIcon(ctx, hostResources, hostPackage);
    }

    private static android.graphics.drawable.Drawable getFreeformActionIcon(
            Context ctx, android.content.res.Resources hostResources, String hostPackage) {
        try {
            // Lunaris exposes the same icon used by its freeform caption/menu in
            // SystemUI. Reusing the host resource keeps the notification and
            // freeform window controls visually consistent with the ROM.
            if (LUNARIS_SYSTEMUI_PACKAGE.equals(hostPackage)) {
                int iconId = hostResources.getIdentifier(
                        LUNARIS_FREEFORM_ICON, "drawable", hostPackage);
                if (iconId != 0) {
                    android.graphics.drawable.Drawable icon = hostResources
                            .getDrawable(iconId, ctx.getTheme()).mutate();
                    tintFreeformIcon(icon, ctx, hostResources, hostPackage);
                    Log.i(TAG, "Using Lunaris native Freeform icon");
                    return icon;
                }
            }

            Context moduleContext = ctx.createPackageContext(
                    "com.floatwindow.morebubblebutton", Context.CONTEXT_IGNORE_SECURITY);
            android.graphics.drawable.Drawable icon = moduleContext.getResources().getDrawable(
                    R.drawable.ic_freeform_button, moduleContext.getTheme()).mutate();
            tintFreeformIcon(icon, ctx, hostResources, hostPackage);
            return icon;
        } catch (Throwable t) {
            Log.w(TAG, "Freeform icon load failed: " + t.getMessage());
            return null;
        }
    }

    private static void tintFreeformIcon(android.graphics.drawable.Drawable icon, Context ctx,
            android.content.res.Resources hostResources, String hostPackage) {
        if (icon == null) return;
        try {
            try {
                int tintId = hostResources.getIdentifier(
                        "materialColorOnSurface", "color", hostPackage);
                if (tintId != 0) icon.setTint(hostResources.getColor(tintId, ctx.getTheme()));
            } catch (Throwable tintError) {
                Log.w(TAG, "Freeform icon tint failed: " + tintError.getMessage());
            }
        } catch (Throwable t) {
            Log.w(TAG, "Freeform icon tint setup failed: " + t.getMessage());
        }
    }

    @SuppressLint("DiscouragedApi")
    private void ensureSecondRow(ViewGroup actionsParent, Button btn,
            android.content.res.Resources res, String pkg) {
        Context ctx = actionsParent.getContext();

        if (sSecondRow != null && sSecondRow.getParent() == actionsParent) {
            for (int i = 0; i < ((ViewGroup) sSecondRow).getChildCount(); i++) {
                View child = ((ViewGroup) sSecondRow).getChildAt(i);
                if (child.getTag() != null && child.getTag().equals(btn.getTag())) return;
            }
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
            if (spId != 0) mlp.setMarginStart(res.getDimensionPixelSize(spId));
            btn.setLayoutParams(mlp);
            ((ViewGroup) sSecondRow).addView(btn);
            return;
        }

        LinearLayout newSecondRow = new LinearLayout(ctx);
        newSecondRow.setTag("bubble_second_row");
        newSecondRow.setOrientation(LinearLayout.HORIZONTAL);

        // 使用 X/Y 坐标定位
        int posX = ModuleSettings.getPosX(ctx);
        int posY = ModuleSettings.getPosY(ctx);

        // X 轴：用 marginStart 连续定位（不再用离散 gravity）
        // posX 0%=左对齐, 50%=居中, 100%=右对齐
        float density = ctx.getResources().getDisplayMetrics().density;

        ViewGroup.MarginLayoutParams btnMlp = new ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int spId = res.getIdentifier("overview_actions_button_spacing", "dimen", pkg);
        if (spId != 0) btnMlp.setMarginStart(res.getDimensionPixelSize(spId));
        btn.setLayoutParams(btnMlp);
        newSecondRow.addView(btn);

        // 定位：用 layout_gravity=START|BOTTOM + marginStart 实现所有位置
        FrameLayout.LayoutParams rowLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.gravity = android.view.Gravity.START | android.view.Gravity.BOTTOM;

        // Y 偏移：posY 0% = 紧贴底部，100% = 向上偏移 48dp
        int maxOffset = (int)(48 * density);
        int bottomOffset = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);
        rowLp.bottomMargin = bottomOffset;

        int insertIndex = 0;
        int abId = res.getIdentifier("action_buttons", "id", pkg);
        View abv = actionsParent.findViewById(abId);
        if (abv != null) insertIndex = actionsParent.indexOfChild(abv) + 1;

        actionsParent.addView(newSecondRow, insertIndex, rowLp);
        sSecondRow = newSecondRow;

        // post 里应用 X margin + 同步 alpha
        newSecondRow.post(() -> {
            // X margin + alpha 同步 — 用 OnPreDrawListener 保证每帧都正确
            View abv2 = actionsParent.findViewById(
                    res.getIdentifier("action_buttons", "id", pkg));
            actionsParent.getViewTreeObserver().addOnPreDrawListener(
                    new ViewTreeObserver.OnPreDrawListener() {
                        private int lastX = Integer.MIN_VALUE;
                        private int lastY = Integer.MIN_VALUE;

                        @Override public boolean onPreDraw() {
                            if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                                int posX = ModuleSettings.getPosX(ctx);
                                int posY = ModuleSettings.getPosY(ctx);
                                if (posX != lastX || posY != lastY) {
                                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                                    int maxOffset = (int)(48 * ctx.getResources().getDisplayMetrics().density);
                                    p.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);
                                    applyXMargin(ctx, p);
                                    sSecondRow.setLayoutParams(p);
                                    lastX = posX;
                                    lastY = posY;
                                    Log.i(TAG, "live position applied: X=" + posX + " Y=" + posY);
                                }
                            }
                            // alpha 同步
                            if (sSecondRow != null && abv2 != null)
                                sSecondRow.setAlpha(abv2.getAlpha());
                            return true;
                        }
                    });
        });
    }

    private void updateBubbleVisibility(Object av) {}

    // ==================== 菜单项注入 ====================

    @SuppressLint("DiscouragedApi")
    private void addBubbleMenuOption(Object menuView, ClassLoader cl) {
        try {
            Context ctx = ((View) menuView).getContext();
            android.content.res.Resources res = ctx.getResources();
            String pkg = ctx.getPackageName();

            ViewGroup optionLayout = (ViewGroup) findMethod(menuView.getClass(), "getOptionLayout").invoke(menuView);
            Object taskContainer = findMethod(menuView.getClass(), "getTaskContainer").invoke(menuView);
            if (optionLayout == null || taskContainer == null) return;

            ViewGroup menuItem = (ViewGroup) android.view.LayoutInflater.from(ctx)
                    .inflate(res.getIdentifier("task_view_menu_option", "layout", pkg), optionLayout, false);

            int bgId = res.getIdentifier("app_chip_menu_item_bg", "drawable", pkg);
            if (bgId != 0) menuItem.setBackground(res.getDrawable(bgId, ctx.getTheme()));

            View iconView = menuItem.findViewById(res.getIdentifier("icon", "id", pkg));
            if (iconView != null) {
                android.graphics.drawable.Drawable icon = getConfiguredActionIcon(ctx, res, pkg);
                int tintId = res.getIdentifier("materialColorOnSurface", "color", pkg);
                if (icon != null) {
                    if (tintId != 0) icon.setTint(res.getColor(tintId, ctx.getTheme()));
                    iconView.setBackground(icon);
                }
            }

            View tv = menuItem.findViewById(res.getIdentifier("text", "id", pkg));
            if (tv instanceof android.widget.TextView)
                ((android.widget.TextView) tv).setText(getConfiguredOpenLabel(ctx));

            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) menuItem.getLayoutParams();
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            menuItem.setLayoutParams(lp);

            menuItem.setOnClickListener(v -> {
                try {
                    Object task = invoke(taskContainer, "getTask");
                    if (task == null) return;
                    Object key = getField(task, "key");
                    Intent intent = (Intent) getField(key, "baseIntent");
                    int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
                    if (intent != null) {
                        Object recentsView = null;
                        try {
                            Object taskView = findMethod(menuView.getClass(), "getTaskView")
                                    .invoke(menuView);
                            if (taskView != null) {
                                recentsView = findMethod(taskView.getClass(), "getRecentsView")
                                        .invoke(taskView);
                            }
                        } catch (Throwable ignored) {}
                        findMethod(menuView.getClass(), "close", boolean.class).invoke(menuView, true);
                        if (openTaskFromRecents(ctx, intent, task, userId)
                                && recentsView != null) {
                            Object targetRecentsView = recentsView;
                            new android.os.Handler(Looper.getMainLooper()).postDelayed(
                                    () -> dismissOverview(ctx, targetRecentsView), 200);
                        }
                    }
                } catch (Throwable t) { Log.e(TAG, "menu click: " + t.getMessage()); }
            });

            optionLayout.addView(menuItem);
        } catch (Throwable t) { Log.e(TAG, "addBubbleMenuOption: " + t.getMessage()); }
    }

    // ==================== 气泡触发 ====================

    private void onBottomActionClick(View actionView, boolean freeform) {
        Object rv = findVisibleRecentsView(actionView);
        if (rv == null || !isActionBarInVisibleRecents(actionView, rv)) {
            Log.i(TAG, "Ignore bottom action bar click outside visible RecentsView");
            return;
        }

        Context ctx = actionView.getContext();
        try {
            Object tv = findMethod(rv.getClass(), "getCurrentPageTaskView").invoke(rv);
            if (tv == null) return;
            List<?> tc = (List<?>) findMethod(tv.getClass(), "getTaskContainers").invoke(tv);
            if (tc == null || tc.isEmpty()) return;

            Object task = findMethod(tc.get(0).getClass(), "getTask").invoke(tc.get(0));
            Object key = getField(task, "key");
            Intent intent = (Intent) getField(key, "baseIntent");
            int userId = getField(key, "userId") != null ? (int) getField(key, "userId") : 0;
            if (intent == null) return;

            if (openTaskFromRecents(ctx, intent, task, userId, freeform)) {
                Object recentsView = rv;
                new android.os.Handler(Looper.getMainLooper()).postDelayed(
                        () -> dismissOverview(ctx, recentsView), 200);
            }
        } catch (Throwable t) {
            Log.e(TAG, (freeform ? "Freeform" : "Bubble") + " bottom action click: "
                    + t.getMessage());
        }
    }

    private Object findVisibleRecentsView(View view) {
        if (view == null || mLauncherClassLoader == null) return null;
        try {
            Class<?> recentsViewClass = mLauncherClassLoader.loadClass(
                    "com.android.quickstep.views.RecentsView");

            // Some Launcher builds place OverviewActionsView beside RecentsView rather
            // than inside it. First inspect the clicked view's ancestors, then search
            // only its own window root for the currently visible RecentsView.
            View current = view;
            while (current != null) {
                if (recentsViewClass.isInstance(current)
                        && current.isShown() && current.isAttachedToWindow()) return current;
                current = current.getParent() instanceof View
                        ? (View) current.getParent() : null;
            }
            View root = view.getRootView();
            return root != null ? findVisibleViewInTree(root, recentsViewClass) : null;
        } catch (Throwable t) {
            Log.w(TAG, "Find clicked button RecentsView: " + t.getMessage());
        }
        return null;
    }

    private View findVisibleViewInTree(View view, Class<?> targetClass) {
        if (targetClass.isInstance(view) && view.isShown() && view.isAttachedToWindow()) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findVisibleViewInTree(group.getChildAt(i), targetClass);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static boolean isActionBarInVisibleRecents(View actionsView, Object recentsView) {
        if (!(recentsView instanceof View) || actionsView == null) return false;
        View recents = (View) recentsView;
        return actionsView.isShown() && actionsView.isAttachedToWindow()
                && recents.isShown() && recents.isAttachedToWindow();
    }

    private boolean openTaskFromRecents(Context ctx, Intent intent, Object task, int userId) {
        return openTaskFromRecents(ctx, intent, task, userId, isFreeformOpenMode(ctx));
    }

    private boolean openTaskFromRecents(Context ctx, Intent intent, Object task, int userId,
            boolean preferFreeform) {
        if (preferFreeform) {
            if (isFreeformSupported(ctx) && startTaskInFreeform(task, ctx)) {
                Log.i(TAG, "Opened recents task in freeform");
                return true;
            }
            Log.i(TAG, "Recents freeform unavailable, falling back to Bubble");
        }
        if (bubbleCurrentTask(ctx, intent, task, userId)) return true;
        Log.w(TAG, "Bubble launch failed, falling back to fullscreen activity");
        return startTaskFullscreen(ctx, intent, userId);
    }

    private static boolean isFreeformOpenMode(Context ctx) {
        return ctx != null && ModuleSettings.getOpenMode(ctx)
                == ModuleSettings.OPEN_MODE_FREEFORM;
    }

    private static final class RecentsFreeformTarget {
        final String packageName;
        final String activityName;
        final int userId;
        final int taskId;

        RecentsFreeformTarget(String packageName, String activityName, int userId, int taskId) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.userId = userId;
            this.taskId = taskId;
        }
    }

    /**
     * Evolution 17 owns the Freeform display through LMOFreeform. Recents already has
     * a real task, so move that task directly instead of starting its base Intent in a
     * normal task and trying to change its windowing mode afterward.
     */
    private boolean startTaskInFreeform(Object task, Context ctx) {
        RecentsFreeformTarget target = getRecentsFreeformTarget(task);
        if (isLmoFreeformServiceAvailable()) {
            if (target != null && requestLmoFreeformForTask(ctx, target.packageName,
                    target.activityName, target.userId, target.taskId, "Recents")) {
                return true;
            }
            // Do not claim success with the native ActivityOptions path on an LMO device:
            // Evolution's task-area modifier may silently keep the task fullscreen.
            Log.w(TAG, "Recents LMO Freeform request could not be prepared");
            return false;
        }

        // Keep the AOSP/Evolution DesktopMode route for devices that do not expose LMO.
        return startTaskInNativeFreeform(task, ctx);
    }

    private static RecentsFreeformTarget getRecentsFreeformTarget(Object task) {
        try {
            Object key = getField(task, "key");
            if (key == null) key = invoke(task, "getKey");
            if (key == null) return null;

            Intent baseIntent = (Intent) getField(key, "baseIntent");
            ComponentName component = (ComponentName) getField(task, "topActivity");
            if (component == null) component = (ComponentName) invoke(task, "getTopComponent");
            if (component == null && baseIntent != null) component = baseIntent.getComponent();

            String packageName = component != null ? component.getPackageName() : null;
            if (packageName == null && baseIntent != null) packageName = baseIntent.getPackage();
            int userId = getIntField(key, "userId", 0);
            int taskId = getIntField(key, "id", -1);
            if (packageName == null || component == null || taskId < 0) {
                Log.w(TAG, "Recents Freeform target incomplete: package=" + packageName
                        + " component=" + component + " taskId=" + taskId);
                return null;
            }
            return new RecentsFreeformTarget(packageName, component.getClassName(),
                    userId >= 0 ? userId : 0, taskId);
        } catch (Throwable t) {
            Log.w(TAG, "Resolve Recents Freeform target failed: " + t.getMessage());
            return null;
        }
    }

    private static int getIntField(Object object, String name, int defaultValue) {
        Object value = getField(object, name);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }

    /** AOSP/Evolution DesktopMode fallback for devices without LMO Freeform. */
    private boolean startTaskInNativeFreeform(Object task, Context ctx) {
        try {
            Object key = getField(task, "key");
            if (key == null) key = invoke(task, "getKey");
            if (key == null || mLauncherClassLoader == null) return false;

            Class<?> wrapperCls = mLauncherClassLoader.loadClass(
                    "com.android.systemui.shared.system.ActivityManagerWrapper");
            Method getInstance = wrapperCls.getMethod("getInstance");
            Object wrapper = getInstance.invoke(null);
            ActivityOptions options = ActivityOptions.makeBasic();
            if (!configureFreeformOptions(options, ctx, true)) return false;

            for (Method method : allMethods(wrapper.getClass())) {
                if (!method.getName().equals("startActivityFromRecents")
                        || method.getParameterCount() != 2
                        || !ActivityOptions.class.isAssignableFrom(method.getParameterTypes()[1])
                        || !method.getParameterTypes()[0].isAssignableFrom(key.getClass())) {
                    continue;
                }
                method.setAccessible(true);
                Object result = method.invoke(wrapper, key, options);
                boolean success = !(result instanceof Boolean) || (Boolean) result;
                Log.i(TAG, "ActivityManagerWrapper.startActivityFromRecents result=" + success);
                return success;
            }
        } catch (Throwable t) {
            Log.w(TAG, "startTaskInNativeFreeform: " + t.getMessage());
        }
        return false;
    }

    private static List<Method> allMethods(Class<?> type) {
        java.util.ArrayList<Method> methods = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) methods.add(method);
        }
        return methods;
    }

    private static boolean startTaskFullscreen(Context ctx, Intent intent, int userId) {
        try {
            Intent fallback = new Intent(intent);
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(fallback);
            Log.i(TAG, "Started recents task fullscreen");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "startTaskFullscreen: " + t.getMessage());
            return false;
        }
    }

    private boolean bubbleCurrentTask(Context ctx, Intent taskIntent, Object task, int userId) {
        try {
            Class<?> proxyCls = mLauncherClassLoader.loadClass("com.android.quickstep.SystemUiProxy");
            Object ds = proxyCls.getField("INSTANCE").get(null);
            Object proxy = ds.getClass().getMethod("get", Context.class).invoke(ds, ctx.getApplicationContext());
            if (proxy == null) return false;

            Intent bIntent = new Intent(taskIntent);
            // Always ensure package is set (BubbleData.getOrCreateBubble needs intent.getPackage())
            if (bIntent.getPackage() == null && bIntent.getComponent() != null)
                bIntent.setPackage(bIntent.getComponent().getPackageName());

            // Set the exact top component for precise bubble targeting (for deep-linked / secondary activities)
            try {
                Object topComponent = invoke(task, "getTopComponent");
                String className = (String) invoke(topComponent, "getClassName");
                if (className != null && bIntent.getPackage() != null) {
                    bIntent.setComponent(new ComponentName(bIntent.getPackage(), className));
                }
            } catch (Throwable ignored) {}

            Object userHandle = mLauncherClassLoader.loadClass("android.os.UserHandle")
                    .getMethod("of", int.class).invoke(null, userId);

            Class<?> epCls = mLauncherClassLoader.loadClass("com.android.wm.shell.shared.bubbles.logging.EntryPoint");
            Object ep = null;
            for (Object e : (Object[]) epCls.getDeclaredField("$VALUES").get(null))
                if ("NOTIFICATION".equals(e.toString())) { ep = e; break; }

            for (Method m : proxy.getClass().getMethods())
                if (m.getName().equals("showAppBubble")) {
                    m.invoke(proxy, bIntent, userHandle, ep, null);
                    Log.i(TAG, "showAppBubble OK");
                    return true;
                }
        } catch (Throwable t) { Log.e(TAG, "bubbleCurrentTask: " + t.getMessage()); }
        return false;
    }

    private void dismissOverview(Context ctx, Object recentsView) {
        try {
            if (!(recentsView instanceof View)) return;
            if (!isActionBarInVisibleRecents((View) recentsView, recentsView)) return;
            Object sm = findMethod(recentsView.getClass(), "getStateManager").invoke(recentsView);
            if (sm != null) {
                findMethod(sm.getClass(), "moveToRestState").invoke(sm);
                Log.i(TAG, "dismissed current RecentsView via moveToRestState");
            }
        } catch (Throwable t) { Log.e(TAG, "dismiss: " + t.getMessage()); }
    }

    // ==================== 工具方法 ====================

    private static Object getFieldSystemUi(Object obj, String name) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = findFieldSystemUi(obj.getClass(), name);
            if (f == null) return null;
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable t) { return null; }
    }

    private static Object invokeSystemUi(Object obj, String method) {
        try { Method m = findMethodSystemUi(obj.getClass(), method); return m != null ? m.invoke(obj) : null; }
        catch (Throwable t) { return null; }
    }

    private static java.lang.reflect.Field findFieldSystemUi(Class<?> c, String n) {
        while (c != null) { try { return c.getDeclaredField(n); } catch (NoSuchFieldException e) { c = c.getSuperclass(); } } return null;
    }

    private static Method findMethodSystemUi(Class<?> c, String n, Class<?>... p) {
        while (c != null) { try { Method m = c.getDeclaredMethod(n, p); m.setAccessible(true); return m; } catch (NoSuchMethodException e) { c = c.getSuperclass(); } } return null;
    }

    private static void showToast(Context ctx, String msg) {
        new android.os.Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show());
    }

    private static String getBubbleButtonLabel(Context hostContext) {
        try {
            Context moduleContext = hostContext.createPackageContext(
                    "com.floatwindow.morebubblebutton", Context.CONTEXT_IGNORE_SECURITY);
            return moduleContext.getString(R.string.bubble_button_label);
        } catch (Throwable ignored) {
            Locale locale = hostContext.getResources().getConfiguration().getLocales().get(0);
            return locale != null && "zh".equalsIgnoreCase(locale.getLanguage())
                    ? "消息气泡" : "Bubble";
        }
    }

    /**
     * 静态方法：从模块 Activity 调用，重新应用位置设置
     */
    public static void applyPositionFromSettings(Context ctx) {
        if (sSecondRow == null) {
            Log.i(TAG, "applyPositionFromSettings: sSecondRow is null, settings saved for next load");
            return;
        }

        try {
            Log.i(TAG, "applyPositionFromSettings: X=" + ModuleSettings.getPosX(ctx)
                    + " Y=" + ModuleSettings.getPosY(ctx));

            if (sSecondRow.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                float density = ctx.getResources().getDisplayMetrics().density;

                // Y 偏移
                int posY = ModuleSettings.getPosY(ctx);
                int maxOffset = (int)(48 * density);
                lp.bottomMargin = (int)(Math.min(posY * 1.4f, 100f) / 100f * maxOffset);

                // X margin — 等布局完成后应用
                if (sSecondRow.getWidth() > 0) {
                    applyXMargin(ctx, lp);
                } else {
                    sSecondRow.post(() -> {
                        if (sSecondRow != null && sSecondRow.getWidth() > 0) {
                            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sSecondRow.getLayoutParams();
                            applyXMargin(ctx, p);
                            sSecondRow.requestLayout();
                            Log.i(TAG, "X margin applied via post");
                        }
                    });
                }

                sSecondRow.setLayoutParams(lp);
                sSecondRow.requestLayout();
                Log.i(TAG, "Position applied: X=" + ModuleSettings.getPosX(ctx) + " Y=" + posY);
            }
        } catch (Throwable t) {
            Log.e(TAG, "applyPositionFromSettings failed: " + t.getMessage());
        }
    }

    private static View findViewByTag(View view, String tag) {
        if (tag.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findViewByTag(vg.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }
}
