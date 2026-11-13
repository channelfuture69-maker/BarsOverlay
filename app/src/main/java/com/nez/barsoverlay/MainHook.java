package com.nez.barsoverlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Hook generik (tanpa nama class launcher): setiap RemoteTransition yang dibuat
 * launcher dipantau, startAnimation -> tampilkan overlay, finish -> sembunyikan.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> DONE = Collections.synchronizedSet(new HashSet<Class<?>>());
    private static final Runnable HIDE = new Runnable() {
        @Override public void run() { BarOverlay.hide(); }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        try {
            final ClassLoader cl = lpp.classLoader;
            final Class<?> irt = XposedHelpers.findClass("android.window.IRemoteTransition", cl);
            final Class<?> fcb = XposedHelpers.findClass("android.window.IRemoteTransitionFinishedCallback", cl);
            Class<?> rt = XposedHelpers.findClass("android.window.RemoteTransition", cl);

            XposedBridge.hookAllConstructors(rt, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    for (Object a : p.args) {
                        if (a != null && irt.isInstance(a)) hookRunner(a.getClass(), fcb);
                    }
                }
            });
            XposedBridge.log("BarsOverlay: aktif di " + lpp.packageName);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static void hookRunner(Class<?> c, final Class<?> fcb) {
        if (!DONE.add(c)) return;
        XposedBridge.hookAllMethods(c, "startAnimation", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                for (Object a : p.args) {
                    if (a != null && fcb.isInstance(a)) hookFinish(a.getClass());
                }
                H.post(new Runnable() {
                    @Override public void run() { BarOverlay.show(app()); }
                });
                H.removeCallbacks(HIDE);
                H.postDelayed(HIDE, 2500); // cadangan kalau callback finish tidak tertangkap
            }
        });
    }

    private static void hookFinish(Class<?> c) {
        if (!DONE.add(c)) return;
        XposedBridge.hookAllMethods(c, "onTransitionFinished", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam p) {
                H.postDelayed(HIDE, 60);
            }
        });
    }

    private static Context app() {
        try {
            return (Context) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null), "currentApplication");
        } catch (Throwable t) {
            return null;
        }
    }
}
