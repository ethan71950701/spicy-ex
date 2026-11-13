package com.eza.spicyex.hooks;

import static com.eza.spicyex.hooks.NativeLyricsUtils.dp;
import static com.eza.spicyex.hooks.NativeLyricsUtils.isBlank;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.os.Build;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;

import com.eza.spicyex.Diagnostics;
import com.eza.spicyex.References;
import com.eza.spicyex.Settings;
import com.eza.spicyex.SpotifyPlusConfig;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;
import com.eza.spicyex.xposed.XpReflect;

/** Owns Spotify activity takeover, entry injection, keepalive, and native shell root mount. */
final class LyricsActivityTakeoverHook {
    private static final String LYRICS_FULLSCREEN_ACTIVITY =
            "com.spotify.lyrics.fullscreenview.page.LyricsFullscreenPageActivity";
    private static final int TAG_NATIVE_SPICY_ROOT = 0x53504C53; // SPLS
    private static final int TAG_COVERED_SIBLINGS = 0x53504C43; // SPLC
    private static final long KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS = 3500L;

    private static final WeakHashMap<Activity, Long> EXPLICIT_LYRICS_EXIT_UNTIL_MS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Long> KEEP_LYRICS_ACTIVITY_UNTIL_MS = new WeakHashMap<>();
    // Exact-class method lookups bypass subclass overrides, so one Activity-level
    // back hook is not enough (see ensureLegacyBackCoverage). Each declaring
    // class+method pair is covered once per process.
    private static final Set<String> LEGACY_BACK_COVERED =
            Collections.synchronizedSet(new HashSet<>());
    // A back key observed at dispatch means a shortly-following finish is
    // user-initiated even when no onBackPressed override ran (a view consumed
    // the key and finished directly). Consumed-without-finish presses expire.
    private static final WeakHashMap<Activity, Long> BACK_PRESS_UNTIL_MS = new WeakHashMap<>();
    private static final long BACK_PRESS_EXIT_WINDOW_MS = 2500L;
    // Armed by our own entry button right before launching the lyrics activity; consumed when we mount.
    // Spotify's own lyric entry launches the same activity without arming this; it still takes over when Spicy is on.
    private static volatile boolean takeoverArmed = false;
    // True while our native lyrics screen is the active session. Survives activity recreation
    // (rotation/config change) so we re-mount instead of falling back to Spotify's native screen;
    // cleared on an explicit exit or a real (non-config-change) destroy.
    private static volatile boolean nativeLyricsSessionActive = false;

    private final NativeSpicyLyricsHook host;
    private final NowPlayingInjector nowPlayingInjector;
    private final WeakHashMap<Activity, OnBackInvokedCallback> backCallbacks = new WeakHashMap<>();

    LyricsActivityTakeoverHook(NativeSpicyLyricsHook host, NowPlayingInjector nowPlayingInjector) {
        this.host = host;
        this.nowPlayingInjector = nowPlayingInjector;
    }

    void hook() {
        NativeSpicyLyricsHook.dbgEnter("hookLyricsActivityLifecycle");
        XpHooks.findAfter(Activity.class, "onCreate", "takeover:Activity#onCreate", param -> {
            Activity activity = (Activity) param.thisObject;
            if (isLyricsFullscreenActivity(activity)) {
                // No system-back registration here: native Spotify screens stay untouched.
                // Registration happens only after takeover ownership is established.
                if (activateNativeTakeover(activity)) {
                    ensureSystemBackCallback(activity);
                    XpLog.log(NativeSpicyLyricsHook.TAG
                            + " lyrics activity onCreate (takeover) " + activity.getClass().getName());
                }
                // else: Spicy disabled - leave Spotify's own lyrics screen untouched.
            } else {
                scheduleNowPlayingCard(activity);
                if (isNativeSpicyEnabled(activity)) nowPlayingInjector.installCardGuard(activity);
            }
        }, android.os.Bundle.class);

        XpHooks.findAfter(Activity.class, "onResume", "takeover:Activity#onResume", param -> {
            Activity activity = (Activity) param.thisObject;
            References.setCurrentActivity(activity);
            if (isLyricsFullscreenActivity(activity)) {
                if (activateNativeTakeover(activity)) {
                    ensureSystemBackCallback(activity);
                    ensureLegacyBackCoverage(activity);
                }
                // else: Spicy disabled - keep Spotify's own lyrics screen.
            } else {
                scheduleNowPlayingCard(activity);
            }
        });

        XpHooks.findAfter(Activity.class, "onWindowFocusChanged", "takeover:Activity#onWindowFocusChanged",
                param -> {
                    if (!((boolean) param.args[0])) return;
                    Activity activity = (Activity) param.thisObject;
                    if (!isLyricsFullscreenActivity(activity)) return;
                    // Explicit exit must not rearm while the old root still exists: focus
                    // transitions during the exit animation would otherwise remount/re-register.
                    if (isExplicitLyricsExit(activity)) {
                        unregisterSystemBackCallback(activity);
                        return;
                    }
                    if (activateNativeTakeover(activity)) {
                        ensureSystemBackCallback(activity);
                        ensureLegacyBackCoverage(activity);
                        mountNativeSpicyRoot(activity);
                    }
                }, boolean.class);

        XpHooks.findBefore(Activity.class, "onDestroy", "takeover:Activity#onDestroy", param -> {
            Activity activity = (Activity) param.thisObject;
            nowPlayingInjector.destroy(activity);
            if (!isLyricsFullscreenActivity(activity)) return;
            unregisterSystemBackCallback(activity);
            // Keep takeover state through every lyrics-page destroy. Spotify may report a
            // track-change rotation as a normal destroy, and the old content root can already
            // be detached before this hook runs. Non-lyrics onResume and explicit back clear it.
            removeNativeSpicyRoot(activity);
        });

        XpHooks.findAfter(Activity.class, "onPause", "takeover:Activity#onPause", param -> {
            Activity activity = (Activity) param.thisObject;
            nowPlayingInjector.stop(activity); // quiet the now-playing card ticker
        });

        XpHooks.findBefore(Activity.class, "onBackPressed", "takeover:Activity#onBackPressed", param -> {
            handleLegacyActivityBack((Activity) param.thisObject, param);
        });

        // Back keys reach views before any onBackPressed override runs. On the legacy path
        // (notably the API-32 Fold), offer Back-up to owned shell layers first; otherwise an open
        // editor sheet is skipped and Spotify exits the whole lyrics activity. When no shell
        // layer consumes it, record Back-down and leave Spotify's normal handling untouched.
        XpHooks.findBefore(Activity.class, "dispatchKeyEvent", "takeover:Activity#dispatchKeyEvent", param -> {
            handleActivityKeyForBack((Activity) param.thisObject, param);
        }, KeyEvent.class);

        XpHooks.findBefore(Activity.class, "finish", "takeover:Activity#finish", param -> {
            Activity activity = (Activity) param.thisObject;
            // A back press observed at dispatch promotes this finish to an
            // explicit exit (clearing the session) instead of suppressing it.
            if (isLyricsFullscreenActivity(activity) && consumeRecentBackPress(activity)) {
                markExplicitLyricsExit(activity);
            }
            if (!shouldKeepLyricsActivityOpen(activity)) return;
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " suppressed non-explicit lyrics activity finish to keep native renderer open");
            param.setResult(null);
        });
    }

    boolean isNativeSpicyEnabled(Activity activity) {
        try {
            return SpotifyPlusConfig.from(activity).get(Settings.NATIVE_SPICY_ENABLED);
        } catch (Throwable ignored) {
            return true;
        }
    }

    void markExplicitLyricsExit(Activity activity) {
        if (activity == null) return;
        nativeLyricsSessionActive = false; // user is leaving - end the session (next open takes over again)
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            EXPLICIT_LYRICS_EXIT_UNTIL_MS.put(activity, SystemClock.elapsedRealtime() + 1200);
        }
        // End owned-back handling immediately so the exit animation/focus transition
        // cannot rearm it while the old root still exists. Destroy also unregisters.
        unregisterSystemBackCallback(activity);
    }

    private boolean isExplicitLyricsExit(Activity activity) {
        if (activity == null) return false;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    // Legacy back path shared by every onBackPressed declaring class (see
    // ensureLegacyBackCoverage). Legacy path (API <33, or predictive back
    // off): the dispatcher callback never runs, so finish explicitly instead
    // of relying on Spotify's onBackPressed to finish. Inactive native
    // screens fall through untouched.
    private void handleLegacyActivityBack(Activity activity, XpHooks.XpParam param) {
        if (activity == null) return;
        if (!isLyricsFullscreenActivity(activity)) {
            if (nowPlayingInjector.consumeArtworkBack(activity)) param.setResult(null);
            return;
        }
        // An override that calls super would otherwise exit twice; the first
        // firing already marked the exit and finished.
        if (isExplicitLyricsExit(activity)) {
            param.setResult(null);
            return;
        }
        if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) return;
        param.setResult(null);
        // The lyrics screen closes its own layers first (share sheet, line picker, editor).
        if (shellConsumesBack(activity)) return;
        markExplicitLyricsExit(activity);
        activity.finish();
    }

    // getDeclaredMethod lookups are exact-class only, so an onBackPressed
    // override in the concrete lyrics activity (or its androidx base) bypasses
    // the Activity-level hook while its finish() still hits our suppression:
    // back then does nothing and the screen looks stuck. Cover each declaring
    // class once, lazily, from an activity instance that carries the host
    // classloader. Misses fail silent and keep the old behavior.
    private void ensureLegacyBackCoverage(Activity activity) {
        if (activity == null || !isLyricsFullscreenActivity(activity)) return;
        coverHostMethod(activity.getClass(), "onBackPressed");
        coverHostMethod(activity.getClass(), "dispatchKeyEvent");
        Class<?> component = XpReflect.findClassIfExists(
                "androidx.activity.ComponentActivity", activity.getClass().getClassLoader());
        coverHostMethod(component, "onBackPressed");
        coverHostMethod(component, "dispatchKeyEvent");
    }

    private void coverHostMethod(Class<?> clazz, String method) {
        if (clazz == null || clazz == Activity.class) return;
        String key = clazz.getName() + "#" + method;
        synchronized (LEGACY_BACK_COVERED) {
            if (!LEGACY_BACK_COVERED.add(key)) return;
        }
        try {
            Class<?>[] params = "dispatchKeyEvent".equals(method)
                    ? new Class<?>[]{KeyEvent.class} : new Class<?>[0];
            clazz.getDeclaredMethod(method, params);
        } catch (NoSuchMethodException missing) {
            return;
        }
        XpLog.log(NativeSpicyLyricsHook.TAG + " covering " + method + " override in " + clazz.getName());
        try {
            if ("dispatchKeyEvent".equals(method)) {
                XpHooks.hookAllMethods(clazz, method, "takeover:Activity#dispatchKeyEvent-override",
                        (XpHooks.Before) param -> handleActivityKeyForBack(
                                (Activity) param.thisObject, param));
            } else {
                XpHooks.hookAllMethods(clazz, method, "takeover:Activity#onBackPressed-override",
                        (XpHooks.Before) param -> handleLegacyActivityBack(
                                (Activity) param.thisObject, param));
            }
        } catch (Throwable ignored) {
        }
    }

    private void handleActivityKeyForBack(Activity activity, XpHooks.XpParam param) {
        if (activity == null || !isLyricsFullscreenActivity(activity)) return;
        if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof KeyEvent)) return;
        KeyEvent event = (KeyEvent) param.args[0];
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getAction() == KeyEvent.ACTION_UP
                && shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))
                && shellConsumesBack(activity)) {
            synchronized (BACK_PRESS_UNTIL_MS) {
                BACK_PRESS_UNTIL_MS.remove(activity);
            }
            param.setResult(true);
            return;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            synchronized (BACK_PRESS_UNTIL_MS) {
                BACK_PRESS_UNTIL_MS.put(activity, SystemClock.elapsedRealtime() + BACK_PRESS_EXIT_WINDOW_MS);
            }
        }
    }

    private static boolean consumeRecentBackPress(Activity activity) {
        if (activity == null) return false;
        synchronized (BACK_PRESS_UNTIL_MS) {
            Long until = BACK_PRESS_UNTIL_MS.remove(activity);
            return until != null && SystemClock.elapsedRealtime() <= until;
        }
    }

    // Owned-overlay back only: registered after takeover ownership is established    // (active session), never on native Spotify screens. No host-dispatch fallback:
    // unregistered screens keep normal host handling untouched.
    private void ensureSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        if (!isLyricsFullscreenActivity(activity)) return;
        if (isExplicitLyricsExit(activity)) return;
        if (!nativeLyricsSessionActive) return;
        synchronized (backCallbacks) {
            if (backCallbacks.containsKey(activity)) return;
            OnBackInvokedCallback callback = () -> {
                // Registration implies ownership; re-check for races without ever
                // delegating to deprecated host handling from inside the callback.
                if (!shouldInterceptLyricsBack(nativeLyricsSessionActive, hasNativeSpicyRoot(activity))) {
                    unregisterSystemBackCallback(activity);
                    return;
                }
                // This callback outranks everything else on the dispatcher, so the layout editor
                // never saw a back press and back closed the whole lyrics screen mid-edit. Ask
                // the shell first: the editor's sheet closes, then the editor, then the screen.
                if (shellConsumesBack(activity)) return;
                markExplicitLyricsExit(activity);
                activity.finish();
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
            backCallbacks.put(activity, callback);
        }
    }

    private void unregisterSystemBackCallback(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 33) return;
        synchronized (backCallbacks) {
            OnBackInvokedCallback callback = backCallbacks.remove(activity);
            if (callback != null) activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(callback);
        }
    }

    void markLyricsActivityKeepWindow(Activity activity) {
        if (activity == null) return;
        synchronized (KEEP_LYRICS_ACTIVITY_UNTIL_MS) {
            KEEP_LYRICS_ACTIVITY_UNTIL_MS.put(
                    activity,
                    SystemClock.elapsedRealtime() + KEEP_LYRICS_ACTIVITY_AFTER_MOUNT_MS
            );
        }
    }

    private void scheduleNowPlayingCard(Activity activity) {
        if (activity == null) return;
        try {
            if (!isNativeSpicyEnabled(activity)) return;
            nowPlayingInjector.schedule(activity);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " schedule live card injection failed: " + t);
        }
    }

    boolean launchNativeLyricsFullscreen(Activity activity) {
        try {
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
            takeoverArmed = true;
            Intent intent = new Intent();
            intent.setClassName(activity.getPackageName(), LYRICS_FULLSCREEN_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            XpLog.log(NativeSpicyLyricsHook.TAG
                    + " launched native lyrics fullscreen (takeover armed) from Spicy lyrics button");
            return true;
        } catch (Throwable t) {
            takeoverArmed = false;
            XpLog.log(NativeSpicyLyricsHook.TAG + " launch native lyrics fullscreen failed: " + t);
            return false;
        }
    }

    private boolean consumeTakeoverArmed() {
        if (takeoverArmed) {
            takeoverArmed = false;
            return true;
        }
        return false;
    }

    private boolean activateNativeTakeover(Activity activity) {
        if (!isLyricsFullscreenActivity(activity)) return false;
        // Stale-root reclaim must not resurrect a session the user just exited.
        if (isExplicitLyricsExit(activity)) return false;
        if (nativeLyricsSessionActive || hasNativeSpicyRoot(activity)) return true;
        // Every open of Spotify's lyrics screen (our button, the lyric card, or Spotify's own entry
        // points) becomes the Spicy screen while the native Spicy switch is on.
        boolean armed = consumeTakeoverArmed();
        if (!armed && !isNativeSpicyEnabled(activity)) return false;
        // Promote the one-shot entry signal before waiting for a mount-ready window. This is the
        // durable lifecycle signal carried across rotation and Spotify activity relaunches.
        nativeLyricsSessionActive = true;
        return true;
    }

    private boolean isStayInLyricsEnabled(Activity activity) {
        try {
            return LyricsShellChromeConfig.read(SpotifyPlusConfig.from(activity)).stayInLyrics;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // Pure back/finish decision matrix (unit-tested): user back must exit even while
    // track-change/rotation finishes stay suppressed; inactive native screens stay untouched.
    static boolean shouldInterceptLyricsBack(boolean sessionActive, boolean hasRoot) {
        return sessionActive || hasRoot;
    }

    static boolean shouldSuppressLyricsFinish(boolean stayInLyricsEnabled,
                                              boolean nativeSpicyEnabled,
                                              boolean sessionActive,
                                              boolean explicitExit) {
        if (!nativeSpicyEnabled) return false;
        if (!stayInLyricsEnabled) return false;
        if (!sessionActive) return false;
        return !explicitExit;
    }

    private boolean shouldKeepLyricsActivityOpen(Activity activity) {
        if (!isLyricsFullscreenActivity(activity)) return false;
        // Spotify invalidates and finishes its fullscreen lyrics activity after some track
        // changes. During rotation that finish can happen before our recreated root mounts, so
        // root presence and short keep windows are not stable ownership signals. The takeover
        // session is stable; explicit back clears it before calling finish.
        boolean explicitExit;
        synchronized (EXPLICIT_LYRICS_EXIT_UNTIL_MS) {
            Long until = EXPLICIT_LYRICS_EXIT_UNTIL_MS.get(activity);
            explicitExit = until != null && SystemClock.elapsedRealtime() <= until;
        }
        return shouldSuppressLyricsFinish(isStayInLyricsEnabled(activity),
                isNativeSpicyEnabled(activity), nativeLyricsSessionActive, explicitExit);
    }

    static boolean isLyricsFullscreenActivity(Activity activity) {
        return activity != null && LYRICS_FULLSCREEN_ACTIVITY.equals(activity.getClass().getName());
    }

    private void mountNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("mountNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            if (activity == null || activity.isFinishing()
                    || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) return;
            if (!isLyricsFullscreenActivity(activity)) return;
            if (isExplicitLyricsExit(activity)) {
                unregisterSystemBackCallback(activity);
                return;
            }
            if (!isNativeSpicyEnabled(activity)) {
                removeNativeSpicyRoot(activity);
                return;
            }
            // Captured before the flag flips below: true here means a lyrics session was already
            // active going into this call, i.e. this mount is a reattach after an orientation-
            // driven activity recreate, not the screen's first open this session.
            boolean rotationContinuation = nativeLyricsSessionActive;
            nativeLyricsSessionActive = true; // our screen owns this lyrics session (survives rotation)
            ensureSystemBackCallback(activity);

            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) {
                XpLog.log(NativeSpicyLyricsHook.TAG + " content root missing");
                return;
            }

            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                ((NativeSpicyShellView) existing).start();
                if (existing.getAlpha() >= 1f) hideCoveredSiblings(content, existing);
                ensureSystemBackCallback(activity);
                return;
            }

            NativeSpicyShellView root = new NativeSpicyShellView(host, activity);
            root.setTag(TAG_NATIVE_SPICY_ROOT);
            // Rotation reattach: panel was already visible a moment ago, show it instantly
            // without fading in. Fresh open: start invisible and fade up to announce arrival.
            if (rotationContinuation) {
                root.setAlpha(1f);
                root.setTranslationY(0f);
            } else {
                root.setAlpha(0f);
                root.setTranslationY(NativeLyricsUtils.dp(24));
            }
            content.addView(root, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            markLyricsActivityKeepWindow(activity);
            root.start();
            if (!rotationContinuation) {
                root.animate().alpha(1f).translationY(0f).setDuration(260)
                        .withEndAction(() -> hideCoveredSiblings(content, root)).start();
            } else {
                hideCoveredSiblings(content, root);
            }
            XpLog.log(NativeSpicyLyricsHook.TAG + " mounted native Spicy renderer shell");
            Diagnostics.event("renderer", "mount_state",
                    Diagnostics.context("surface", "fullscreen", "mounted", "true"));
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " mount failed: " + t);
            Diagnostics.event("renderer", "mount_state", t,
                    Diagnostics.context("surface", "fullscreen", "mounted", "false"));
        }
    }

    private void removeNativeSpicyRoot(Activity activity) {
        NativeSpicyLyricsHook.dbg("removeNativeSpicyRoot",
                "activity=" + (activity == null ? "null" : activity.getClass().getName()));
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            if (existing instanceof NativeSpicyShellView) {
                NativeSpicyShellView shell = (NativeSpicyShellView) existing;
                // Before any fade-out: the page underneath must be drawn again once the shell
                // stops covering it.
                restoreCoveredSiblings(shell);
                // Host activity may already be leaving for rotation or a track-driven recreate.
                // Do not rely on an exit animation callback from a detached window to stop the
                // shell; that callback can be skipped, leaving stale subscriptions alive.
                if (activity.isDestroyed() || activity.isFinishing() || activity.isChangingConfigurations()) {
                    shell.stop();
                    content.removeView(shell);
                    return;
                }
                shell.animate().alpha(0f).translationY(NativeLyricsUtils.dp(24)).setDuration(220).withEndAction(() -> {
                    try {
                        shell.stop();
                        content.removeView(shell);
                    } catch (Throwable t) {
                        XpLog.log(NativeSpicyLyricsHook.TAG + " remove animation cleanup failed: " + t);
                    }
                }).start();
                XpLog.log(NativeSpicyLyricsHook.TAG + " removed native Spicy shell");
            }
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " remove failed: " + t);
        }
        // Rotation keeps the session (and its owned back) alive across remount; only release
        // owned back when ownership itself ended. Explicit exit and destroy unregister directly.
        if (!nativeLyricsSessionActive) unregisterSystemBackCallback(activity);
    }

    /**
     * Spotify's own lyrics page (a full-screen ComposeView) stays mounted under the opaque shell.
     * Left VISIBLE it is still recorded and rasterized every frame behind the lyrics - a whole
     * extra screen of drawing, plus Compose's own lyric animations - without a single pixel of it
     * ever reaching the display. Hiding it once the shell fully covers it changes nothing on
     * screen; it stays attached and laid out, so anything reading its views still works.
     */
    private static void hideCoveredSiblings(FrameLayout content, View root) {
        try {
            if (content == null || root == null || root.getParent() != content) return;
            if (root.getTag(TAG_COVERED_SIBLINGS) != null) return;
            java.util.ArrayList<View> hidden = new java.util.ArrayList<>();
            int rootIndex = content.indexOfChild(root);
            for (int i = 0; i < rootIndex; i++) {
                View child = content.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;
                child.setVisibility(View.INVISIBLE);
                hidden.add(child);
            }
            root.setTag(TAG_COVERED_SIBLINGS, hidden);
        } catch (Throwable t) {
            XpLog.log(NativeSpicyLyricsHook.TAG + " hide covered page failed: " + t);
        }
    }

    private static void restoreCoveredSiblings(View root) {
        try {
            Object tag = root == null ? null : root.getTag(TAG_COVERED_SIBLINGS);
            if (!(tag instanceof java.util.List)) return;
            root.setTag(TAG_COVERED_SIBLINGS, null);
            for (Object o : (java.util.List<?>) tag) {
                // Only undo our own change; Spotify may have hidden the view itself meanwhile.
                if (o instanceof View && ((View) o).getVisibility() == View.INVISIBLE) {
                    ((View) o).setVisibility(View.VISIBLE);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean shellConsumesBack(Activity activity) {
        try {
            FrameLayout content = activity.findViewById(android.R.id.content);
            View root = content == null ? null : content.findViewWithTag(TAG_NATIVE_SPICY_ROOT);
            return root instanceof NativeSpicyShellView && ((NativeSpicyShellView) root).consumeBack();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasNativeSpicyRoot(Activity activity) {
        try {
            if (activity == null) return false;
            FrameLayout content = activity.findViewById(android.R.id.content);
            return content != null && content.findViewWithTag(TAG_NATIVE_SPICY_ROOT) instanceof NativeSpicyShellView;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
