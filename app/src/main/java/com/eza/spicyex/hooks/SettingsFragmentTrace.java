package com.eza.spicyex.hooks;

import android.app.Activity;
import android.net.Uri;
import android.os.Looper;

import com.eza.spicyex.xposed.XpHooks;
import com.eza.spicyex.xposed.XpLog;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Diagnostic only: records the first resume of each Fragment class in Spotify, with the call
 * stack that led to it. Used to find which Fragment and call path build Spotify's settings page.
 * Logs under the "SettingsTrace" tag; changes no behavior.
 */
final class SettingsFragmentTrace {
    private static final String PREFIX = NativeSpicyLyricsHook.TAG + " SettingsTrace ";
    private static final int MAX_FRAMES = 40;
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<>());
    private static final Set<String> SEEN_URIS = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean installed;

    private SettingsFragmentTrace() {
    }

    /** Hooks Fragment.onResume once per process, resolving the classes through Spotify's loader. */
    static void installOnce(Activity activity) {
        if (installed || activity == null) return;
        installed = true;
        ClassLoader loader = activity.getClassLoader();
        install(loader, "androidx.fragment.app.Fragment");
        install(loader, "android.app.Fragment");
        installUriTrace();
    }

    /** Logs the first call stack for each Uri string that mentions "settings" (Spotify routes). */
    private static void installUriTrace() {
        try {
            XpHooks.findBefore(Uri.class, "parse", "settingsTrace:uriParse", param -> {
                Object arg = param.args[0];
                if (!(arg instanceof String)) return;
                String uri = (String) arg;
                if (!uri.toLowerCase(java.util.Locale.ROOT).contains("settings")) return;
                if (!SEEN_URIS.add(uri)) return;
                XpLog.log(PREFIX + "uri=" + uri + " stack=" + stackOf());
                if ("spotify:settings".equals(uri)) sampleMainThread();
            }, String.class);
            XpLog.log(PREFIX + "hooked Uri.parse");
        } catch (Throwable t) {
            XpLog.log(PREFIX + "Uri.parse unavailable: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Samples the main thread's stack every 80 ms for about 2 s after the settings route opens,
     * and logs the unique frames. Compose composition frames show which code builds the rows.
     */
    private static void sampleMainThread() {
        final Thread main = Looper.getMainLooper().getThread();
        Thread sampler = new Thread(() -> {
            Set<String> frames = new java.util.LinkedHashSet<>();
            try {
                for (int i = 0; i < 25; i++) {
                    Thread.sleep(80);
                    for (StackTraceElement frame : main.getStackTrace()) {
                        if (frame.getClassName().startsWith("com.eza.spicyex.")) continue;
                        frames.add(frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
                    }
                }
            } catch (Throwable t) {
                XpLog.log(PREFIX + "sampler failed: " + t.getClass().getSimpleName());
            }
            StringBuilder chunk = new StringBuilder();
            int part = 0;
            for (String frame : frames) {
                if (chunk.length() + frame.length() > 700) {
                    XpLog.log(PREFIX + "sample" + part++ + " " + chunk);
                    chunk.setLength(0);
                }
                chunk.append(frame).append(" | ");
            }
            if (chunk.length() > 0) XpLog.log(PREFIX + "sample" + part + " " + chunk);
            XpLog.log(PREFIX + "sample done frames=" + frames.size());
        }, "SettingsTraceSampler");
        sampler.setDaemon(true);
        sampler.start();
    }

    private static void install(ClassLoader loader, String className) {
        try {
            Class<?> cls = Class.forName(className, false, loader);
            XpHooks.findAfter(cls, "onResume", "settingsTrace:" + className,
                    param -> record(param.thisObject));
            XpLog.log(PREFIX + "hooked " + className);
        } catch (Throwable t) {
            XpLog.log(PREFIX + className + " unavailable: " + t.getClass().getSimpleName());
        }
    }

    private static void record(Object fragment) {
        if (fragment == null) return;
        String name = fragment.getClass().getName();
        if (!SEEN.add(name)) return;
        XpLog.log(PREFIX + "fragment=" + name + " stack=" + stackOf());
    }

    private static String stackOf() {
        StringBuilder stack = new StringBuilder();
        int count = 0;
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.eza.spicyex.")) continue;
            if (count > 0) stack.append(" < ");
            stack.append(cls).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (++count >= MAX_FRAMES) break;
        }
        return stack.toString();
    }
}
