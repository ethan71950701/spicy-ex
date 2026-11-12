package com.eza.spicyex.hooks;

import android.app.Activity;

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
        StringBuilder stack = new StringBuilder();
        StackTraceElement[] frames = new Throwable().getStackTrace();
        int count = 0;
        for (StackTraceElement frame : frames) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.eza.spicyex.")) continue;
            if (count > 0) stack.append(" < ");
            stack.append(cls).append('.').append(frame.getMethodName()).append(':').append(frame.getLineNumber());
            if (++count >= MAX_FRAMES) break;
        }
        XpLog.log(PREFIX + "fragment=" + name + " stack=" + stack);
    }
}
